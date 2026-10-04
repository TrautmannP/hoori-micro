package hoori.micro;

import hoori.concurrent.http.HttpTasks;
import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.HttpClientClosedException;
import hoori.http.PoolOverloadedException;
import hoori.http.RequestBudget;
import hoori.http.Response;
import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-service broker. Resolves "service.action" against a local catalog copy, picks an instance
 * that offers exactly this action and version, and calls its /_hoori/invoke over the service's
 * data Hoori pool. Only the heartbeat thread uses the separate control pool for the registry.
 * No retries, redirects, ambient identity or peer bodies in errors.
 */
final class ServiceBroker {
    @FunctionalInterface
    interface Exchange {
        Response send(URI target, String method, Headers headers, byte[] body, RequestBudget budget) throws IOException;
    }

    static final String INVOKE_PATH = "/_hoori/invoke";
    static final String ACTION_HEADER = "X-Hoori-Action", VERSION_HEADER = "X-Hoori-Version";
    private static final byte[] EMPTY = new byte[0];

    private final Service service;
    private final ServiceConfig config;
    private final Exchange exchange, control;
    private final JsonLimits limits;
    private final Admission outgoing;
    private final byte[] registration;
    private final AtomicInteger turn = new AtomicInteger();
    final Executions executions;
    private volatile Catalog.Filter filter;
    private volatile View view = View.EMPTY;
    private boolean publicCatalog;
    private final Random jitter = new Random();
    private boolean registered; // all control state below is heartbeat-thread owned
    private String retiredEpoch;
    private int failures;
    private boolean registryReachable = true; // heartbeat thread only; logs transitions, not every beat

    ServiceBroker(Service service, ServiceConfig config, Exchange exchange, JsonLimits limits) {
        this(service, config, exchange, exchange, limits);
    }

    ServiceBroker(Service service, ServiceConfig config, Exchange exchange, Exchange control, JsonLimits limits) {
        if (service == null || config == null || exchange == null || control == null || limits == null)
            throw new NullPointerException();

        this.service = service;
        this.config = config;
        this.exchange = exchange;
        this.control = control;
        this.limits = limits;
        executions = new Executions(config);
        outgoing = new Admission(config.outgoingCalls, config.outgoingPendingCalls);
        Catalog.Entry[] entries = new Catalog.Entry[service.actions.size()];
        int i = 0;
        for (Service.Definition<?, ?> action : service.actions.values()) entries[i++] = action.entry();
        registration = Json.encode(
                new Catalog.Instance(config.instanceId, service.name, service.version, config.advertiseUrl, entries),
                Catalog.INSTANCE,
                limits);
        filter = Catalog.Filter.consumer(service.dependencies, false);
    }

    static Exchange dataTransport(HttpClient http) {
        if (http == null) throw new NullPointerException("http");

        return (target, method, headers, body, budget) ->
                HttpTasks.exchange(http, target, method, headers, body, budget);
    }

    static Exchange controlTransport(HttpClient http) {
        if (http == null) throw new NullPointerException("http");

        return (target, method, headers, body, budget) -> {
            if (budget == null) return http.exchange(target, method, headers, body);

            return http.exchange(target, method, headers, body, budget);
        };
    }

    int callTimeoutMillis() {
        return config.clientTimeoutMillis;
    }

    Admission.Permit admit(Invocation invocation, RequestBudget budget) throws IOException {

        // A dispatched request may still make immediate calls during drain; no waiting/new background work.
        return outgoing.acquire(
                budget.withRemainingMillisHeader(Context.BUDGET_HEADER), executions.requestOwned(invocation));
    }

    Admission.Stats admissionStats() {
        return outgoing.stats();
    }

    void stop() {
        outgoing.stop();
    }

    void close() {
        outgoing.close();
    }

    <I, O> O call(Invocation invocation, RequestBudget budget, Action<I, O> action, I input) throws IOException {
        if (action == null || input == null) throw new NullPointerException();

        int version = dependency(action.service);
        try (Admission.Permit permit = admit(invocation, budget)) {
            permit.check(outgoing);
            byte[] result = invoke(
                    permit,
                    invocation,
                    action.service,
                    version,
                    action.operation,
                    Json.encode(input, action.input, limits));
            O decoded = decode(action.service, result, action.output);
            permit.check(outgoing);

            return decoded;
        }
    }

    Object call(Invocation invocation, RequestBudget budget, String action, Map<String, ?> params) throws IOException {
        if (params == null) throw new NullPointerException("params");

        String[] name = ServiceName.qualified(action);
        int version = dependency(name[0]);
        try (Admission.Permit permit = admit(invocation, budget)) {
            permit.check(outgoing);
            byte[] result =
                    invoke(permit, invocation, name[0], version, name[1], Json.encode(params, JsonTree.CODEC, limits));
            Object decoded = decode(name[0], result, JsonTree.CODEC);
            permit.check(outgoing);

            return decoded;
        }
    }

