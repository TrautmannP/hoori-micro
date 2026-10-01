package hoori.micro;

import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.HttpServer;
import hoori.http.Limits;
import hoori.http.Response;
import hoori.rest.Attribute;
import hoori.rest.Router;
import hoori.rest.json.JsonLimits;
import hoori.runtime.RuntimeMetrics;
import hoori.runtime.Shutdown;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Explicit startup composition on Hoori's cooperative single carrier. run() owns registration,
 * draining and pool cleanup. Configure routes/actions before run(), never in handlers.
 */
public final class Microservice implements AutoCloseable {
    private final Attribute<Context> contextKey = new Attribute<>();

    private final Service service;
    private final ServiceConfig config;
    private final Router router = new Router();
    private final HttpClient http, control;
    private final ServiceBroker broker;
    private final Admission incoming;
    private final JsonLimits jsonLimits;

    private volatile HttpServer server;
    private volatile boolean closed, stopRequested, applicationReady = true;

    private boolean started;
    private Thread signalWatcher;
    private volatile Thread registrar;

    private Microservice(Service service, ServiceConfig config) {
        this.service = service;
        this.config = config;
        jsonLimits = new JsonLimits(64, 16384, 128, config.bodyBytes);
        incoming = new Admission(config.incomingCalls, config.incomingPendingCalls);
        http = new HttpClient(
                limits(config.clientConnections, config.clientTimeoutMillis)
                        .withPendingAcquires(config.clientPendingAcquires),
                config.clientPerOrigin,
                config.clientIdleMillis);
        control = new HttpClient(
                limits(1, config.controlTimeoutMillis).withPendingAcquires(0), 1, config.clientIdleMillis);
        broker = new ServiceBroker(
                service, config, ServiceBroker.transport(http), ServiceBroker.transport(control), jsonLimits);

        // One fixed internal entry point; new actions never need new routes. Absent without actions.
        if (!service.actions.isEmpty()) router.post(ServiceBroker.INVOKE_PATH, this::invoke);

        router.get("/health/live", request -> health(isLive()));
        router.get("/health/ready", request -> health(isReady()));
        router.get(
                "/metrics",
                request -> new Response(
                        200,
                        new Headers().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8"),
                        (server.metrics().prometheus()
                                        + poolMetrics(http.poolStats(), control.poolStats())
                                        + admissionMetrics(incoming.stats(), broker.admissionStats())
                                        + runtimeMetrics())
                                .getBytes(StandardCharsets.UTF_8)));
        router.onError((request, failure) -> {
            // Intentionally no stacktrace, URI, request/response body or secret in logs/errors.
            String id = request == null ? "unavailable" : request.id();
            System.err.println("request_failed service="
                    + config.name
                    + " request_id="
                    + id
                    + " type="
                    + failure.getClass().getName());

            if (failure instanceof CallRejectedException) return Response.text(503, "Local call capacity unavailable");

            if (failure instanceof ServiceCallException upstream)
                return Response.text(
                        upstream.upstreamStatus() == 503 || upstream.upstreamStatus() == 504
                                ? upstream.upstreamStatus()
                                : 502,
                        "Upstream service unavailable");

            if (failure instanceof SocketTimeoutException) return Response.text(504, "Request budget expired");

            if (failure instanceof InterruptedIOException) return Response.text(503, "Request cancelled");

            return Response.text(500, "Internal Server Error");
        });
    }

    public static Microservice create(Service service) {
        return create(service, Environment.system());
    }

    public static Microservice create(Service service, Environment env) {
        if (service == null) throw new NullPointerException("service");

        return new Microservice(service.freeze(), ServiceConfig.from(service.name, env));
    }

    public Router routes() {
        return router;
    }

    /** Call context for a route handler; request may be null for startup/background calls. */
    public Context context(hoori.rest.Request request) {
        if (request == null) return new Context(broker, null);

        Context context = request.attribute(contextKey);

        if (context == null) {
            context = new Context(broker, request.raw());
            request.attribute(contextKey, context);
        }

        return context;
    }

    ServiceBroker broker() {
        return broker;
    }

    HttpClient.PoolStats dataPoolStats() {
        return http.poolStats();
    }

    HttpClient.PoolStats controlPoolStats() {
        return control.poolStats();
    }

