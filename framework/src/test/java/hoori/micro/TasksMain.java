package hoori.micro;

import hoori.concurrent.Cancellation;
import hoori.concurrent.TaskDiagnostics;
import hoori.concurrent.TaskScope;
import hoori.concurrent.TaskSpec;
import hoori.concurrent.Tasks;
import hoori.http.Headers;
import hoori.http.HttpClient;
import hoori.http.Limits;
import hoori.http.Response;
import hoori.rest.RequestException;
import hoori.rest.Responses;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import hoori.runtime.RuntimeMetrics;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Controlled native Micro interactions, including test-only control routes and cleanup gates. */
public final class TasksMain {
    private static final AtomicInteger ENCODED = new AtomicInteger();
    private static final JsonCodec<Object> INPUT = new JsonCodec<>() {
        public Object read(JsonReader reader) {
            return JsonTree.CODEC.read(reader);
        }

        public void write(Object value, JsonWriter writer) {
            ENCODED.incrementAndGet();
            JsonTree.CODEC.write(value, writer);
        }
    };
    private static final Action<Object, Object> ECHO = new Action<>("peer.echo", INPUT, JsonTree.CODEC);
    private static final List<WeakReference<Object>> REQUESTS = new ArrayList<>();
    private static volatile Cancellation cancellable;
    private static volatile boolean bodyReturned, childFinished, resourceClosed, responseBuilt;

