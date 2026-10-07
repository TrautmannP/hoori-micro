package hoori.micro;

import hoori.concurrent.http.HttpTasks;
import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.HttpClientClosedException;
import hoori.http.PoolOverloadedException;
import hoori.http.RequestBudget;
import hoori.http.Response;
import hoori.rest.json.Json;
import hoori.rest.json.JsonException;
import hoori.rest.json.JsonLimits;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-service endpoint selection and shared pools. Registry I/O stays on the control path. */
final class ServiceBroker {
    @FunctionalInterface
    interface Exchange {
        Response send(URI target, String method, Headers headers, byte[] body, RequestBudget budget) throws IOException;
    }

    static final String ENDPOINT_HEADER = "X-Hoori-Endpoint", VERSION_HEADER = "X-Hoori-Version";
    private static final byte[] EMPTY = new byte[0];

    private final ServiceDefinition service;
    private final ServiceConfig config;
    private final Exchange exchange, control;
    private final JsonLimits limits;
    private final Admission outgoing;
    private byte[] registration;
    private final AtomicInteger turn = new AtomicInteger();
    final Executions executions;
    private volatile Catalog.Filter filter;
    private volatile View view = View.EMPTY;
    private boolean publicCatalog;
    private final Random jitter = new Random();
    private volatile boolean registered; // written only by the control owner
    private volatile long registrationNanos;
    private volatile long refreshFailures;
    private volatile String failureReason = "none";
    private String retiredEpoch;
    private int failures;
    private volatile boolean registryReachable; // logs transitions, not every beat

    ServiceBroker(ServiceDefinition service, ServiceConfig config, Exchange exchange, JsonLimits limits) {
        this(service, config, exchange, exchange, limits);
    }

    ServiceBroker(
            ServiceDefinition service, ServiceConfig config, Exchange exchange, Exchange control, JsonLimits limits) {
        if (service == null || config == null || exchange == null || control == null || limits == null)
            throw new NullPointerException();

        this.service = service;
        this.config = config;
        this.exchange = exchange;
        this.control = control;
        this.limits = limits;
        executions = new Executions(config);
        outgoing = new Admission(config.outgoingCalls, config.outgoingPendingCalls);
        filter = Catalog.Filter.consumer(service.dependencies, false);
    }

