package hoori.micro;

import hoori.concurrent.Tasks;
import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.HttpServer;
import hoori.http.Limits;
import hoori.http.Response;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import hoori.rest.Router;
import hoori.rest.json.Json;
import hoori.rest.json.JsonLimits;
import hoori.runtime.RuntimeMetrics;
import hoori.runtime.Shutdown;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only controls. Static addresses/snapshots never become an application API. */
public final class BenchmarkMain {
    private static final Action<Object, Object> ECHO = new Action<>("recipes.echo", JsonTree.CODEC, JsonTree.CODEC);
    private static final Action<Object, Object> FAIL = new Action<>("recipes.fail", JsonTree.CODEC, JsonTree.CODEC);
    private static final JsonLimits JSON = new JsonLimits(64, 16384, 128, 65536);
    private static final String ROLE = System.getenv("BENCH_ROLE");
    private static final String VARIANT = System.getenv("BENCH_VARIANT");
    private static final String COMPOSITION = System.getenv("BENCH_COMPOSITION");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("lookup")) {
            lookup();

            return;
        }

        boolean fixed = VARIANT.equals("A") || VARIANT.equals("B");

        if (fixed && (ROLE.equals("recipes") || ROLE.equals("shopping"))) {
            fixed();

            return;
        }

        Service service = Service.named(ROLE);
        boolean composition = COMPOSITION != null && !COMPOSITION.equals("single");

        if (ROLE.equals("recipes"))
            service.action("echo", JsonTree.CODEC, JsonTree.CODEC, (ctx, input) -> echo(input))
                    .http("POST", "/bench")
                    .requirePermission("recipes:read");

        if (ROLE.equals("recipes") && composition)
            service.action(FAIL, (ctx, input) -> {
                Thread.sleep(50);
                throw new RequestException(409, "Controlled benchmark failure");
            });

        if (ROLE.equals("shopping"))
            service.dependsOn("recipes", 1)
                    .action(
                            "echo",
                            JsonTree.CODEC,
                            JsonTree.CODEC,
                            composition ? BenchmarkMain::compose : (ctx, input) -> ctx.call(ECHO, input))
                    .http("POST", "/bench/meal")
                    .requirePermission("shopping:read");

        try (Microservice app = Microservice.create(service)) {
            if (ROLE.equals("registry")) new Registry(app.config().registryTtlMillis, app.jsonLimits()).mount(app);

            if (ROLE.equals("gateway") && VARIANT.equals("D"))
                Gateway.mount(app, (request, permission) -> permission.equals("shopping:read"));

            if (ROLE.equals("shopping") && !composition)
                app.routes()
                        .post(
                                "/bench",
                                request -> Responses.json(
                                        200,
                                        app.context().call(ECHO, request.body(JsonTree.CODEC, JSON)),
                                        JsonTree.CODEC,
                                        JSON));

            if (ROLE.equals("shopping") && composition)
                app.routes()
                        .post(
                                "/bench",
                                request -> Responses.json(
                                        200,
                                        compose(app.context(), request.body(JsonTree.CODEC, JSON)),
                                        JsonTree.CODEC,
                                        JSON));

            app.controlRoute(
                    "GET",
                    "/bench/runtime",
                    request -> runtime(app.broker(), app.dataPoolStats(), app.controlPoolStats(), app.incomingStats()));
            app.run();
            for (HttpClient.PoolStats stats :
                    new HttpClient.PoolStats[] {app.dataPoolStats(), app.controlPoolStats()}) {
                if (stats != null
                        && (stats.activeConnections != 0 || stats.idleConnections != 0 || stats.pendingAcquires != 0))
                    throw new AssertionError("Pool retained resources after shutdown");
            }
            System.out.println("pools_closed=true");
            for (Admission.Stats stats :
                    new Admission.Stats[] {app.incomingStats(), app.broker().admissionStats()}) {
                if (stats != null && (stats.active != 0 || stats.pending != 0))
                    throw new AssertionError("Admission retained work after shutdown");
            }
            System.out.println("admission_closed=true");
        }
    }

    private static Object compose(Context ctx, Object input) throws Exception {
        Action<Object, Object> first =
                input instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("failure")) ? FAIL : ECHO;

        if (COMPOSITION.equals("serial")) return same(ctx.call(first, input), ctx.call(ECHO, input));

        var plan = Tasks.parallel(ctx.task(first, input), ctx.task(ECHO, input)).named("benchmark.composition");

        if (COMPOSITION.equals("fail-fast")) plan.failFast();

        return plan.map(BenchmarkMain::same);
    }

    private static Object same(Object first, Object second) {
        if (!first.equals(second)) throw new AssertionError("Different composed business results");

        return first;
    }

    /** Native CPU control, separate from transport timing. No speculative index implementation. */
    private static void lookup() {
        Catalog.Entry[] actions = {new Catalog.Entry("echo", null, null, null)};
        Catalog.Instance[] all = new Catalog.Instance[35];
        all[0] = new Catalog.Instance("recipes", "recipes", 1, "http://recipes:8080", actions);
        for (int i = 1; i < all.length; i++)
            all[i] = new Catalog.Instance("extra-" + i, "extra-" + i, 1, "http://extra:8080", actions);
        Catalog[] catalogs = {
            new Catalog("lookup", 1, true, new Catalog.Instance[] {all[0]}), new Catalog("lookup", 1, true, all)
        };
        int checksum = 0;
        for (int round = 0; round < 6; round++) {
            for (Catalog catalog : catalogs) {
                long start = System.nanoTime();
                for (int i = 0; i < 100_000; i++) {
                    Catalog.Instance selected = catalog.select("recipes", 1, (i & 1) == 0 ? "echo" : "absent", i);

                    if (selected != null) checksum++;
                }
                long elapsed = System.nanoTime() - start;

                if (round != 0)
                    System.out.println(
                            "lookup instances=" + catalog.instances.length + " calls=100000 elapsed_ns=" + elapsed);
            }
        }

        if (checksum != 600_000) throw new AssertionError("Wrong selection");
    }

    private static Object echo(Object input) throws InterruptedException {
        if (!(input instanceof Map<?, ?> map) || !(map.get("value") instanceof String value) || value.length() > 8192)
            throw new RequestException(400, "Expected bounded echo input");

        Object delay = map.get("delayMillis");

        if (delay != null && (!(delay instanceof Long) || (Long) delay < 0 || (Long) delay > 1000))
            throw new RequestException(400, "Expected bounded delay");

        if (delay != null && (Long) delay > 0) Thread.sleep((Long) delay);

        return input;
    }

    /** Same HTTP/JSON limits and request context; B adds the real broker and action dispatcher. */
    private static void fixed() throws Exception {
        Service service = Service.named(ROLE);

        if (ROLE.equals("recipes")) service.action("echo", JsonTree.CODEC, JsonTree.CODEC, (ctx, input) -> echo(input));
        else service.dependsOn("recipes", 1);

        service.freeze();
        ServiceConfig config = ServiceConfig.from(ROLE, Environment.system());
        Limits clientLimits = new Limits(
                        64, 16384, config.bodyBytes, config.clientConnections, 100, config.clientTimeoutMillis)
                .withPendingAcquires(VARIANT.equals("A") ? config.outgoingPendingCalls : config.clientPendingAcquires);
        try (HttpClient client = new HttpClient(clientLimits, config.clientPerOrigin, config.clientIdleMillis)) {
            ServiceBroker broker = VARIANT.equals("B")
                    ? new ServiceBroker(service, config, ServiceBroker.dataTransport(client), JSON)
                    : null;

            if (broker != null) {
                Catalog.Entry[] actions = {new Catalog.Entry("echo", null, null, null)};
                int extras = Integer.parseInt(System.getenv("BENCH_CATALOG_INSTANCES"));
                Catalog.Instance[] instances = new Catalog.Instance[extras + 1];
                instances[0] = new Catalog.Instance("recipes-fixed", "recipes", 1, "http://recipes:8080", actions);
                for (int i = 1; i < instances.length; i++)
                    instances[i] = new Catalog.Instance("extra-" + i, "extra-" + i, 1, "http://recipes:8080", actions);
                broker.accept(Json.encode(new Catalog("fixed", 1, true, instances), Catalog.CODEC, JSON));
            }

            URI direct = URI.create("http://recipes:8080/direct");
            Router router = new Router();
            Admission incoming = new Admission(config.incomingCalls, config.incomingPendingCalls);

            if (broker != null)
                router.use((request, next) -> {
                    if (request.routeTemplate().equals("/metrics")
                            || request.routeTemplate().equals("/health/ready")
                            || request.routeTemplate().equals("/bench/runtime")) return next.handle(request);

                    return broker.executions.request(
                            request.raw(),
                            Context.incomingBudget(
                                    request.raw().headers,
                                    request.raw().budget().limitedToMillis(config.workTimeoutMillis)),
                            incoming,
                            scope -> next.handle(request));
                });

            HttpServer[] owner = new HttpServer[1];
            router.get(
                    "/metrics",
                    request -> Response.text(
                            200,
                            owner[0].metrics().prometheus()
                                    + Microservice.poolMetrics(client.poolStats(), null)
                                    + Microservice.admissionMetrics(
                                            null, broker == null ? null : broker.admissionStats())));
            router.get("/health/ready", request -> Response.text(200, "UP"));
            router.get("/bench/runtime", request -> runtime(broker, client.poolStats(), null, null));

            if (ROLE.equals("recipes")) {
                router.post(
                        "/direct",
                        request -> Responses.json(200, echo(request.body(JsonTree.CODEC, JSON)), JsonTree.CODEC, JSON));
                router.post(ServiceBroker.INVOKE_PATH, request -> {
                    Headers headers = request.raw().headers;

                    if (!ECHO.name.equals(headers.get(ServiceBroker.ACTION_HEADER))
                            || !"1".equals(headers.get(ServiceBroker.VERSION_HEADER)))
                        return Response.text(421, "Action not offered by this instance");

                    return service.actions.get("echo").invoke(new Context(broker), request, JSON);
                });
            } else {
                router.post("/bench", request -> {
                    Object input = request.body(JsonTree.CODEC, JSON);
                    Object result;

                    if (VARIANT.equals("A")) {
                        Response response = client.exchange(
                                request.raw(),
                                direct,
                                "POST",
                                new Headers().add("Accept", "application/json").add("Content-Type", "application/json"),
                                Json.encode(input, JsonTree.CODEC, JSON));

                        if (response.status != 200) throw new RequestException(502, "Upstream service unavailable");

                        result = Json.decode(response.body, JsonTree.CODEC, JSON);
                    } else result = new Context(broker).call(ECHO, input);

                    return Responses.json(200, result, JsonTree.CODEC, JSON);
                });
            }

            router.onError((request, failure) -> {
                System.err.println(
                        "benchmark_request_failed type=" + failure.getClass().getName());

                return Response.text(503, "Benchmark request failed");
            });
            Limits serverLimits =
                    new Limits(64, 16384, config.bodyBytes, config.serverConnections, 100, config.requestTimeoutMillis);
            try (HttpServer server = new HttpServer(config.bindAddress, config.port, serverLimits, router.freeze())) {
                owner[0] = server;
                Thread watcher = new Thread(() -> {
                    try {
                        while (!Shutdown.requested()) Thread.sleep(50);
                        server.stopAccepting();

                        if (broker != null) broker.stop();
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                });
                watcher.setDaemon(true);
                watcher.start();
                try {
                    server.run();
                } finally {
                    if (broker != null) {
                        broker.executions.awaitUntil(System.nanoTime() + config.shutdownGraceMillis * 1_000_000L);
                        broker.executions.close();
                    }

                    server.shutdown(config.shutdownGraceMillis);

                    if (broker != null) broker.close();

                    watcher.interrupt();
                }
            }
        }
    }

    private static Response runtime(
            ServiceBroker broker, HttpClient.PoolStats data, HttpClient.PoolStats control, Admission.Stats incoming) {
        RuntimeMetrics.Snapshot s = RuntimeMetrics.snapshot();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("heap_used_bytes", s.heapUsedBytes);
        values.put("heap_committed_bytes", s.heapCommittedBytes);
        values.put("heap_peak_bytes", s.heapPeakBytes);
        values.put("allocated_bytes", s.allocatedBytes);
        values.put("object_allocations", s.objectAllocations);
        values.put("array_allocations", s.arrayAllocations);
        values.put("rss_bytes", s.rssBytes);
        values.put("gc_collections", s.gcCollections);
        values.put("gc_pause_ns", s.gcPauseNanos);
        values.put("tasks_active", s.tasksActive);
        values.put("tasks_waiting", s.tasksWaiting);
        values.put("service_handles_open", s.serviceHandlesOpen);
        values.put("service_bytes_read", s.serviceBytesRead);
        values.put("service_bytes_written", s.serviceBytesWritten);
        Catalog catalog = broker == null ? Catalog.EMPTY : broker.catalog();
        int actions = 0;
        for (Catalog.Instance instance : catalog.instances) actions += instance.actions.length;
        values.put("catalog_instances", catalog.instances.length);
        values.put("catalog_actions", actions);
        values.put(
                "http_pool_active_connections",
                data.activeConnections + (control == null ? 0 : control.activeConnections));
        values.put(
                "http_pool_idle_connections", data.idleConnections + (control == null ? 0 : control.idleConnections));
        values.put(
                "http_pool_pending_acquires", data.pendingAcquires + (control == null ? 0 : control.pendingAcquires));
        values.put(
                "http_pool_rejected_acquires",
                data.rejectedAcquires + (control == null ? 0 : control.rejectedAcquires));
        poolValues(values, "data", data);

        if (control != null) poolValues(values, "control", control);

        callValues(values, "incoming", incoming);
        callValues(values, "outgoing", broker == null ? null : broker.admissionStats());

        return Responses.json(200, values, JsonTree.CODEC, JSON);
    }

    private static void poolValues(Map<String, Object> values, String pool, HttpClient.PoolStats stats) {
        values.put(pool + "_pool_active_connections", stats.activeConnections);
        values.put(pool + "_pool_idle_connections", stats.idleConnections);
        values.put(pool + "_pool_pending_acquires", stats.pendingAcquires);
        values.put(pool + "_pool_rejected_acquires", stats.rejectedAcquires);
    }

    private static void callValues(Map<String, Object> values, String direction, Admission.Stats stats) {
        values.put(direction + "_calls_active", stats == null ? null : stats.active);
        values.put(direction + "_calls_pending", stats == null ? null : stats.pending);
        values.put(direction + "_calls_rejected", stats == null ? null : stats.rejected);
        values.put(direction + "_calls_expired", stats == null ? null : stats.expired);
    }
}
