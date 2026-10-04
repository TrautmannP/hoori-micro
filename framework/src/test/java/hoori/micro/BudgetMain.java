package hoori.micro;

import hoori.concurrent.TaskScope;
import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.Limits;
import hoori.http.RequestBudget;
import hoori.http.Response;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import hoori.rest.json.Json;
import hoori.runtime.RuntimeMetrics;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only real guest services and a shared-SDK-pool wire probe. */
public final class BudgetMain {
    private static final AtomicInteger CALLS = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String role = System.getenv("BUDGET_ROLE");

        if (role.equals("pool")) {
            pool(System.getenv("BUDGET_HOLD_PATH"));

            return;
        }

        try (Microservice app = Microservice.create(role)) {
            RemoteClient recipes = role.equals("shopping") ? new RemoteClient(app, "recipes", 1) : null;

            if (role.equals("recipes")) {
                HttpFixture.Body observe = (ctx, input) -> {
                    CALLS.incrementAndGet();
                    long delay = ((Map<?, ?>) input).get("delay") instanceof Long value ? value : 0;

                    if (delay < 0 || delay > 1500) throw new RequestException(400, "Delay bound");

                    Thread.sleep(delay);
                    String wire = ctx.ownerRequest().headers.get(Context.BUDGET_HEADER);

                    return Map.of(
                            "wire",
                            wire == null ? 0L : Long.parseLong(wire),
                            "remaining",
                            ctx.budget().remainingNanos() / 1_000_000L,
                            "requestId",
                            ctx.invocation().requestId(),
                            "authorization",
                            ctx.ownerRequest().headers.get("Authorization") != null,
                            "body",
                            input);
                };
                HttpFixture.post(app, "/observe", null, JsonTree.CODEC, observe);
                HttpFixture.post(app, "/observe/{id}", "recipes:read", JsonTree.CODEC, observe);
            }

            if (role.equals("shopping")) {
                HttpFixture.post(app, "/chain", "shopping:read", JsonTree.CODEC, (ctx, input) -> serial(recipes));
                HttpFixture.post(
                        app,
                        "/expired",
                        null,
                        JsonTree.CODEC,
                        (ctx, input) -> TaskScope.named("shorter")
                                .within(Duration.ofMillis(50))
                                .call(scope -> {
                                    Thread.sleep(100);

                                    return HttpFixture.call(recipes, "/observe", Map.of());
                                }));
            }

            if (role.equals("gateway"))
                Gateway.mount(
                        app,
                        (request, permission) ->
                                permission.equals("recipes:read") || permission.equals("shopping:read"));

            if (role.equals("shopping")) {
                app.routes()
                        .post(
                                "/root",
                                request -> Responses.json(
                                        200,
                                        TaskScope.named("shorter")
                                                .within(Duration.ofMillis(1000))
                                                .call(scope -> serial(recipes)),
                                        JsonTree.CODEC,
                                        app.jsonLimits()));
                app.routes().post("/cancel", request -> {
                    Throwable[] failure = new Throwable[1];
                    boolean[] interrupted = new boolean[1];
                    Thread child = new Thread(() -> {
                        try {
                            app.runTask(() -> HttpFixture.call(recipes, "/observe", Map.of("delay", 1500)));
                        } catch (Throwable error) {
                            failure[0] = error;
                            interrupted[0] = Thread.currentThread().isInterrupted();
                        }
                    });
                    child.start();
                    long deadline = System.nanoTime() + 2000000000L;
                    while (app.dataPoolStats().activeConnections != 1 && System.nanoTime() - deadline < 0)
                        Thread.sleep(1);

                    if (app.dataPoolStats().activeConnections != 1) throw new AssertionError("Missing active child");

                    child.interrupt();
                    child.join(3000);

                    if (child.isAlive()
                            || Failures.classify(failure[0]).kind() != Failures.Kind.INTERRUPTED
                            || !interrupted[0]) throw new AssertionError("Cancellation contract");

                    if (app.broker().admissionStats().active != 0 || app.dataPoolStats().pendingAcquires != 0)
                        throw new AssertionError("Cancelled child retained resources");

                    return Response.text(200, "cancelled");
                });
            }

            app.controlRoute("GET", "/probe", request -> {
                ServiceBroker.View view = app.broker().snapshot();
                ArrayList<String> routes = new ArrayList<>();
                for (Gateway.Route route : view.routes) routes.add(route.path);
                RuntimeMetrics.Snapshot runtime = RuntimeMetrics.snapshot();
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("calls", CALLS.get());
                values.put("revision", view.catalog.revision);
                values.put("routes", routes);
                values.put("incoming_active", app.incomingStats().active);
                values.put("incoming_pending", app.incomingStats().pending);
                values.put("outgoing_active", app.broker().admissionStats().active);
                values.put("outgoing_pending", app.broker().admissionStats().pending);
                values.put("pool_active", app.dataPoolStats().activeConnections);
                values.put("pool_pending", app.dataPoolStats().pendingAcquires);
                values.put("heap_used", runtime.heapUsedBytes);
                values.put("rss", runtime.rssBytes);
                values.put("tasks_active", runtime.tasksActive);
                values.put("handles", runtime.serviceHandlesOpen);

                return Responses.json(200, values, JsonTree.CODEC, app.jsonLimits());
            });
            app.run();
            for (Admission.Stats stats :
                    new Admission.Stats[] {app.incomingStats(), app.broker().admissionStats()})
                if (stats.active != 0 || stats.pending != 0) throw new AssertionError("Admission retained work");
            for (HttpClient.PoolStats stats : new HttpClient.PoolStats[] {app.dataPoolStats(), app.controlPoolStats()})
                if (stats.activeConnections != 0 || stats.idleConnections != 0 || stats.pendingAcquires != 0)
                    throw new AssertionError("Pool retained resources");
            System.out.println("budget_closed=true pools_closed=true");
        }
    }

    private static Object serial(RemoteClient recipes) throws Exception {
        Object first = HttpFixture.call(recipes, "/observe", Map.of("delay", 100));
        Thread.sleep(200);
        Object second = HttpFixture.call(recipes, "/observe", Map.of("delay", 100));

        return Map.of("first", first, "second", second);
    }

    private static void pool(String holdPath) throws Exception {
        String peer = System.getenv("BUDGET_PEER");
        Throwable[] failure = new Throwable[1];
        HttpClient client = new HttpClient(new Limits(64, 16384, 65536, 1, 100, 5000).withPendingAcquires(1), 1, 5000);
        try (client) {
            client.exchange(URI.create(peer + "/warm"), "GET", new Headers(), new byte[0]);
            Thread hold = new Thread(() -> {
                try {
                    client.exchange(URI.create(peer + holdPath), "GET", new Headers(), new byte[0]);
                } catch (Throwable error) {
                    failure[0] = error;
                }
            });
            hold.start();
            long deadline = System.nanoTime() + 3000000000L;
            while (client.poolStats().activeConnections != 1 && System.nanoTime() - deadline < 0) Thread.sleep(1);

            if (client.poolStats().activeConnections != 1) throw new AssertionError("Missing shared pool contention");

            RequestBudget budget = RequestBudget.afterMillis(2000).withRemainingMillisHeader(Context.BUDGET_HEADER);
            Response result = client.exchange(URI.create(peer + "/observe"), "GET", new Headers(), new byte[0], budget);
            hold.join(3000);

            if (hold.isAlive() || failure[0] != null || result.status != 200)
                throw new AssertionError("Pool probe failed");

            System.out.println(
                    "pool_wire=" + Json.decodeUtf8(result.body) + " remaining_ms=" + budget.remainingMillis());
        }

        if (client.poolStats().activeConnections != 0 || client.poolStats().pendingAcquires != 0)
            throw new AssertionError("Pool probe retained work");

        System.out.println("pool_closed=true");
    }
}