    void prepare() {
        if (registration != null) return;

        registration = Json.encode(
                new Catalog.Instance(
                        config.instanceId,
                        service.name,
                        service.version,
                        config.advertiseUrl,
                        service.endpoints.values().toArray(new Catalog.Entry[0]),
                        service.contractHash,
                        service.apiGroup),
                Catalog.INSTANCE,
                limits);
        filter = Catalog.Filter.consumer(service.dependencies, publicCatalog);
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

    /** Local observations only; a scrape neither renews a lease nor fetches a catalog. */
    String discoveryMetrics() {
        View current = view;
        long now = System.nanoTime();
        boolean fresh = current.live && now - current.fetchedNanos < config.catalogMaxAgeMillis * 1_000_000L;
        long age = current == View.EMPTY ? -1 : Math.max(0, (now - current.fetchedNanos) / 1_000_000L);
        boolean activeRegistration = registered && now - registrationNanos < config.registryTtlMillis * 1_000_000L;

        return "hoori_micro_discovery_enabled " + (needsDiscovery() ? 1 : 0) + "\n"
                + "hoori_micro_registry_available " + (registryReachable ? 1 : 0) + "\n"
                + "hoori_micro_registration_active " + (activeRegistration ? 1 : 0) + "\n"
                + "hoori_micro_catalog_age_millis " + age + "\n"
                + "hoori_micro_catalog_fresh " + (fresh ? 1 : 0) + "\n"
                + "hoori_micro_catalog_complete " + (fresh && current.catalog.complete ? 1 : 0) + "\n"
                + "hoori_micro_registry_refresh_failures_total " + refreshFailures + "\n"
                + "hoori_micro_registry_failure{reason=\"" + failureReason + "\"} 1\n"
                + "hoori_micro_gateway_publication_available " + (fresh && current.publication != null ? 1 : 0) + "\n"
                + "hoori_micro_gateway_routes_withheld "
                + (fresh && current.publication != null ? current.publication.withheldRoutes : 0) + "\n";
    }

    void stop() {
        outgoing.stop();
    }

    void close() {
        outgoing.close();
    }

    <T> T call(
            Invocation invocation,
            RequestBudget budget,
            String service,
            int version,
            HttpEndpoint endpoint,
            RemoteClient.Encoder encoder,
            RemoteClient.Decoder<T> decoder)
            throws IOException {
        if (dependency(service) != version) throw new IllegalArgumentException("Conflicting client version");

        try (Admission.Permit permit = admit(invocation, budget)) {
            permit.check(outgoing);
            ClientRequest request = encoder.encode(limits);
            Response response = invoke(
                    permit,
                    invocation,
                    service,
                    version,
                    endpoint,
                    request.target(),
                    request.headers,
                    request.body,
                    snapshot());

            if (response.status < 200 || response.status >= 300) throw failure(service, endpoint, response);

            T result;
            try {
                result = decoder.decode(response, limits);
            } catch (JsonException | IllegalArgumentException invalid) {
                throw new ServiceCallException(service, response.status, "invalid response", null);
            }
            permit.check(outgoing);

            return result;
        }
    }

    ServiceCallException failure(String service, HttpEndpoint endpoint, Response response) {
        return new ServiceCallException(
                service,
                response.status,
                "unexpected HTTP status",
                null,
                endpoint,
                ValidationErrors.read(response),
                HttpResponses.problem(response));
    }

    Response invoke(
            Admission.Permit permit,
            Invocation invocation,
            String service,
            int version,
            HttpEndpoint endpoint,
            String pathAndQuery,
            Headers explicit,
            byte[] body,
            View source)
            throws IOException {
        return invoke(permit, invocation, service, version, endpoint, pathAndQuery, explicit, body, source, null);
    }

    Response invoke(
            Admission.Permit permit,
            Invocation invocation,
            String service,
            int version,
            HttpEndpoint endpoint,
            String pathAndQuery,
            Headers explicit,
            byte[] body,
            View source,
            Gateway.Route published)
            throws IOException {
        permit.check(outgoing);
        Catalog catalog = fresh(source) ? source.catalog : Catalog.EMPTY;
        String key = endpoint.key();
        Catalog.Instance target = catalog.select(service, version, key, turn.getAndIncrement(), published);

        if (target == null) throw new ServiceCallException(service, 0, "no instance offers endpoint", null);

        if (!pathAndQuery.startsWith("/") || pathAndQuery.length() > 8192 || body.length > config.bodyBytes)
            throw new IllegalArgumentException("Outbound request limit");

        Headers headers = new Headers();
        for (int i = 0; i < explicit.size(); i++) {
            if (!ClientRequest.allowedHeader(explicit.name(i)))
                throw new IllegalArgumentException("Reserved client header");

            headers.add(explicit.name(i), explicit.value(i));
        }

        if (!endpoint.produces().isEmpty() && headers.get("Accept") == null) headers.add("Accept", endpoint.produces());

        if (!endpoint.consumes().isEmpty() && headers.get("Content-Type") == null)
            headers.add("Content-Type", endpoint.consumes());

        headers.add(ENDPOINT_HEADER, key).add(VERSION_HEADER, Integer.toString(version));

        if (invocation != null) headers.add("X-Request-ID", invocation.requestId());

        Response response;
        try {
            response = exchange.send(
                    URI.create(target.url + pathAndQuery), endpoint.method(), headers, body, permit.budget);
        } catch (PoolOverloadedException overloaded) {
            throw new CallRejectedException();
        } catch (SocketTimeoutException expired) {
            permit.expired();
            throw expired;
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException failed) {
            throw new ServiceCallException(service, 0, "transport failed", failed);
        }
        permit.check(outgoing);

        if (response.body.length > config.bodyBytes)
            throw new ServiceCallException(service, response.status, "response limit", null);

        return response;
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
                    Gateway.EMPTY_ROUTES,
                    Gateway.EMPTY_ROUTER);
    }

    void followPublicCatalog() {
        filter = Catalog.Filter.consumer(service.dependencies, true);
        publicCatalog = true;
    }

    boolean followsPublicCatalog() {
        return publicCatalog;
    }

