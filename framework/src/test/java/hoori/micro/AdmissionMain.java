package hoori.micro;

import hoori.http.HttpClient;
import hoori.rest.Responses;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP fixture: counters prove admission happens before framework codecs and handlers. */
public final class AdmissionMain {
    private static final AtomicInteger READS = new AtomicInteger(),
            WRITES = new AtomicInteger(),
            HANDLERS = new AtomicInteger();
    private static final JsonCodec<Object> INPUT = new JsonCodec<>() {
        public Object read(JsonReader reader) {
            READS.incrementAndGet();

            return JsonTree.CODEC.read(reader);
        }

        public void write(Object value, JsonWriter writer) {
            WRITES.incrementAndGet();

            if (value.equals("slow-encode")) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                }
            }

            JsonTree.CODEC.write(value, writer);
        }
    };
    private static final Action<Object, Object> OUT = new Action<>("recipes.echo", INPUT, JsonTree.CODEC);
    private static final Action<Object, Object> BILL = new Action<>("billing.echo", INPUT, JsonTree.CODEC);

    public static void main(String[] args) throws Exception {
        Service service = Service.named("admission")
                .dependsOn("recipes", 1)
                .dependsOn("billing", 1)
                .action("in", INPUT, JsonTree.CODEC, (ctx, value) -> {
                    HANDLERS.incrementAndGet();

                    if (value.equals("hold")) Thread.sleep(1500);

                    return value;
                });
        try (Microservice app = Microservice.create(service)) {
            Gateway.mount(app, (request, permission) -> permission.equals("recipes:read"));
            app.routes()
                    .post(
                            "/typed",
                            request -> Responses.json(
                                    200, app.context().call(OUT, "ok"), JsonTree.CODEC, app.jsonLimits()));
            app.routes()
                    .post(
                            "/generic",
                            request -> Responses.json(
                                    200,
                                    app.context().call("recipes.echo", Map.of("value", "ok")),
                                    JsonTree.CODEC,
                                    app.jsonLimits()));
            app.routes()
                    .post(
                            "/billing",
                            request -> Responses.json(
                                    200, app.context().call(BILL, "ok"), JsonTree.CODEC, app.jsonLimits()));
            app.routes()
                    .post(
                            "/encode",
                            request -> Responses.json(
                                    200, app.context().call(OUT, "slow-encode"), JsonTree.CODEC, app.jsonLimits()));
            app.controlRoute("GET", "/probe", request -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("reads", READS.get());
                values.put("writes", WRITES.get());
                values.put("handlers", HANDLERS.get());
                Admission.Stats incoming = app.incomingStats(),
                        outgoing = app.broker().admissionStats();
                values.put("incoming_active", incoming.active);
                values.put("incoming_pending", incoming.pending);
                values.put("outgoing_active", outgoing.active);
                values.put("outgoing_pending", outgoing.pending);
                values.put("outgoing_expired", outgoing.expired);
                values.put("instances", app.broker().catalog().instances.length);
                values.put("sdk_pending", app.dataPoolStats().pendingAcquires);

                return Responses.json(200, values, JsonTree.CODEC, app.jsonLimits());
            });
            app.run();
            for (Admission.Stats stats :
                    new Admission.Stats[] {app.incomingStats(), app.broker().admissionStats()}) {
                if (stats.active != 0 || stats.pending != 0) throw new AssertionError("Admission retained work");
            }
            for (HttpClient.PoolStats stats :
                    new HttpClient.PoolStats[] {app.dataPoolStats(), app.controlPoolStats()}) {
                if (stats.activeConnections != 0 || stats.idleConnections != 0 || stats.pendingAcquires != 0)
                    throw new AssertionError("Pool retained resources");
            }
            System.out.println("admission_closed=true pools_closed=true");
        }
    }
}
