package hoori.micro;

import static org.junit.jupiter.api.Assertions.*;

import hoori.http.Headers;
import hoori.http.Response;
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
        Registry registry = new Registry(1000);
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
        Gateway.Route[] routes = Gateway.build(new Catalog(true, new Catalog.Instance[] {
            new Catalog.Instance("a", "recipes", 1, "http://a:8080", new Catalog.Entry[] {get, search}),
            new Catalog.Instance("b", "recipes", 1, "http://b:8080", new Catalog.Entry[] {get, search})
        }));
        assertEquals(2, routes.length); // Two instances of the same actions are one route each.
        routes = Gateway.build(new Catalog(true, new Catalog.Instance[] {
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
        return ("{\"complete\":" + complete + ",\"instances\":[" + String.join(",", instances) + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Response json(int status, String body) {
        return new Response(
                status, new Headers().add("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8));
    }
}