    boolean needsDiscovery() {
        return !service.endpoints.isEmpty() || !filter.key.equals("none");
    }

    /** One control exchange; an unknown lease permits one full, idempotent re-registration. */
    void beat(boolean ready) throws IOException {
        prepare();
        boolean register = ready && !service.endpoints.isEmpty();

        catalog(); // Also releases expired rows during idle/control failures; epoch history stays bounded.

        if (!register && filter.key.equals("none")) return;

        try {
            View known = view;
            Catalog.Filter requested = filter;
            Headers headers = new Headers().add("Accept", "application/json").add(Catalog.VIEW_HEADER, requested.key);

            if (known.live)
                headers.add(Catalog.EPOCH_HEADER, known.catalog.epoch)
                        .add(Catalog.REVISION_HEADER, Long.toString(known.catalog.revision))
                        .add(Catalog.KNOWN_VIEW_HEADER, known.scope);

            String path = "/_hoori/instances/" + config.instanceId;
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
                response =
                        control.send(URI.create(config.registryUrl + "/_hoori/catalog"), "GET", headers, EMPTY, null);
            }

            accept(response, known, requested);

            if (register) {
                registered = true;
                registrationNanos = System.nanoTime();
            }

            failures = 0;
            failureReason = "none";
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

        if (refreshFailures < Long.MAX_VALUE) refreshFailures++;

        failureReason = failed instanceof SocketTimeoutException
                ? "timeout"
                : failed instanceof IOException ? "transport" : "rejected";
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
        if (service.endpoints.isEmpty()) return;

        try {
            control.send(
                    URI.create(config.registryUrl + "/_hoori/instances/" + config.instanceId),
                    "DELETE",
                    new Headers(),
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

        hoori.rest.Router routing = accepted == old ? current.routing : Gateway.routing(routes);

        if (!sameEpoch && old != Catalog.EMPTY) retiredEpoch = old.epoch;

        GatewayPublication publication = accepted == old
                ? current.publication
                : publicCatalog ? GatewayPublication.build(accepted, routes) : null;

        synchronized (this) {
            view = new View(accepted, fetchedNanos, scope, true, routes, routing, publication);
        }
    }

    private void accept(Response response, View sent, Catalog.Filter requested) throws IOException {
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

                view = new View(
                        sent.catalog, System.nanoTime(), sent.scope, true, sent.routes, sent.routing, sent.publication);
            }

            return;
        }

        if (response.status != 200 || !jsonContentType(response.headers)) throw new IOException("Registry status");

        Catalog next = Json.decode(response.body, Catalog.CODEC, limits);

        if (next == null
                || !next.epoch.equals(epoch)
                || !Long.toString(next.revision).equals(revision)) throw new IOException("Unmatched catalog revision");

        accept(next, requested.key);
    }

    static final class View {
        static final View EMPTY = new View(Catalog.EMPTY, 0, "", false, Gateway.EMPTY_ROUTES, Gateway.EMPTY_ROUTER);
        final Catalog catalog;
        final long fetchedNanos;
        final String scope;
        final boolean live;
        final Gateway.Route[] routes;
        final hoori.rest.Router routing;
        final GatewayPublication publication;

        View(
                Catalog catalog,
                long fetchedNanos,
                String scope,
                boolean live,
                Gateway.Route[] routes,
                hoori.rest.Router routing) {
            this(catalog, fetchedNanos, scope, live, routes, routing, null);
        }

        View(
                Catalog catalog,
                long fetchedNanos,
                String scope,
                boolean live,
                Gateway.Route[] routes,
                hoori.rest.Router routing,
                GatewayPublication publication) {
            this.catalog = catalog;
            this.fetchedNanos = fetchedNanos;
            this.scope = scope;
            this.live = live;
            this.routes = routes;
            this.routing = routing;
            this.publication = publication;
        }
    }

    private void reachable(boolean value, Exception failure) {
        if (value != registryReachable)
            System.err.println(
                    value
                            ? "registry_available service=" + service.name
                            : "registry_unavailable service=" + service.name + " reason=" + failureReason);

        registryReachable = value;
    }

    private int dependency(String name) {
        Integer version = service.dependencies.get(name);

        if (version == null) throw new IllegalArgumentException("Undeclared service dependency");

        return version;
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
