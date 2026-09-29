package hoori.micro;

import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.Request;
import hoori.http.Response;
import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-service broker. Resolves "service.action" against a local catalog copy, picks an instance
 * that offers exactly this action and version, and calls its /_hoori/invoke over the service's
 * single Hoori pool. Only the heartbeat thread talks to the registry, never a business call.
 * No retries, redirects, ambient identity or peer bodies in errors.
 */
final class ServiceBroker {
    @FunctionalInterface
    interface Exchange {
        Response send(Request context, URI target, String method, Headers headers, byte[] body) throws IOException;
    }

    static final String INVOKE_PATH = "/_hoori/invoke";
    static final String ACTION_HEADER = "X-Hoori-Action", VERSION_HEADER = "X-Hoori-Version";
    private static final byte[] EMPTY = new byte[0];

    private final Service service;
    private final ServiceConfig config;
    private final Exchange exchange;
    private final JsonLimits limits;
    private final byte[] registration;
    private final AtomicInteger turn = new AtomicInteger();
    private volatile boolean followCatalog;
    private volatile Catalog catalog = Catalog.EMPTY;
    private volatile long fetchedNanos;
    private byte[] fetchedBody = EMPTY; // heartbeat thread only
    private boolean registryReachable = true; // heartbeat thread only; logs transitions, not every beat

    ServiceBroker(Service service, ServiceConfig config, Exchange exchange, JsonLimits limits) {
        if (service == null || config == null || exchange == null || limits == null) throw new NullPointerException();

        this.service = service;
        this.config = config;
        this.exchange = exchange;
        this.limits = limits;
        Catalog.Entry[] entries = new Catalog.Entry[service.actions.size()];
        int i = 0;
        for (Service.Definition<?, ?> action : service.actions.values()) entries[i++] = action.entry();
        registration = Json.encode(
                new Catalog.Instance(config.instanceId, service.name, service.version, config.advertiseUrl, entries),
                Catalog.INSTANCE,
                limits);
        followCatalog = !service.dependencies.isEmpty();
    }

    static Exchange transport(HttpClient http) {
        if (http == null) throw new NullPointerException("http");

        return (context, target, method, headers, body) -> context == null
                ? http.exchange(target, method, headers, body)
                : http.exchange(context, target, method, headers, body);
    }

    <I, O> O call(Request context, Action<I, O> action, I input) throws IOException {
        if (action == null || input == null) throw new NullPointerException();

        String[] name = ServiceName.qualified(action.name);
        byte[] result =
                invoke(context, name[0], dependency(name[0]), name[1], Json.encode(input, action.input, limits));

        return decode(name[0], result, action.output);
    }

    Object call(Request context, String action, Map<String, ?> params) throws IOException {
        if (params == null) throw new NullPointerException("params");

        String[] name = ServiceName.qualified(action);
        byte[] result =
                invoke(context, name[0], dependency(name[0]), name[1], Json.encode(params, JsonTree.CODEC, limits));

        return decode(name[0], result, JsonTree.CODEC);
    }

    /** Returns the successful JSON body. Also used by the gateway with the catalog's version. */
    byte[] invoke(Request context, String service, int version, String action, byte[] params) throws IOException {
        Catalog.Instance target = catalog().select(service, version, action, turn.getAndIncrement());

        if (target == null) throw new ServiceCallException(service, 0, "no instance offers " + action, null);

        Headers headers = new Headers()
                .add("Accept", "application/json")
                .add("Content-Type", "application/json")
                .add(ACTION_HEADER, service + "." + action)
                .add(VERSION_HEADER, Integer.toString(version));
        Response response;
        try {
            response = exchange.send(context, URI.create(target.url + INVOKE_PATH), "POST", headers, params);
        } catch (InterruptedIOException cancelledOrExpired) {
            // Keep the SDK's cancellation/timeout semantics and interrupt status intact.
            throw cancelledOrExpired;
        } catch (IOException failed) {
            throw new ServiceCallException(service, 0, "transport failed", failed);
        }

        if (response.status < 200 || response.status >= 300)
            throw new ServiceCallException(service, response.status, "unexpected HTTP status", null);

        if (!jsonContentType(response.headers))
            throw new ServiceCallException(service, response.status, "expected application/json", null);

        return response.body;
    }