    Admission.Stats incomingStats() {
        return incoming.stats();
    }

    static String admissionMetrics(Admission.Stats incoming, Admission.Stats outgoing) {
        StringBuilder result = new StringBuilder();

        if (incoming != null) appendAdmissionMetrics(result, "incoming", incoming);

        if (outgoing != null) appendAdmissionMetrics(result, "outgoing", outgoing);

        return result.toString();
    }

    private static String runtimeMetrics() {
        RuntimeMetrics.Snapshot s = RuntimeMetrics.snapshot();

        return "hoori_micro_heap_used_bytes " + s.heapUsedBytes + "\n"
                + "hoori_micro_heap_committed_bytes " + s.heapCommittedBytes + "\n"
                + "hoori_micro_rss_bytes " + s.rssBytes + "\n"
                + "hoori_micro_allocated_bytes_total " + s.allocatedBytes + "\n"
                + "hoori_micro_gc_collections_total " + s.gcCollections + "\n"
                + "hoori_micro_tasks_active " + s.tasksActive + "\n"
                + "hoori_micro_tasks_waiting " + s.tasksWaiting + "\n"
                + "hoori_micro_handles_open " + s.serviceHandlesOpen + "\n";
    }

    private static void appendAdmissionMetrics(StringBuilder result, String direction, Admission.Stats stats) {
        result.append("hoori_micro_calls_active{direction=\"")
                .append(direction)
                .append("\"} ")
                .append(stats.active)
                .append('\n');
        result.append("hoori_micro_calls_pending{direction=\"")
                .append(direction)
                .append("\"} ")
                .append(stats.pending)
                .append('\n');
        result.append("hoori_micro_calls_rejected_total{direction=\"")
                .append(direction)
                .append("\"} ")
                .append(stats.rejected)
                .append('\n');
        result.append("hoori_micro_calls_expired_total{direction=\"")
                .append(direction)
                .append("\"} ")
                .append(stats.expired)
                .append('\n');
    }

    static String poolMetrics(HttpClient.PoolStats data, HttpClient.PoolStats control) {
        StringBuilder result = new StringBuilder();
        appendPoolMetrics(result, "data", data);

        if (control != null) appendPoolMetrics(result, "control", control);

        return result.toString();
    }

    private static void appendPoolMetrics(StringBuilder result, String pool, HttpClient.PoolStats stats) {
        result.append("hoori_micro_pool_active_connections{pool=\"")
                .append(pool)
                .append("\"} ")
                .append(stats.activeConnections)
                .append('\n');
        result.append("hoori_micro_pool_idle_connections{pool=\"")
                .append(pool)
                .append("\"} ")
                .append(stats.idleConnections)
                .append('\n');
        result.append("hoori_micro_pool_pending_acquires{pool=\"")
                .append(pool)
                .append("\"} ")
                .append(stats.pendingAcquires)
                .append('\n');
        result.append("hoori_micro_pool_rejected_acquires_total{pool=\"")
                .append(pool)
                .append("\"} ")
                .append(stats.rejectedAcquires)
                .append('\n');
    }

    public ServiceConfig config() {
        return config;
    }

    public JsonLimits jsonLimits() {
        return jsonLimits;
    }

    /** Local application readiness, not an automatic recursive dependency health check. */
    public void ready(boolean value) {
        applicationReady = value;
        HttpServer current = server;

        if (current != null) current.setReady(value);
    }

    public boolean isLive() {
        return server != null && server.isLive();
    }

    public boolean isReady() {
        return server != null && server.isReady();
    }

    /** Stop admission and deregister; run() drains dispatched requests before closing the pool. */
    public synchronized void stop() {
        if (stopRequested) return;

        stopRequested = true;
        HttpServer current = server;

        if (current != null) current.stopAccepting();

        incoming.stop();
        broker.stop();

        Thread heartbeat = registrar;

        if (heartbeat != null) heartbeat.interrupt();
    }