    public static void main(String[] args) throws Exception {
        if (System.getenv("TASK_START_RACE") != null) {
            for (int i = 0; i < 32; i++) {
                AtomicInteger closed = new AtomicInteger();
                try (Microservice app = Microservice.create(Service.named("startup"))) {
                    app.own(() -> closed.incrementAndGet());
                    Throwable[] failed = new Throwable[1];
                    Thread owner = new Thread(() -> {
                        try {
                            app.run();
                        } catch (IllegalStateException stoppedBeforeRun) {
                            // A never-started, already stopped application rejects run().
                        } catch (Throwable failure) {
                            failed[0] = failure;
                        }
                    });
                    owner.start();
                    for (int turn = 0; turn < i % 4; turn++) Thread.yield();
                    app.stop();
                    app.close();
                    owner.join(2000);

                    if (owner.isAlive() || failed[0] != null || closed.get() != 1)
                        throw new AssertionError("Startup/stop retained work or closed resources twice");
                }
            }
            System.out.println("startup_stop_races=32");

            return;
        }

        Service service = Service.named("tasks")
                .dependsOn("peer", 1)
                .dependsOn("other", 1)
                .action("echo", JsonTree.CODEC, JsonTree.CODEC, (ctx, input) -> ctx.call(ECHO, input));
        try (Microservice app = Microservice.create(service)) {
            HttpClient cleanup = app.own(new HttpClient(new Limits(32, 4096, 65536, 1, 100, 15000), 1, 100));
            URI peer = URI.create(System.getenv("TASK_PEER"));
            app.own(() -> signal(cleanup, peer, "/closed"));
            Context ctx = app.context();
            TaskSpec<Object> deferred = ctx.task(ECHO, Map.of("deferred", true));

            if (ENCODED.get() != 0) throw new AssertionError("Task construction encoded input");

            try {
                deferred.run();
                throw new AssertionError("Call without boundary succeeded");
            } catch (IllegalStateException expected) {
            }

            if (ENCODED.get() != 0) throw new AssertionError("Missing context encoded input");

            app.routes().post("/metadata", request -> {
                synchronized (REQUESTS) {
                    if (REQUESTS.size() == 256) REQUESTS.clear();

                    REQUESTS.add(new WeakReference<>(request.raw()));
                    REQUESTS.add(new WeakReference<>(request.raw().body));
                }
                String id = ctx.invocation().requestId();

                if (ctx.ownerRequest() != request.raw()) throw new AssertionError("Wrong owner request");

                Object result = Tasks.parallel(deferred, Tasks.task(() -> {
                            try {
                                ctx.ownerRequest();
                                throw new AssertionError("Raw HTTP request inherited by child");
                            } catch (IllegalStateException expected) {
                            }

                            if (!ctx.invocation().requestId().equals(id))
                                throw new AssertionError("Wrong child identity");

                            return ctx.invocation().requestId();
                        }))
                        .map((remote, childId) -> Map.of("remote", remote, "child", childId, "owner", id));

                if (!ctx.invocation().requestId().equals(id) || ctx.ownerRequest() != request.raw())
                    throw new AssertionError("Nested context was not restored");

                return json(app, result);
            });
            app.routes()
                    .get(
                            "/call/{action}",
                            request -> json(app, ctx.call("peer." + request.pathParam("action"), Map.of())));
            app.routes().get("/other", request -> json(app, ctx.call("other.echo", Map.of())));
            app.routes()
                    .get(
                            "/cancel/{action}",
                            request -> json(
                                    app,
                                    TaskScope.named("cancel-probe")
                                            .cancellable()
                                            .call(scope -> {
                                                cancellable = scope.cancellation();
                                                try {
                                                    return ctx.call("peer." + request.pathParam("action"), Map.of());
                                                } finally {
                                                    cancellable = null;
                                                }
                                            })));
            app.routes()
                    .get(
                            "/deadline",
                            request -> json(
                                    app,
                                    TaskScope.named("short-budget")
                                            .within(Duration.ofMillis(100))
                                            .call(scope -> ctx.call("peer.hold", Map.of()))));
            app.routes()
                    .get(
                            "/parallel",
                            request -> json(
                                    app,
                                    Tasks.parallel(ctx.task("peer.hold", Map.of()), ctx.task("other.hold", Map.of()))
                                            .map((a, b) -> List.of(a, b))));
            app.routes().get("/fail-fast", request -> {
                Tasks.parallel(
                                Tasks.task(() -> {
                                    try {
                                        return ctx.call("peer.hold", Map.of());
                                    } finally {
                                        signal(cleanup, peer, "/cleanup");
                                        childFinished = true;
                                    }
                                }),
                                Tasks.task(() -> {
                                    while (app.dataPoolStats().activeConnections == 0) Thread.sleep(1);
                                    throw new RequestException(422, "Expected business failure");
                                }))
                        .failFast()
                        .run();
                throw new AssertionError("Fail-fast returned success");
            });
            app.routes().get("/settled", request -> {
                var result = Tasks.parallel(ctx.task("peer.fail", Map.of()), Tasks.task(List::of))
                        .settled();

                if (result.first().isSuccess() || !result.second().isSuccess())
                    throw new AssertionError("Settled outcome");

                return json(
                        app,
                        Map.of("remote", "unavailable", "empty", result.second().value()));
            });
            app.routes()
                    .get(
                            "/settled-deadline",
                            request -> json(
                                    app,
                                    Tasks.parallel(ctx.task("peer.hold", Map.of()), Tasks.task(List::of))
                                            .within(Duration.ofMillis(100))
                                            .settled()));
            app.routes()
                    .get(
                            "/batch",
                            request -> json(
                                    app,
                                    Tasks.map(
                                                    List.of(0, 1, 2, 3, 4, 5, 6),
                                                    i -> ctx.call("peer.batch", Map.of("index", i)))
                                            .maxConcurrency(2)
                                            .toList()));
            app.routes().get("/drain", request -> {
                bodyReturned = childFinished = resourceClosed = false;

                return TaskScope.named("drain-probe").call(scope -> {
                    scope.own(() -> resourceClosed = true);
                    scope.fork(() -> {
                        try {
                            return 1;
                        } finally {
                            signal(cleanup, peer, "/cleanup");
                            childFinished = true;
                        }
                    });
                    bodyReturned = true;

                    return Response.text(200, "drained");
                });
            });
            app.routes().get("/error/{kind}", request -> {
                String kind = request.pathParam("kind");

                return TaskScope.named("error-probe").call(scope -> {
                    if (kind.equals("body")) throw new RequestException(409, "Expected business failure");

                    if (kind.equals("child"))
                        scope.fork(() -> {
                            throw new RequestException(409, "Expected business failure");
                        });

                    if (kind.equals("cleanup"))
                        scope.own(() -> {
                            throw new IllegalStateException("SECRET cleanup detail");
                        });

                    if (kind.equals("encoder")) return json(app, new Object());

                    return Response.text(200, "provisional");
                });
            });
            app.routes().get("/shutdown", request -> {
                return TaskScope.named("shutdown-fanout").call(scope -> {
                    scope.own(() -> {
                        resourceClosed = true;
                        signal(cleanup, peer, "/resource");
                    });
                    scope.fork(() -> {
                        try {
                            return ctx.call("peer.hold", Map.of());
                        } finally {
                            signal(cleanup, peer, "/cleanup");
                            childFinished = true;
                        }
                    });
                    scope.fork(() -> ctx.call("other.hold", Map.of()));
                    bodyReturned = true;

                    return Response.text(200, "provisional");
                });
            });
            app.routes().get("/stop", request -> {
                app.stop();
                app.stop();

                return json(app, ctx.call(ECHO, Map.of()));
            });
            app.routes().get("/capacity", request -> {
                TaskScope.named("bounded-children").run(scope -> {
                    for (int i = 0; i < 65; i++) scope.fork(() -> 1);
                });
                throw new AssertionError("Child capacity was not enforced");
            });
            app.routes().get("/response", request -> {
                byte[] bytes = new byte[4194304];
                responseBuilt = true;

                return new Response(200, new Headers().add("Content-Type", "application/octet-stream"), bytes);
            });
            app.routes().get("/noncooperative", request -> {
                signal(cleanup, peer, "/noncooperative");
                for (; ; ) LockSupport.parkNanos(1000000);
            });
            app.controlRoute("POST", "/control/cancel", request -> {
                Cancellation token = cancellable;

                if (token == null) throw new AssertionError("No operation to cancel");

                token.cancel();

                return Response.text(200, "cancelled");
            });
            app.controlRoute("POST", "/control/gc", request -> {
                System.gc();

                return Response.text(200, "collected");
            });
            app.controlRoute("GET", "/control/state", request -> json(app, state(app)));
            try {
                app.run();

                if (System.getenv("TASK_START_FAIL") != null) throw new AssertionError("Occupied listener started");
            } catch (IOException failed) {
                if (System.getenv("TASK_START_FAIL") == null) throw failed;

                System.out.println("start_failed_cleanly=true");
            }
            app.close();

            if (app.broker().executions.activeCount() != 0
                    || !app.taskDiagnostics().isEmpty()
                    || app.incomingStats().active != 0
                    || app.broker().admissionStats().active != 0)
                throw new AssertionError("Retained operations after close");

            System.out.println("tasks_closed=true");
        }
    }