    /** Current snapshot, or EMPTY once no refresh succeeded within HOORI_CATALOG_MAX_AGE_MS. */
    Catalog catalog() {
        Catalog current = catalog;
        long age = System.nanoTime() - fetchedNanos;

        return current != Catalog.EMPTY && age < config.catalogMaxAgeMillis * 1_000_000L ? current : Catalog.EMPTY;
    }

    void followCatalog() {
        followCatalog = true;
    }

    /** One heartbeat: register while ready (the reply is the catalog), else refresh only if needed. */
    void beat(boolean ready) throws InterruptedIOException {
        boolean register = ready && !service.actions.isEmpty();

        if (!register && !followCatalog) return;

        try {
            Headers headers = new Headers().add("Accept", "application/json");
            Response response = register
                    ? exchange.send(
                            null,
                            URI.create(config.registryUrl + "/v1/instances/" + config.instanceId),
                            "PUT",
                            headers.add("Content-Type", "application/json"),
                            registration)
                    : exchange.send(null, URI.create(config.registryUrl + "/v1/catalog"), "GET", headers, EMPTY);

            if (response.status != 200 || !jsonContentType(response.headers)) throw new IOException("Registry status");

            accept(response.body);
            reachable(true, null);
        } catch (InterruptedIOException stopped) {
            throw stopped;
        } catch (IOException | RuntimeException failed) {
            reachable(false, failed);
        }
    }

    /** Best effort on graceful stop; TTL expiry covers crashes and an unreachable registry. */
    void deregister() {
        if (service.actions.isEmpty()) return;

        try {
            exchange.send(
                    null,
                    URI.create(config.registryUrl + "/v1/instances/" + config.instanceId),
                    "DELETE",
                    new Headers(),
                    EMPTY);
        } catch (IOException ignored) {
            /* Expires after HOORI_REGISTRY_TTL_MS. */
        }
    }

    void accept(byte[] body) {
        if (!Arrays.equals(body, fetchedBody)) {
            Catalog next = Json.decode(body, Catalog.CODEC, limits);

            if (next == null) throw new JsonException("Null catalog");

            // A restarted registry is incomplete until one TTL has passed; keep the last full view.
            if (!next.complete && catalog() != Catalog.EMPTY) return;

            catalog = next;
            fetchedBody = body;
        }

        fetchedNanos = System.nanoTime();
    }

    private void reachable(boolean value, Exception failure) {
        if (value != registryReachable)
            System.err.println(
                    value
                            ? "registry_available service=" + service.name
                            : "registry_unavailable service=" + service.name + " type="
                                    + failure.getClass().getName());

        registryReachable = value;
    }

    private int dependency(String name) {
        Integer version = service.dependencies.get(name);

        if (version == null) throw new IllegalArgumentException("Undeclared service dependency");

        return version;
    }

    private <T> T decode(String service, byte[] body, JsonCodec<T> codec) throws ServiceCallException {
        try {
            T result = Json.decode(body, codec, limits);

            if (result == null) throw new JsonException("Null JSON response");

            return result;
        } catch (JsonException invalid) {
            // Do not retain decoder messages: a custom codec may include peer data there.
            throw new ServiceCallException(service, 200, "invalid JSON response", null);
        }
    }

    private static boolean jsonContentType(Headers headers) {
        String type = null;
        for (int i = 0; i < headers.size(); i++) {
            if (headers.name(i).equalsIgnoreCase("Content-Type")) {
                if (type != null) return false;

                type = headers.value(i).trim();
            }
        }

        if (type == null) return false;

        int semicolon = type.indexOf(';');

        if (semicolon < 0) return type.equalsIgnoreCase("application/json");

        if (!type.substring(0, semicolon).trim().equalsIgnoreCase("application/json")) return false;

        String parameter = type.substring(semicolon + 1).trim();
        int equals = parameter.indexOf('=');

        if (equals < 0 || !parameter.substring(0, equals).trim().equalsIgnoreCase("charset")) return false;

        String charset = parameter.substring(equals + 1).trim();

        return charset.equalsIgnoreCase("utf-8") || charset.equalsIgnoreCase("\"utf-8\"");
    }
}
