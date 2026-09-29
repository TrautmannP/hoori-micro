package hoori.micro;

import static org.junit.jupiter.api.Assertions.*;

import hoori.http.Headers;
import hoori.http.Response;
import hoori.rest.RequestException;
import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonLimits;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ServiceBrokerTest {
    private static final JsonCodec<String> STRING = new JsonCodec<>() {
        public String read(JsonReader reader) {
            return reader.nextString();
        }

        public void write(String value, JsonWriter writer) {
            writer.value(value);
        }
    };
    private static final Action<String, String> GET = new Action<>("recipes.get", STRING, STRING);
    private static final Action<String, String> RECOMMEND = new Action<>("recipes.recommend", STRING, STRING);

    @Test
    void selectsPerActionAndMajorVersionDuringRollingUpdate() throws IOException {
        List<String> targets = new ArrayList<>();
        ServiceBroker broker = broker((context, target, method, headers, body) -> {
            targets.add(target.toString());
            assertEquals("POST", method);
            assertEquals("1", headers.get(ServiceBroker.VERSION_HEADER));
            assertNull(headers.get("Authorization"));

            return json(200, "\"" + headers.get(ServiceBroker.ACTION_HEADER) + "\"");
        });
        broker.accept(catalog(
                true,
                instance("recipes-old", 1, "http://old:8080", "get"),
                instance("recipes-new", 1, "http://new:8080", "get", "recommend"),
                instance("recipes-v2", 2, "http://v2:8080", "get", "recommend")));
        for (int i = 0; i < 4; i++) assertEquals("recipes.recommend", broker.call(null, RECOMMEND, "x"));
        assertEquals(
                List.of("http://new:8080/_hoori/invoke"),
                targets.stream().distinct().toList());
        targets.clear();
        for (int i = 0; i < 4; i++) broker.call(null, GET, "x");
        assertEquals(
                List.of("http://new:8080/_hoori/invoke", "http://old:8080/_hoori/invoke"),
                targets.stream().distinct().sorted().toList());
    }

    @Test
    void genericCallUsesTheSameBroker() throws IOException {
        ServiceBroker broker = broker((context, target, method, headers, body) -> {
            assertEquals("{\"id\":7}", new String(body, StandardCharsets.UTF_8));

            return json(200, "{\"id\":7,\"title\":\"Suppe\",\"tags\":[1,2.5,true,null]}");
        });
        broker.accept(catalog(true, instance("recipes-a", 1, "http://a:8080", "get")));
        Map<?, ?> result = (Map<?, ?>) broker.call(null, "recipes.get", Map.of("id", 7));
        assertEquals(7L, result.get("id"));
        assertEquals("Suppe", result.get("title"));
        assertEquals(Arrays.asList(1L, 2.5, true, null), result.get("tags"));
    }

    @Test
    void failsBeforeIoWithoutDependencyOrOfferingInstance() {
        ServiceBroker broker = broker((context, target, method, headers, body) -> fail("No transport call expected"));
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.call(null, new Action<>("billing.get", STRING, STRING), "x"));
        assertThrows(IllegalArgumentException.class, () -> broker.call(null, "recipes", Map.of()));
        ServiceCallException none = assertThrows(ServiceCallException.class, () -> broker.call(null, GET, "x"));
        assertEquals(0, none.upstreamStatus());
    }

    @Test
    void noRetryAndNoPeerBodyInErrors() throws IOException {
        int[] calls = {0};
        ServiceBroker failing = broker((context, target, method, headers, body) -> {
            calls[0]++;
            throw new IOException("peer may already have committed");
        });
        failing.accept(
                catalog(true, instance("a", 1, "http://a:8080", "get"), instance("b", 1, "http://b:8080", "get")));
        assertEquals(
                0,
                assertThrows(ServiceCallException.class, () -> failing.call(null, GET, "x"))
                        .upstreamStatus());
        assertEquals(1, calls[0]);

        for (int status : new int[] {301, 404, 421, 500}) {
            ServiceBroker broker = broker((context, target, method, headers, body) -> json(status, "sensitive body"));
            broker.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
            ServiceCallException failure = assertThrows(ServiceCallException.class, () -> broker.call(null, GET, "x"));
            assertEquals(status, failure.upstreamStatus());
            assertFalse(failure.getMessage().contains("sensitive"));
        }
        for (String type : new String[] {"text/html", "application/problem+json", "application/json; charset=latin1"}) {
            ServiceBroker broker = broker((context, target, method, headers, body) -> new Response(
                    200, new Headers().add("Content-Type", type), "\"ok\"".getBytes(StandardCharsets.UTF_8)));
            broker.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
            assertThrows(ServiceCallException.class, () -> broker.call(null, GET, "x"));
        }
        InterruptedIOException original = new InterruptedIOException("cancelled");
        ServiceBroker cancelled = broker((context, target, method, headers, body) -> {
            throw original;
        });
        cancelled.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
        assertSame(original, assertThrows(InterruptedIOException.class, () -> cancelled.call(null, GET, "x")));
    }

    @Test
    void catalogSurvivesRegistryRestartButNotUnboundedAge() throws Exception {
        ServiceBroker broker = new ServiceBroker(
                shopping(),
                ServiceConfig.from(
                        "shopping",
                        key -> key.equals("HOORI_HEARTBEAT_MS")
                                ? "100"
                                : key.equals("HOORI_CATALOG_MAX_AGE_MS") ? "150" : null),
                (context, target, method, headers, body) -> json(200, "\"ok\""),
                JsonLimits.DEFAULT);
        broker.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
        broker.accept(catalog(false)); // Restarted registry before re-registrations: keep the full view.
        assertEquals("ok", broker.call(null, GET, "x"));
        Thread.sleep(200);
        assertThrows(ServiceCallException.class, () -> broker.call(null, GET, "x"));
    }

    @Test
    void registryExpiresRegistrationsAndReportsWarmup() {
        Registry registry = new Registry(1000, JsonLimits.DEFAULT);
        long now = System.nanoTime();
        Catalog.Instance a = parse(instance("recipes-a", 1, "http://a:8080", "get"));
        assertFalse(registry.register(a, now).complete);
        assertTrue(registry.snapshot(now + 1_001_000_000L).complete);
        assertEquals(0, registry.snapshot(now + 1_001_000_000L).instances.length);
        registry.register(a, now);
        registry.remove("recipes-a");
        assertEquals(0, registry.snapshot(now).instances.length);
    }

    @Test
    void leasesReuseSnapshotsAndOnlyVisibleChangesAdvanceRevision() {
        Registry registry = new Registry(1000, JsonLimits.DEFAULT);
        long now = System.nanoTime();
        Catalog.Instance a = parse(instance("a", 1, "http://a:8080", "get"));
        Catalog first = registry.register(a, now);
        Response body = registry.reply(new Headers(), now);
        assertSame(first, registry.register(parse(instance("a", 1, "http://a:8080", "get")), now + 1));
        assertTrue(registry.renew("a", now + 2));
        assertSame(first, registry.snapshot(now + 2));
        assertSame(body.body, registry.reply(new Headers(), now + 2).body);
        Headers known = version(first);
        assertEquals(204, registry.reply(known, now + 2).status);
        assertEquals(0, registry.reply(known, now + 2).body.length);
        assertEquals(200, registry.reply(new Headers().add(Catalog.EPOCH_HEADER, first.epoch), now).status);
        assertEquals(
                200, registry.reply(version(new Catalog("other", first.revision, true, first.instances)), now).status);

        Catalog changed = registry.register(parse(instance("a", 1, "http://a:8080", "get", "recommend")), now + 3);
        assertTrue(changed.revision > first.revision);
        assertEquals(2, changed.instances[0].actions.length);
        assertEquals(200, registry.reply(known, now + 3).status);
        registry.remove("absent");
        assertSame(changed, registry.snapshot(now + 3));
        // Renew after warmup but before this instance's expiry, then expire the renewed lease.
        assertTrue(registry.renew("a", now + 1_000_000_001L));
        Catalog complete = registry.snapshot(now + 1_000_000_001L);
        assertTrue(complete.complete);
        assertTrue(complete.revision > changed.revision);
        assertSame(complete, registry.snapshot(now + 1_000_000_002L));
        assertFalse(registry.renew("a", now + 2_000_000_002L));
        assertEquals(0, registry.snapshot(now + 2_000_000_002L).instances.length);
        assertTrue(registry.snapshot(now + 2_000_000_002L).revision > complete.revision);
        assertNotEquals(first.epoch, new Registry(1000, JsonLimits.DEFAULT).snapshot(now).epoch);
    }

    @Test
    void rejectedMetadataKeepsTheCatalogAndDoesNotRenewTheLease() {
        JsonLimits limits = new JsonLimits(64, 16384, 128, 1024);
        Registry registry = new Registry(1000, limits);
        long now = System.nanoTime();
        registry.register(parse(instance("a", 1, "http://a:8080", "get")), now);
        Catalog current = registry.register(parse(instance("b", 1, "http://b:8080", "get")), now);
        byte[] before = registry.reply(new Headers(), now).body;
        Catalog.Entry[] many = new Catalog.Entry[40];
        for (int i = 0; i < many.length; i++) many[i] = new Catalog.Entry("action-" + i, null, null, null);
        Catalog.Instance big = new Catalog.Instance("b", "recipes", 1, "http://b:8080", many);
        assertTrue(Json.encode(big, Catalog.INSTANCE, limits).length < 1024); // Valid alone; aggregate too large.
        assertEquals(
                413, assertThrows(RequestException.class, () -> registry.register(big, now + 500_000_000L)).status);
        assertSame(current, registry.snapshot(now + 500_000_000L));
        assertSame(before, registry.reply(new Headers(), now + 500_000_000L).body);
        assertFalse(registry.renew("b", now + 1_000_000_001L));

        Registry full = new Registry(60_000, JsonLimits.DEFAULT);
        for (int i = 0; i < Catalog.MAX_INSTANCES; i++)
            full.register(parse(instance("node-" + i, 1, "http://a:8080", "get")), now);
        assertEquals(
                429,
                assertThrows(
                                RequestException.class,
                                () -> full.register(parse(instance("extra", 1, "http://a:8080", "get")), now))
                        .status);
        assertEquals(Catalog.MAX_INSTANCES, full.snapshot(now).instances.length);
    }

    @Test
    void brokerSendsSmallLeasesAndReregistersOnceAfterRestart() throws Exception {
        Registry[] registry = {new Registry(60_000, JsonLimits.DEFAULT)};
        long[] now = {System.nanoTime() + 60_001_000_000L};
        List<String> methods = new ArrayList<>();
        Service provider = Service.named("shopping")
                .action("ping", STRING, STRING, (ctx, in) -> in)
                .freeze();
        ServiceConfig config =
                ServiceConfig.from("shopping", key -> key.equals("HOORI_INSTANCE_ID") ? "shopping-test" : null);
        ServiceBroker broker = new ServiceBroker(
                provider,
                config,
                (context, target, method, headers, body) -> {
                    methods.add(method);
                    assertEquals("2", headers.get(Catalog.PROTOCOL_HEADER));

                    if (method.equals("PUT")) registry[0].register(Json.decode(body, Catalog.INSTANCE), now[0]);
                    else {
                        assertEquals(0, body.length);

                        if (!registry[0].renew(config.instanceId, now[0])) return Response.text(404, "Unknown lease");
                    }

                    return registry[0].reply(headers, now[0]);
                },
                JsonLimits.DEFAULT);
        broker.beat(true);
        Catalog first = broker.catalog();
        for (int i = 0; i < 4; i++) {
            broker.beat(true);
            assertSame(first, broker.catalog());
            int delay = broker.heartbeatDelayMillis();
            assertTrue(delay >= 1800 && delay <= 2200 && delay < config.registryTtlMillis);
        }
        assertEquals(List.of("PUT", "POST", "POST", "POST", "POST"), methods);
        registry[0] = new Registry(60_000, JsonLimits.DEFAULT);
        now[0] = System.nanoTime() + 60_001_000_000L;
        broker.beat(true);
        assertEquals(List.of("PUT", "POST", "POST", "POST", "POST", "POST", "PUT"), methods);
        assertNotEquals(first.epoch, broker.catalog().epoch);
    }

    @Test
    void staleVersionsAndUnmatchedConfirmationsCannotExtendFreshness() throws Exception {
        Response[] reply = {null};
        ServiceConfig config = ServiceConfig.from(
                "shopping",
                key -> key.equals("HOORI_HEARTBEAT_MS")
                        ? "100"
                        : key.equals("HOORI_CATALOG_MAX_AGE_MS") ? "150" : null);
        ServiceBroker broker = new ServiceBroker(shopping(), config, (c, t, m, h, b) -> reply[0], JsonLimits.DEFAULT);
        Catalog original =
                new Catalog("old", 10, true, new Catalog.Instance[] {parse(instance("a", 1, "http://a:8080", "get"))});
        broker.accept(Json.encode(original, Catalog.CODEC));
        Catalog held = broker.catalog();
        reply[0] = new Response(204, version(new Catalog("new", 10, true, original.instances)), new byte[0]);
        broker.beat(false);
        assertSame(held, broker.catalog());
        assertTrue(broker.heartbeatDelayMillis() >= 180 && broker.heartbeatDelayMillis() <= 220);
        broker.accept(Json.encode(new Catalog("old", 9, true, new Catalog.Instance[0]), Catalog.CODEC));
        broker.accept(Json.encode(new Catalog("new", 1, false, new Catalog.Instance[0]), Catalog.CODEC));
        Thread.sleep(180);
        assertSame(Catalog.EMPTY, broker.catalog());
        broker.accept(Json.encode(new Catalog("new", 2, true, original.instances), Catalog.CODEC));
        Catalog recovered = broker.catalog();
        broker.accept(Json.encode(new Catalog("old", 99, true, new Catalog.Instance[0]), Catalog.CODEC));
        assertSame(recovered, broker.catalog());
        reply[0] = new Response(204, version(recovered), new byte[0]);
        broker.beat(false);
        assertSame(recovered, broker.catalog());
        assertTrue(broker.heartbeatDelayMillis() >= 90 && broker.heartbeatDelayMillis() <= 110);
        reply[0] = json(
                200,
                new String(Json.encode(original, Catalog.CODEC), StandardCharsets.UTF_8)); // Old registry lacks v2.
        broker.beat(false);
        Thread.sleep(180);
        assertSame(Catalog.EMPTY, broker.catalog());
        assertThrows(
                RuntimeException.class,
                () -> broker.accept("{\"complete\":true,\"instances\":[]}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void publicationIsExplicitNamespacedAndConflictFree() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Service.named("recipes")
                        .action("get", STRING, STRING, (ctx, in) -> in)
                        .http("GET", "/recipes/{id}")
                        .freeze());
        assertThrows(
                IllegalArgumentException.class,
                () -> Service.named("recipes")
                        .action("get", STRING, STRING, (ctx, in) -> in)
                        .http("GET", "/r/{id}")
                        .requirePermission("admin:all"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Service.named("recipes")
                        .action("get", STRING, STRING, (ctx, in) -> in)
                        .http("POST", "/_hoori/invoke"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Service.named("recipes")
                        .action("a", STRING, STRING, (ctx, in) -> in)
                        .http("GET", "/r/{id}")
                        .requirePermission("recipes:read")
                        .action("b", STRING, STRING, (ctx, in) -> in)
                        .http("GET", "/r/{x}")
                        .requirePermission("recipes:read")
                        .freeze());

        Catalog.Entry get = new Catalog.Entry("get", "GET", "/recipes/{id}", "recipes:read");
        Catalog.Entry other = new Catalog.Entry("lookup", "GET", "/recipes/{key}", "recipes:read");
        Catalog.Entry search = new Catalog.Entry("search", "GET", "/recipes/search", "recipes:read");
        Gateway.Route[] routes = Gateway.build(new Catalog("test", 1, true, new Catalog.Instance[] {
            new Catalog.Instance("a", "recipes", 1, "http://a:8080", new Catalog.Entry[] {get, search}),
            new Catalog.Instance("b", "recipes", 1, "http://b:8080", new Catalog.Entry[] {get, search})
        }));
        assertEquals(2, routes.length); // Two instances of the same actions are one route each.
        routes = Gateway.build(new Catalog("test", 1, true, new Catalog.Instance[] {
            new Catalog.Instance("a", "recipes", 1, "http://a:8080", new Catalog.Entry[] {get, search}),
            new Catalog.Instance("b", "recipes", 1, "http://b:8080", new Catalog.Entry[] {other})
        }));
        assertEquals(1, routes.length); // Contradicting /recipes/{id} definitions are both withheld.
        assertEquals("search", routes[0].action);
    }

    @Test
    void registrationsAreValidated() {
        assertNotNull(parse(instance("recipes-a", 1, "http://a:8080", "get")));
        for (String bad : new String[] {
            "{\"id\":\"a\",\"service\":\"recipes\",\"version\":1,\"url\":\"http://u:p@a\",\"actions\":[]}",
            "{\"id\":\"a\",\"service\":\"recipes\",\"version\":0,\"url\":\"http://a\",\"actions\":[]}",
            "{\"id\":\"a\",\"service\":\"recipes\",\"version\":1,\"url\":\"http://a\",\"actions\":[{\"name\":\"get\"},{\"name\":\"get\"}]}",
            "{\"id\":\"a\",\"service\":\"recipes\",\"version\":1,\"url\":\"http://a\",\"actions\":[{\"name\":\"get\",\"method\":\"GET\",\"path\":\"/x\"}]}"
        })
            assertThrows(
                    RuntimeException.class, () -> Json.decode(bad.getBytes(StandardCharsets.UTF_8), Catalog.INSTANCE));
    }

    private static Service shopping() {
        return Service.named("shopping").dependsOn("recipes", 1).freeze();
    }

    private static ServiceBroker broker(ServiceBroker.Exchange exchange) {
        return new ServiceBroker(shopping(), ServiceConfig.from("shopping", key -> null), exchange, JsonLimits.DEFAULT);
    }

    private static String instance(String id, int version, String url, String... actions) {
        StringBuilder json = new StringBuilder("{\"id\":\"" + id + "\",\"service\":\"recipes\",\"version\":" + version
                + ",\"url\":\"" + url + "\",\"unknown\":{\"x\":1},\"actions\":[");
        for (int i = 0; i < actions.length; i++)
            json.append(i == 0 ? "" : ",")
                    .append("{\"name\":\"")
                    .append(actions[i])
                    .append("\"}");

        return json.append("]}").toString();
    }

    private static Catalog.Instance parse(String json) {
        return Json.decode(json.getBytes(StandardCharsets.UTF_8), Catalog.INSTANCE);
    }

    private static byte[] catalog(boolean complete, String... instances) {
        return ("{\"epoch\":\"test\",\"revision\":1,\"complete\":" + complete + ",\"instances\":["
                        + String.join(",", instances) + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Response json(int status, String body) {
        return new Response(
                status, new Headers().add("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8));
    }

    private static Headers version(Catalog catalog) {
        return new Headers()
                .add(Catalog.PROTOCOL_HEADER, "2")
                .add(Catalog.EPOCH_HEADER, catalog.epoch)
                .add(Catalog.REVISION_HEADER, Long.toString(catalog.revision));
    }
}