    private static void signal(HttpClient http, URI base, String path) throws Exception {
        if (http.exchange(URI.create(base.toString() + path), "GET", new Headers(), new byte[0]).status != 200)
            throw new AssertionError("Cleanup peer failed");
    }

    private static Response json(Microservice app, Object value) {
        return Responses.json(200, value, JsonTree.CODEC, app.jsonLimits());
    }

    private static Map<String, Object> state(Microservice app) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roots", app.broker().executions.activeCount());
        result.put("incoming", app.incomingStats().active);
        result.put("pending", app.broker().admissionStats().pending);
        result.put("outgoing", app.broker().admissionStats().active);
        result.put("poolActive", app.dataPoolStats().activeConnections);
        result.put("poolPending", app.dataPoolStats().pendingAcquires);
        result.put("encoded", ENCODED.get());
        result.put("bodyReturned", bodyReturned);
        result.put("childFinished", childFinished);
        result.put("resourceClosed", resourceClosed);
        result.put("responseBuilt", responseBuilt);
        List<String> states = new ArrayList<>();
        for (TaskDiagnostics.Snapshot snapshot : app.taskDiagnostics())
            for (TaskDiagnostics.Entry entry : snapshot.entries())
                states.add(entry.phase() + "/" + entry.state() + "/" + entry.waitReason());
        result.put("diagnostics", states);
        int retained = 0;
        synchronized (REQUESTS) {
            for (WeakReference<Object> ref : REQUESTS) if (ref.get() != null) retained++;
        }
        result.put("retainedRequests", retained);
        RuntimeMetrics.Snapshot runtime = RuntimeMetrics.snapshot();
        result.put("tasks", runtime.tasksActive);
        result.put("waiting", runtime.tasksWaiting);
        result.put("handles", runtime.serviceHandlesOpen);
        result.put("heap", runtime.heapUsedBytes);
        result.put("allocated", runtime.allocatedBytes);
        result.put("gc", runtime.gcCollections);

        return result;
    }
}
