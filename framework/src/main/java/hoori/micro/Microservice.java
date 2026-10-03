package hoori.micro;

import hoori.concurrent.TaskDiagnostics;
import hoori.concurrent.TaskSpec;
import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.HttpServer;
import hoori.http.Limits;
import hoori.http.RequestBudget;
import hoori.http.Response;
import hoori.rest.Handler;
import hoori.rest.Router;
import hoori.rest.json.JsonLimits;
import hoori.runtime.RuntimeMetrics;
import hoori.runtime.Shutdown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Explicit startup composition on Hoori's cooperative single carrier. run() owns registration,
 * draining and pool cleanup. Configure routes/actions before run(), never in handlers.
 */
public final class Microservice implements AutoCloseable {
    private final Service service;
    private final ServiceConfig config;
    private final Router router = new Router();
    private final HttpClient http, control;
    private final ServiceBroker broker;
    private final Admission incoming;
    private final JsonLimits jsonLimits;
    private final Context context;
    private final List<String> controlRoutes = new ArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();

    private volatile HttpServer server;
    private volatile boolean closed, stopRequested, applicationReady = true;

    private boolean started;
    private Thread signalWatcher;
    private volatile Thread registrar;
    private Thread owner;
    private boolean closing;
    private long graceDeadline;

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
                service,
                config,
                ServiceBroker.dataTransport(http),
                ServiceBroker.controlTransport(control),
                jsonLimits);
        context = new Context(broker);
        // The Router selects trusted declarations first and maps errors after this managed boundary.
        router.use((request, next) -> {
            if (controlRoutes.contains(request.method() + " " + request.routeTemplate())) return next.handle(request);

            RequestBudget budget = request.raw().budget().limitedToMillis(config.workTimeoutMillis);

            if (request.method().equals("POST")
                    && request.routeTemplate().equals(ServiceBroker.INVOKE_PATH)
                    && !service.actions.isEmpty()) budget = Context.incomingBudget(request.raw().headers, budget);

            return broker.executions.request(request.raw(), budget, incoming, scope -> {
                Response response = next.handle(request);

                if (response == null) throw new IllegalStateException("Null controller response");

                return response;
            });
        });

        // One fixed internal entry point; new actions never need new routes. Absent without actions.
        if (!service.actions.isEmpty()) router.post(ServiceBroker.INVOKE_PATH, this::invoke);

        controlRoute("GET", "/health/live", request -> health(isLive()));
        controlRoute("GET", "/health/ready", request -> health(isReady()));
        controlRoute(
                "GET",
                "/metrics",
                request -> new Response(
                        200,
                        new Headers().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8"),
                        (server.metrics().prometheus()
                                        + poolMetrics(http.poolStats(), control.poolStats())
                                        + admissionMetrics(incoming.stats(), broker.admissionStats())
                                        + broker.executions.metrics()
                                        + runtimeMetrics())
                                .getBytes(StandardCharsets.UTF_8)));
        router.onError((request, failure) -> {
            // Intentionally no stacktrace, URI, request/response body or secret in logs/errors.
            String id = request == null ? "unavailable" : request.id();
            Failures.Result result = Failures.classify(failure);
            System.err.println("request_failed service="
                    + config.name
                    + " request_id="
                    + id
                    + " reason=" + result.kind().label + " transaction=" + result.transaction());

            return result.response();
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

    /** Reusable facade; calls require a current request or explicit service-owned runTask. */
    public Context context() {
        return context;
    }

    /** Synchronous startup/maintenance operation, owned and drained by this service. */
    public <T> T runTask(TaskSpec<T> work) throws Exception {
        if (work == null) throw new NullPointerException("work");

        if (broker.executions.currentOwner()) throw new IllegalStateException("runTask requires a service owner");

        return broker.executions.service(scope -> work.run());
    }

    /** Optional service resources (for example a DB pool); register at startup, close after root drain. */
    public synchronized <T extends AutoCloseable> T own(T resource) {
        if (resource == null) throw new NullPointerException("resource");

        if (started || stopRequested || resources.size() == 16)
            throw new IllegalStateException("Service resources frozen or full");

        resources.add(resource);

        return resource;
    }

    /** On-demand local diagnostics: at most 8 roots, 32 entries per root, depth 4; no public endpoint. */
    public List<TaskDiagnostics.Snapshot> taskDiagnostics() {
        return broker.executions.diagnostics();
    }

    // Only fixed framework/control declarations can bypass business admission, never request headers/paths.
    void controlRoute(String method, String path, Handler handler) {
        router.route(method, path, handler);
        controlRoutes.add(method + " " + path);
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
        applicationReady = false;
        graceDeadline = System.nanoTime() + config.shutdownGraceMillis * 1_000_000L;
        broker.executions.stop();
        HttpServer current = server;

        if (current != null) current.stopAccepting();

        incoming.stop();
        broker.stop();

        Thread heartbeat = registrar;

        if (heartbeat != null) heartbeat.interrupt();
    }

    public void run() throws IOException {
        synchronized (this) {
            if (started || closed || stopRequested || broker.executions.currentOwner())
                throw new IllegalStateException("Service already run, closed or called from its own work");

            started = true;
            owner = Thread.currentThread();
        }
        try {
            server = new HttpServer(
                    config.bindAddress,
                    config.port,
                    limits(config.serverConnections, config.requestTimeoutMillis),
                    router.freeze());

            // A stop before listener publication must not be lost by idempotent stop().
            if (stopRequested) return;

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
            try {
                server.run();
            } catch (IllegalStateException failed) {
                // stopAccepting may win between publication and the SDK's one-time start.
                if (!stopRequested || server.isLive()) throw failed;
            }
        } finally {
            close();
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

    /** Separate service owner waits for actual completion. Handlers request stop() instead. */
    @Override
    public void close() throws IOException {
        if (broker.executions.currentOwner()) throw new IllegalStateException("An active operation must use stop()");

        stop();
        boolean wait;
        synchronized (this) {
            if (closed) return;

            wait = closing || owner != null && owner != Thread.currentThread();

            if (!wait) closing = true;
        }

        if (wait) {
            boolean interrupted = Thread.interrupted();
            while (!closed) {
                LockSupport.parkNanos(1_000_000);
                interrupted |= Thread.interrupted();
            }

            if (interrupted) Thread.currentThread().interrupt();

            return;
        }

        try {
            broker.executions.awaitUntil(graceDeadline);
            broker.executions.close(); // Cancellation is followed by actual children/resources/context/timer drain.

            if (server != null) {
                int remaining = (int) Math.max(0, (graceDeadline - System.nanoTime()) / 1_000_000L);
                boolean drained = server.shutdown(remaining); // Response I/O owns only the remainder of the same grace.
                System.out.println("service_stopped name=" + config.name + " roots_drained=true drained=" + drained);
            }

            finishRegistrar();
        } finally {
            incoming.close();
            broker.close();
            http.close();
            control.close();
            IOException failure = null;
            for (int i = resources.size() - 1; i >= 0; i--) {
                try {
                    resources.get(i).close();
                } catch (Exception error) {
                    if (failure == null) failure = new IOException("Service resource close failed", error);
                    else failure.addSuppressed(error);
                }
            }
            resources.clear();
            closed = true;

            if (server != null) server.close();

            if (signalWatcher != null && signalWatcher != Thread.currentThread()) signalWatcher.interrupt();

            Thread heartbeat = registrar;

            if (heartbeat != null && heartbeat != Thread.currentThread()) heartbeat.interrupt();

            if (failure != null) throw failure;
        }
    }

    /** Only this instance's own name and major version; anything else is a stale-catalog miss. */
    private Response invoke(hoori.rest.Request request) throws Exception {
        // Root middleware owns the one incoming permit through DTO work and complete managed drain.
        Headers headers = request.raw().headers;
        String action = headers.get(ServiceBroker.ACTION_HEADER);
        Service.Definition<?, ?> definition = null;

        if (action != null
                && action.startsWith(service.name + ".")
                && Integer.toString(service.version).equals(headers.get(ServiceBroker.VERSION_HEADER)))
            definition = service.actions.get(action.substring(service.name.length() + 1));

        if (definition == null) return Response.text(421, "Action not offered by this instance");

        context.check();
        Response response = definition.invoke(context, request, jsonLimits);
        context.check();

        return response;
    }

    private Limits limits(int connections, int timeout) {
        return new Limits(64, 16384, config.bodyBytes, connections, 100, timeout);
    }

    private static Response health(boolean up) {
        return Response.text(up ? 200 : 503, up ? "UP" : "DOWN");
    }
}