    /** Returns the successful JSON body. Also used by the gateway with the catalog's version. */
    byte[] invoke(
            Admission.Permit permit, Invocation invocation, String service, int version, String action, byte[] params)
            throws IOException {
        return invoke(permit, invocation, service, version, action, params, snapshot());
    }

    byte[] invoke(
            Admission.Permit permit,
            Invocation invocation,
            String service,
            int version,
            String action,
            byte[] params,
            View source)
            throws IOException {
        permit.check(outgoing);
        Catalog.Instance target = (fresh(source) ? source.catalog : Catalog.EMPTY)
                .select(service, version, action, turn.getAndIncrement());

        if (target == null) throw new ServiceCallException(service, 0, "no instance offers " + action, null);

        Headers headers = new Headers()
                .add("Accept", "application/json")
                .add("Content-Type", "application/json")
                .add(ACTION_HEADER, service + "." + action)
                .add(VERSION_HEADER, Integer.toString(version));

        if (invocation != null) headers.add("X-Request-ID", invocation.requestId());

        Response response;
        try {
            response = exchange.send(target.invokeTarget, "POST", headers, params, permit.budget);
        } catch (PoolOverloadedException overloaded) {
            throw new CallRejectedException();
        } catch (SocketTimeoutException expired) {
            permit.expired();
            throw expired;
        } catch (InterruptedIOException cancelledOrExpired) {
            // Keep the SDK's cancellation/timeout semantics and interrupt status intact.
            throw cancelledOrExpired;
        } catch (IOException failed) {
            throw new ServiceCallException(service, 0, "transport failed", failed);
        }

        if (response.status < 200 || response.status >= 300)
            throw new ServiceCallException(
                    service,
                    response.status,
                    "unexpected HTTP status",
                    null,
                    service + "." + action,
                    ValidationErrors.read(response));

        if (!jsonContentType(response.headers))
            throw new ServiceCallException(service, response.status, "expected application/json", null);

        permit.check(outgoing);

        return response.body;
    }

    /** Current snapshot, or EMPTY once no refresh succeeded within HOORI_CATALOG_MAX_AGE_MS. */
    Catalog catalog() {
        return snapshot().catalog;
    }

    /** Catalog and prepared gateway routes are one publication, read once by each gateway request. */
    View snapshot() {
        View current = view;

        if (fresh(current)) return current;

        expire(current);

        return View.EMPTY;
    }

    private boolean fresh(View current) {
        return current.live && System.nanoTime() - current.fetchedNanos < config.catalogMaxAgeMillis * 1_000_000L;
    }

    private synchronized void expire(View current) {
        if (view == current && current.live)
            view = new View(
                    new Catalog(
                            current.catalog.epoch,
                            current.catalog.revision,
                            current.catalog.complete,
                            new Catalog.Instance[0]),
                    current.fetchedNanos,
                    current.scope,
                    false,
                    Gateway.EMPTY_ROUTES);
    }

    void followPublicCatalog() {
        filter = Catalog.Filter.consumer(service.dependencies, true);
        publicCatalog = true;
    }

    boolean needsDiscovery() {
        return !service.actions.isEmpty() || !filter.key.equals("none");
    }

    /** One control exchange; an unknown lease permits one full, idempotent re-registration. */
    void beat(boolean ready) throws IOException {
        boolean register = ready && !service.actions.isEmpty();

        catalog(); // Also releases expired rows during idle/control failures; version tokens stay bounded.

        if (!register && filter.key.equals("none")) return;

        try {
            View known = view;
            Catalog.Filter requested = filter;
            Headers headers = new Headers()
                    .add("Accept", "application/json")
                    .add(Catalog.PROTOCOL_HEADER, "2")
                    .add(Catalog.VIEW_HEADER, requested.key);

            if (known.live)
                headers.add(Catalog.EPOCH_HEADER, known.catalog.epoch)
                        .add(Catalog.REVISION_HEADER, Long.toString(known.catalog.revision))
                        .add(Catalog.KNOWN_VIEW_HEADER, known.scope);

            String path = "/v1/instances/" + config.instanceId;
            Response response;

            if (register && registered) {
                response = control.send(URI.create(config.registryUrl + path + "/lease"), "POST", headers, EMPTY, null);

                if (response.status == 404) registered = false;
            } else response = null;

            if (register && !registered) {
                response = control.send(
                        URI.create(config.registryUrl + path),
                        "PUT",
                        headers.add("Content-Type", "application/json"),
                        registration,
                        null);
            } else if (!register) {
                response = control.send(URI.create(config.registryUrl + "/v1/catalog"), "GET", headers, EMPTY, null);
            }

            accept(response, known, requested);

            if (register) registered = true;

            failures = 0;
            reachable(true, null);
        } catch (SocketTimeoutException expired) {
            if (Thread.currentThread().isInterrupted()) throw expired;

            failedBeat(expired);
        } catch (InterruptedIOException | HttpClientClosedException stopped) {
            throw stopped;
        } catch (IOException | RuntimeException failed) {
            failedBeat(failed);
        }
    }