    public void run() throws IOException {
        if (started || closed || stopRequested) throw new IllegalStateException("Service already run or closed");

        started = true;
        try {
            server = new HttpServer(
                    config.bindAddress,
                    config.port,
                    limits(config.serverConnections, config.requestTimeoutMillis),
                    router.freeze());
            server.setReady(applicationReady);
            signalWatcher = new Thread(() -> {
                try {
                    while (!closed && !isLive()) Thread.sleep(1);
                    while (!closed && !stopRequested && !Shutdown.requested()) Thread.sleep(50);

                    if (!closed) stop();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            signalWatcher.setDaemon(true);
            signalWatcher.start();

            // Inactive clients allocate no sockets/reaper; services without discovery need no registrar.
            if (broker.needsDiscovery()) {
                Thread heartbeat = new Thread(() -> {
                    try {
                        while (!closed && !isLive()) Thread.sleep(1);
                        while (!closed && !stopRequested) {
                            broker.beat(isReady());
                            Thread.sleep(broker.heartbeatDelayMillis());
                        }
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    } catch (IOException stopped) {
                        // Only actual SDK cancellation/client close escapes beat().
                    } finally {
                        // One best-effort DELETE after stopping; preserve the cancellation status afterwards.
                        boolean interrupted = Thread.interrupted();
                        try {
                            if (!closed) broker.deregister();
                        } finally {
                            if (interrupted) Thread.currentThread().interrupt();
                        }
                    }
                });
                heartbeat.setDaemon(true);
                registrar = heartbeat;
                heartbeat.start();
            }

            System.out.println("service_starting name="
                    + config.name
                    + " version="
                    + service.version
                    + " instance="
                    + config.instanceId
                    + " port="
                    + config.port);
            server.run();
        } finally {
            try {
                stop();
                finishRegistrar();

                // HttpServer.run returns when admission stops, NOT when handlers have drained.
                // Only the owner thread drains; watcher/heartbeat threads must NOT close the client.
                if (server != null) {
                    boolean drained = server.shutdown(config.shutdownGraceMillis);
                    System.out.println("service_stopped name=" + config.name + " drained=" + drained);
                }
            } finally {
                close();
            }
        }
    }

    private void finishRegistrar() {
        Thread heartbeat = registrar;

        if (heartbeat == null || heartbeat == Thread.currentThread()) return;

        try {
            heartbeat.join(2L * config.controlTimeoutMillis);

            if (heartbeat.isAlive()) {
                control.close();
                heartbeat.interrupt();
                heartbeat.join(config.controlTimeoutMillis);
            }
        } catch (InterruptedException interrupted) {
            control.close();
            heartbeat.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    /** Immediate abort/cleanup. Prefer stop() while run() is active for graceful shutdown. */
    @Override
    public void close() {
        if (closed) return;

        closed = true;
        stopRequested = true;

        if (server != null) server.close();

        incoming.close();
        broker.close();

        http.close();
        control.close();

        if (signalWatcher != null && signalWatcher != Thread.currentThread()) signalWatcher.interrupt();

        Thread heartbeat = registrar;

        if (heartbeat != null && heartbeat != Thread.currentThread()) heartbeat.interrupt();
    }

    /** Only this instance's own name and major version; anything else is a stale-catalog miss. */
    private Response invoke(hoori.rest.Request request) throws Exception {
        // The SDK already read the bounded raw body; DTO decoding starts only after admission.
        try (Admission.Permit permit = incoming.acquire(
                Context.incomingBudget(request.raw().headers, request.raw().budget())
                        .limitedToMillis(config.clientTimeoutMillis),
                false)) {
            Headers headers = request.raw().headers;
            String action = headers.get(ServiceBroker.ACTION_HEADER);
            Service.Definition<?, ?> definition = null;

            if (action != null
                    && action.startsWith(service.name + ".")
                    && Integer.toString(service.version).equals(headers.get(ServiceBroker.VERSION_HEADER)))
                definition = service.actions.get(action.substring(service.name.length() + 1));

            if (definition == null) return Response.text(421, "Action not offered by this instance");

            Context context = new Context(broker, request.raw(), permit.budget);
            request.attribute(contextKey, context);
            permit.check(incoming);
            Response response = definition.invoke(context, request, jsonLimits);
            permit.check(incoming);

            return response;
        }
    }

    private Limits limits(int connections, int timeout) {
        return new Limits(64, 16384, config.bodyBytes, connections, 100, timeout);
    }

    private static Response health(boolean up) {
        return Response.text(up ? 200 : 503, up ? "UP" : "DOWN");
    }
}