    private void failedBeat(Exception failed) {
        failures = Math.min(3, failures + 1);
        reachable(false, failed);
    }

    /** Successful renewals leave half the TTL for exchange/scheduling; failed attempts back off. */
    int heartbeatDelayMillis() {
        int base = failures == 0
                ? Math.min(config.heartbeatMillis, config.registryTtlMillis / 2)
                : Math.min(60_000, config.heartbeatMillis * (1 << failures));
        int spread = Math.max(1, base / 10);

        return Math.max(1, base - spread + jitter.nextInt(2 * spread + 1));
    }

    /** Best effort on graceful stop; TTL expiry covers crashes and an unreachable registry. */
    void deregister() {
        if (service.actions.isEmpty()) return;

        try {
            control.send(
                    URI.create(config.registryUrl + "/v1/instances/" + config.instanceId),
                    "DELETE",
                    new Headers().add(Catalog.PROTOCOL_HEADER, "2"),
                    EMPTY,
                    null);
        } catch (IOException ignored) {
            /* Expires after HOORI_REGISTRY_TTL_MS. */
        }
    }

    void accept(byte[] body) {
        Catalog.Filter requested = filter;
        accept(requested.apply(Json.decode(body, Catalog.CODEC, limits)), requested.key);
    }

    private void accept(Catalog next, String scope) {
        if (next == null) throw new JsonException("Null catalog");

        View current = view;
        Catalog old = current.catalog;
        boolean sameEpoch = next.epoch.equals(old.epoch);

        if (next.epoch.equals(retiredEpoch)
                || sameEpoch && (next.revision < old.revision || old.complete && !next.complete)) return;

        // A new registry cannot extend the retained full view while it rebuilds for one TTL.
        if (!sameEpoch && !next.complete && old.complete && catalog() != Catalog.EMPTY) return;

        Catalog accepted =
                sameEpoch && next.revision == old.revision && current.live && scope.equals(current.scope) ? old : next;
        long fetchedNanos = System.nanoTime();
        // The sole registrar builds before publication, without holding the request/expiry monitor.
        Gateway.Route[] routes =
                accepted == old ? current.routes : publicCatalog ? Gateway.build(accepted) : Gateway.EMPTY_ROUTES;

        if (!sameEpoch && old != Catalog.EMPTY) retiredEpoch = old.epoch;

        synchronized (this) {
            view = new View(accepted, fetchedNanos, scope, true, routes);
        }
    }

    private void accept(Response response, View sent, Catalog.Filter requested) throws IOException {
        if (!"2".equals(response.headers.get(Catalog.PROTOCOL_HEADER))) throw new IOException("Registry protocol");

        if (!requested.key.equals(response.headers.get(Catalog.VIEW_HEADER))) throw new IOException("Registry view");

        if (!requested.key.equals(filter.key)) return;

        String epoch = response.headers.get(Catalog.EPOCH_HEADER),
                revision = response.headers.get(Catalog.REVISION_HEADER);

        if (response.status == 204) {
            if (!sent.live
                    || !sent.scope.equals(requested.key)
                    || !sent.catalog.epoch.equals(epoch)
                    || !Long.toString(sent.catalog.revision).equals(revision)
                    || response.body.length != 0
                    || view != sent) throw new IOException("Unmatched catalog confirmation");

            synchronized (this) {
                if (view != sent) throw new IOException("Expired catalog confirmation");

                view = new View(sent.catalog, System.nanoTime(), sent.scope, true, sent.routes);
            }

            return;
        }

        if (response.status != 200 || !jsonContentType(response.headers)) throw new IOException("Registry status");

        Catalog next = Json.decode(response.body, Catalog.CODEC, limits);

        if (next == null
                || !next.epoch.equals(epoch)
                || !Long.toString(next.revision).equals(revision)) throw new IOException("Unmatched catalog version");

        accept(next, requested.key);
    }

    static final class View {
        static final View EMPTY = new View(Catalog.EMPTY, 0, "", false, Gateway.EMPTY_ROUTES);
        final Catalog catalog;
        final long fetchedNanos;
        final String scope;
        final boolean live;
        final Gateway.Route[] routes;

        View(Catalog catalog, long fetchedNanos, String scope, boolean live, Gateway.Route[] routes) {
            this.catalog = catalog;
            this.fetchedNanos = fetchedNanos;
            this.scope = scope;
            this.live = live;
            this.routes = routes;
        }
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

    static boolean jsonContentType(Headers headers) {
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
