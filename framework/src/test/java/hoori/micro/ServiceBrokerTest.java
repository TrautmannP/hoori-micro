package hoori.micro;

import static org.junit.jupiter.api.Assertions.*;

import hoori.http.Headers;
import hoori.http.HttpClientClosedException;
import hoori.http.RequestBudget;
import hoori.http.Response;
import hoori.rest.RequestException;
import hoori.rest.json.Json;
import hoori.rest.json.JsonCodec;
import hoori.rest.json.JsonLimits;
import hoori.rest.json.JsonReader;
import hoori.rest.json.JsonWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
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
    private static final ExchangeCase<String> GET = new ExchangeCase<>("recipes", endpoint("get"), STRING, STRING);
    private static final ExchangeCase<String> RECOMMEND =
            new ExchangeCase<>("recipes", endpoint("recommend"), STRING, STRING);

    @Test
    void selectsPerEndpointAndMajorVersionDuringRollingUpdate() throws IOException {
        List<String> targets = new ArrayList<>();
        ServiceBroker broker = broker((target, method, headers, body, budget) -> {
            targets.add(target.toString());
            assertEquals("POST", method);
            assertEquals("1", headers.get(ServiceBroker.VERSION_HEADER));
            assertNull(headers.get("Authorization"));

            return json(200, "\"" + headers.get(ServiceBroker.ENDPOINT_HEADER) + "\"");
        });
        broker.accept(catalog(
                true,
                instance("recipes-old", 1, "http://old:8080", "get"),
                instance("recipes-new", 1, "http://new:8080", "get", "recommend"),
                instance("recipes-v2", 2, "http://v2:8080", "get", "recommend")));
        for (int i = 0; i < 4; i++)
            assertEquals(
                    RECOMMEND.endpoint.key(),
                    call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), RECOMMEND, "x"));
        assertEquals(
                List.of("http://new:8080/recommend"),
                targets.stream().distinct().toList());
        targets.clear();
        for (int i = 0; i < 4; i++) call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), GET, "x");
        assertEquals(
                List.of("http://new:8080/get", "http://old:8080/get"),
                targets.stream().distinct().sorted().toList());
    }

    @Test
    void explicitJsonExchangeUsesTheSameBroker() throws IOException {
        ServiceBroker broker = broker((target, method, headers, body, budget) -> {
            assertEquals("{\"id\":7}", new String(body, StandardCharsets.UTF_8));

            return json(200, "{\"id\":7,\"title\":\"Suppe\",\"tags\":[1,2.5,true,null]}");
        });
        broker.accept(catalog(true, instance("recipes-a", 1, "http://a:8080", "get")));
        Map<?, ?> result = (Map<?, ?>) call(
                broker,
                null,
                RequestBudget.afterMillis(broker.callTimeoutMillis()),
                new ExchangeCase<>("recipes", endpoint("get"), JsonTree.CODEC, JsonTree.CODEC),
                Map.of("id", 7));
        assertEquals(7L, result.get("id"));
        assertEquals("Suppe", result.get("title"));
        assertEquals(Arrays.asList(1L, 2.5, true, null), result.get("tags"));
    }

    @Test
    void admissionRejectsBeforeEncodingAndReleasesEveryFailure() throws Exception {
        int[] writes = {0}, sends = {0};
        JsonCodec<String> counted = new JsonCodec<>() {
            public String read(JsonReader reader) {
                return STRING.read(reader);
            }

            public void write(String value, JsonWriter writer) {
                writes[0]++;
                STRING.write(value, writer);
            }
        };
        ExchangeCase<String> get = new ExchangeCase<>("recipes", endpoint("get"), counted, STRING);
        ExchangeCase<String> bill = new ExchangeCase<>("billing", endpoint("get"), counted, STRING);
        ServiceConfig config = ServiceConfig.from(
                "shopping",
                key -> key.equals("HOORI_OUTGOING_CALLS")
                        ? "1"
                        : key.equals("HOORI_OUTGOING_PENDING_CALLS") ? "0" : null);
        ServiceBroker broker = new ServiceBroker(
                definition("shopping", "recipes", "billing"),
                config,
                (target, method, headers, body, budget) -> {
                    assertNotNull(budget);
                    sends[0]++;

                    return json(200, "\"ok\"");
                },
                JsonLimits.DEFAULT);
        broker.accept(catalog(true, instance("recipes-a", 1, "http://a:8080", "get")));
        try (Admission.Permit held = broker.admit(null, RequestBudget.afterMillis(broker.callTimeoutMillis()))) {
            assertThrows(
                    CallRejectedException.class,
                    () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), get, "x"));
            assertThrows(
                    CallRejectedException.class,
                    () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), bill, "x"));
            // This would fail JSON encoding if the encoder materialized it before admission.
            assertThrows(
                    CallRejectedException.class,
                    () -> call(
                            broker,
                            null,
                            RequestBudget.afterMillis(broker.callTimeoutMillis()),
                            new ExchangeCase<>("recipes", endpoint("get"), JsonTree.CODEC, JsonTree.CODEC),
                            Map.of("bad", new Object())));
            assertEquals(0, writes[0]);
            assertEquals(0, sends[0]);
            assertEquals(1, broker.admissionStats().active);
        }
        assertEquals(
                "ok",
                call(
                        broker,
                        null,
                        RequestBudget.afterMillis(broker.callTimeoutMillis()),
                        get,
                        "x")); // max=1 proves no second permit in invoke().
        assertEquals(
                "ok",
                call(
                        broker,
                        null,
                        RequestBudget.afterMillis(broker.callTimeoutMillis()),
                        new ExchangeCase<>("recipes", endpoint("get"), JsonTree.CODEC, JsonTree.CODEC),
                        Map.of("id", 7)));
        assertThrows(
                ServiceCallException.class,
                () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), bill, "x"));
        JsonCodec<String> broken = new JsonCodec<>() {
            public String read(JsonReader reader) {
                return STRING.read(reader);
            }

            public void write(String value, JsonWriter writer) {
                throw new IllegalStateException("codec failed");
            }
        };
        assertThrows(
                IllegalStateException.class,
                () -> call(
                        broker,
                        null,
                        RequestBudget.afterMillis(broker.callTimeoutMillis()),
                        new ExchangeCase<>("recipes", endpoint("get"), broken, STRING),
                        "x"));
        assertEquals(0, broker.admissionStats().active);
        assertEquals(0, broker.admissionStats().pending);
        broker.stop();
        int encoded = writes[0];
        assertThrows(
                CallRejectedException.class,
                () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), get, "x"));
        assertEquals(encoded, writes[0]);
        assertEquals(2, sends[0]);
    }

    @Test
    void admissionWaitConsumesTheSameBudgetSentToTheSdk() throws Exception {
        int[] writes = {0}, sends = {0};
        JsonCodec<String> counted = new JsonCodec<>() {
            public String read(JsonReader reader) {
                return STRING.read(reader);
            }

            public void write(String value, JsonWriter writer) {
                writes[0]++;
                STRING.write(value, writer);
            }
        };
        ServiceConfig config = ServiceConfig.from(
                "shopping",
                key -> key.equals("HOORI_OUTGOING_CALLS")
                        ? "1"
                        : key.equals("HOORI_OUTGOING_PENDING_CALLS")
                                ? "1"
                                : key.equals("HOORI_CLIENT_TIMEOUT_MS") ? "1000" : null);
        RequestBudget[] observed = {null};
        ServiceBroker broker = new ServiceBroker(
                shopping(),
                config,
                (target, method, headers, body, budget) -> {
                    observed[0] = budget;
                    sends[0]++;
                    assertTrue(budget.remainingMillis() < 950, "Admission wait restarted the exchange budget");

                    return json(200, "\"ok\"");
                },
                JsonLimits.DEFAULT);
        broker.accept(catalog(true, instance("recipes-a", 1, "http://a:8080", "get")));
        Throwable[] failed = {null};
        Thread caller;
        try (Admission.Permit held = broker.admit(null, RequestBudget.afterMillis(broker.callTimeoutMillis()))) {
            caller = new Thread(() -> {
                try {
                    assertEquals(
                            "ok",
                            call(
                                    broker,
                                    null,
                                    RequestBudget.afterMillis(broker.callTimeoutMillis()),
                                    new ExchangeCase<>("recipes", endpoint("get"), counted, STRING),
                                    "x"));
                } catch (Throwable error) {
                    failed[0] = error;
                }
            });
            caller.start();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (broker.admissionStats().pending == 0 && System.nanoTime() - deadline < 0) Thread.sleep(1);
            assertEquals(1, broker.admissionStats().pending);
            assertEquals(0, writes[0]);
            assertEquals(0, sends[0]);
            Thread.sleep(100);
        }
        caller.join(2000);
        assertFalse(caller.isAlive());
        assertNull(failed[0]);
        assertNotNull(observed[0]);
        assertEquals(1, writes[0]);
        assertEquals(1, sends[0]);
        assertEquals(0, broker.admissionStats().active);
        assertEquals(0, broker.admissionStats().pending);
    }

    @Test
    void controlTimeoutsRecoverWhileCancellationAndCloseStop() throws Exception {
        Registry registry = new Registry(60_000, JsonLimits.DEFAULT);
        long now = System.nanoTime() + 60_001_000_000L;
        ServiceConfig config = ServiceConfig.from("shopping", key -> null);
        IOException[] failure = {new SocketTimeoutException("expired")};
        List<String> methods = new ArrayList<>();
        ServiceBroker broker = new ServiceBroker(
                shopping(),
                config,
                (target, method, headers, body, budget) -> {
                    assertEquals("/get", target.getPath());

                    return json(200, "\"ok\"");
                },
                (target, method, headers, body, budget) -> {
                    methods.add(method);
                    assertFalse(target.getPath().equals("/get"));

                    if (failure[0] != null) throw failure[0];

                    return registry.reply(headers, now);
                },
                JsonLimits.DEFAULT);
        assertTrue(broker.needsDiscovery());
        for (int i = 0; i < 2; i++) {
            broker.beat(false);
            assertFalse(Thread.currentThread().isInterrupted());
        }
        failure[0] = new IOException("transport failure");
        broker.beat(false);
        int delay = broker.heartbeatDelayMillis();
        assertTrue(delay >= 14400 && delay <= 17600);
        failure[0] = null;
        registry.register(parse(instance("recipes-a", 1, "http://a:8080", "get")), now);
        broker.beat(false);
        assertEquals("ok", call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), GET, "x"));
        delay = broker.heartbeatDelayMillis();
        assertTrue(delay >= 1800 && delay <= 2200);
        failure[0] = new InterruptedIOException("cancelled");
        assertSame(failure[0], assertThrows(InterruptedIOException.class, () -> broker.beat(false)));
        failure[0] = new HttpClientClosedException();
        assertSame(failure[0], assertThrows(HttpClientClosedException.class, () -> broker.beat(false)));
        failure[0] = new SocketTimeoutException("timeout racing stop");
        Thread.currentThread().interrupt();
        try {
            assertSame(failure[0], assertThrows(SocketTimeoutException.class, () -> broker.beat(false)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of("GET", "GET", "GET", "GET", "GET", "GET", "GET"), methods);
        ServiceBroker inactive = new ServiceBroker(
                definition("static"), config, (t, m, h, b, budget) -> fail("Inactive discovery"), JsonLimits.DEFAULT);
        assertFalse(inactive.needsDiscovery());
        inactive.beat(false);
    }

    @Test
    void failsBeforeIoWithoutDependencyOrOfferingInstance() {
        ServiceBroker broker = broker((target, method, headers, body, budget) -> fail("No transport call expected"));
        assertThrows(
                IllegalArgumentException.class,
                () -> call(
                        broker,
                        null,
                        RequestBudget.afterMillis(broker.callTimeoutMillis()),
                        new ExchangeCase<>("billing", endpoint("get"), STRING, STRING),
                        "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> call(
                        broker,
                        null,
                        RequestBudget.afterMillis(broker.callTimeoutMillis()),
                        new ExchangeCase<>("missing", endpoint("get"), JsonTree.CODEC, JsonTree.CODEC),
                        Map.of()));
        ServiceCallException none = assertThrows(
                ServiceCallException.class,
                () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), GET, "x"));
        assertEquals(0, none.upstreamStatus());
    }

    @Test
    void noRetryAndNoPeerBodyInErrors() throws IOException {
        int[] calls = {0};
        ServiceBroker failing = broker((target, method, headers, body, budget) -> {
            calls[0]++;
            throw new IOException("peer may already have committed");
        });
        failing.accept(
                catalog(true, instance("a", 1, "http://a:8080", "get"), instance("b", 1, "http://b:8080", "get")));
        assertEquals(
                0,
                assertThrows(
                                ServiceCallException.class,
                                () -> call(
                                        failing,
                                        null,
                                        RequestBudget.afterMillis(failing.callTimeoutMillis()),
                                        GET,
                                        "x"))
                        .upstreamStatus());
        assertEquals(1, calls[0]);
        assertEquals(0, failing.admissionStats().active);

        for (int status : new int[] {301, 404, 421, 500}) {
            ServiceBroker broker = broker((target, method, headers, body, budget) -> json(status, "sensitive body"));
            broker.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
            ServiceCallException failure = assertThrows(
                    ServiceCallException.class,
                    () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), GET, "x"));
            assertEquals(status, failure.upstreamStatus());
            assertFalse(failure.getMessage().contains("sensitive"));
            assertEquals(0, broker.admissionStats().active);
        }
        for (String type : new String[] {"text/html", "application/problem+json", "application/json; charset=latin1"}) {
            ServiceBroker broker = broker((target, method, headers, body, budget) -> new Response(
                    200, new Headers().add("Content-Type", type), "\"ok\"".getBytes(StandardCharsets.UTF_8)));
            broker.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
            assertThrows(
                    ServiceCallException.class,
                    () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), GET, "x"));
            assertEquals(0, broker.admissionStats().active);
        }
        InterruptedIOException original = new InterruptedIOException("cancelled");
        ServiceBroker cancelled = broker((target, method, headers, body, budget) -> {
            throw original;
        });
        cancelled.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
        assertSame(
                original,
                assertThrows(
                        InterruptedIOException.class,
                        () -> call(
                                cancelled, null, RequestBudget.afterMillis(cancelled.callTimeoutMillis()), GET, "x")));
        assertEquals(0, cancelled.admissionStats().active);
        ServiceBroker badJson = broker((target, method, headers, body, budget) -> json(200, "{"));
        badJson.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
        assertThrows(
                ServiceCallException.class,
                () -> call(badJson, null, RequestBudget.afterMillis(badJson.callTimeoutMillis()), GET, "x"));
        assertEquals(0, badJson.admissionStats().active);
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
                (target, method, headers, body, budget) -> json(200, "\"ok\""),
                JsonLimits.DEFAULT);
        broker.accept(catalog(true, instance("a", 1, "http://a:8080", "get")));
        broker.accept(catalog(false)); // Restarted registry before re-registrations: keep the full view.
        assertEquals("ok", call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), GET, "x"));
        Thread.sleep(200);
        assertThrows(
                ServiceCallException.class,
                () -> call(broker, null, RequestBudget.afterMillis(broker.callTimeoutMillis()), GET, "x"));
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
        Response body = registry.reply(new Headers().add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL), now);
        assertSame(first, registry.register(parse(instance("a", 1, "http://a:8080", "get")), now + 1));
        assertTrue(registry.renew("a", now + 2));
        assertSame(first, registry.snapshot(now + 2));
        assertSame(
                body.body, registry.reply(new Headers().add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL), now + 2).body);
        Headers known = version(first);
        assertEquals(204, registry.reply(known, now + 2).status);
        assertEquals(0, registry.reply(known, now + 2).body.length);
        assertEquals(
                200,
                registry.reply(
                                new Headers()
                                        .add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL)
                                        .add(Catalog.EPOCH_HEADER, first.epoch),
                                now)
                        .status);
        assertEquals(
                200, registry.reply(version(new Catalog("other", first.revision, true, first.instances)), now).status);

        Catalog changed = registry.register(parse(instance("a", 1, "http://a:8080", "get", "recommend")), now + 3);
        assertTrue(changed.revision > first.revision);
        assertEquals(2, changed.instances[0].endpoints.length);
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
        Catalog.Entry[] many = new Catalog.Entry[40];
        for (int i = 0; i < many.length; i++) many[i] = new Catalog.Entry(endpoint("route-" + i), null);
        Catalog.Instance big = new Catalog.Instance("b", "recipes", 1, "http://b:8080", many);
        JsonLimits limits = new JsonLimits(64, 16384, 128, Json.encode(big, Catalog.INSTANCE).length + 16);
        Registry registry = new Registry(1000, limits);
        long now = System.nanoTime();
        registry.register(parse(instance("a", 1, "http://a:8080", "get")), now);
        Catalog current = registry.register(parse(instance("b", 1, "http://b:8080", "get")), now);
        byte[] before = registry.reply(new Headers().add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL), now).body;
        assertTrue(Json.encode(big, Catalog.INSTANCE, limits).length < limits.maxOutputBytes);
        assertEquals(
                413, assertThrows(RequestException.class, () -> registry.register(big, now + 500_000_000L)).status);
        assertSame(current, registry.snapshot(now + 500_000_000L));
        assertSame(
                before,
                registry.reply(new Headers().add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL), now + 500_000_000L).body);
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
        ServiceDefinition provider = definition("shopping");
        provider.endpoint(endpoint("ping"), null);
        ServiceConfig config =
                ServiceConfig.from("shopping", key -> key.equals("HOORI_INSTANCE_ID") ? "shopping-test" : null);
        ServiceBroker broker = new ServiceBroker(
                provider,
                config,
                (target, method, headers, body, budget) -> {
                    methods.add(method);
                    assertEquals(Catalog.PROTOCOL, headers.get(Catalog.PROTOCOL_HEADER));

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
        assertEquals(0, first.instances.length); // Pure provider leases never transfer a foreign catalog.
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
        ServiceBroker broker =
                new ServiceBroker(shopping(), config, (t, m, h, b, budget) -> reply[0], JsonLimits.DEFAULT);
        Catalog original =
                new Catalog("old", 10, true, new Catalog.Instance[] {parse(instance("a", 1, "http://a:8080", "get"))});
        broker.accept(Json.encode(original, Catalog.CODEC));
        Catalog held = broker.catalog();
        reply[0] = new Response(
                204,
                withView(version(new Catalog("new", 10, true, original.instances)), "services=recipes:1"),
                new byte[0]);
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
        reply[0] = new Response(204, withView(version(recovered), "services=recipes:1"), new byte[0]);
        broker.beat(false);
        assertSame(recovered, broker.catalog());
        assertTrue(broker.heartbeatDelayMillis() >= 90 && broker.heartbeatDelayMillis() <= 110);
        reply[0] = json(
                200,
                new String(
                        Json.encode(original, Catalog.CODEC),
                        StandardCharsets.UTF_8)); // Old registry lacks current protocol.
        broker.beat(false);
        Thread.sleep(180);
        assertSame(Catalog.EMPTY, broker.catalog());
        assertThrows(
                RuntimeException.class,
                () -> broker.accept("{\"complete\":true,\"instances\":[]}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void registryFiltersBeforeTransferAndBindsConfirmationsToTheView() {
        Registry registry = new Registry(60_000, JsonLimits.DEFAULT);
        long now = System.nanoTime();
        Catalog.Entry published =
                new Catalog.Entry(new HttpEndpoint("GET", "/recipes/{id}", "", "application/json"), "recipes:read");
        Catalog.Entry privateEndpoint = new Catalog.Entry(endpoint("private"), null);
        registry.register(
                new Catalog.Instance(
                        "old", "recipes", 1, "http://old:8080", new Catalog.Entry[] {published, privateEndpoint}),
                now);
        registry.register(parse(instance("new", 1, "http://new:8080", "get", "recommend")), now);
        registry.register(parse(instance("v2", 2, "http://v2:8080", "get")), now);
        for (int i = 0; i < 32; i++)
            registry.register(
                    new Catalog.Instance(
                            "node-" + i, "other-" + i, 1, "http://other:8080", new Catalog.Entry[] {privateEndpoint}),
                    now);
        Headers requested = new Headers()
                .add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL)
                .add(Catalog.VIEW_HEADER, "services=recipes:1");
        Response response = registry.reply(requested, now);
        Catalog selected = Json.decode(response.body, Catalog.CODEC);
        assertEquals(2, selected.instances.length);
        assertEquals(2, selected.instances[0].endpoints.length);
        assertNull(selected.instances[0].endpoints[0].permission);
        assertNotNull(selected.instances[0].endpoints[0].contract.path());
        assertEquals(
                "http://new:8080",
                selected.select("recipes", 1, endpoint("recommend").key(), 0).url);
        assertNull(selected.select("recipes", 2, endpoint("get").key(), 0));
        assertEquals("services=recipes:1", response.headers.get(Catalog.VIEW_HEADER));
        Headers known = version(selected)
                .add(Catalog.VIEW_HEADER, "services=recipes:1")
                .add(Catalog.KNOWN_VIEW_HEADER, "services=recipes:1");
        assertEquals(204, registry.reply(known, now).status);
        known = withView(known, "public");
        Response publicResponse = registry.reply(known, now);
        assertEquals(200, publicResponse.status); // Same epoch/revision, different selection.
        Catalog publicCatalog = Json.decode(publicResponse.body, Catalog.CODEC);
        assertEquals(1, publicCatalog.instances.length);
        assertEquals(1, publicCatalog.instances[0].endpoints.length);
        assertEquals("/recipes/{id}", publicCatalog.instances[0].endpoints[0].contract.path());
        known = withView(known, "public;services=recipes:1");
        Catalog hybrid = Json.decode(registry.reply(known, now).body, Catalog.CODEC);
        assertEquals(2, hybrid.instances.length);
        assertEquals(2, hybrid.instances[0].endpoints.length);
        known = withView(known, "none");
        assertEquals(0, Json.decode(registry.reply(known, now).body, Catalog.CODEC).instances.length);
        registry.remove("new");
        known = withView(known, "services=recipes:1");
        Catalog removed = Json.decode(registry.reply(known, now).body, Catalog.CODEC);
        assertNull(removed.select("recipes", 1, endpoint("recommend").key(), 0));
        assertEquals(0, Json.decode(registry.reply(known, now + 60_001_000_000L).body, Catalog.CODEC).instances.length);
        for (String invalid : List.of(
                "services=",
                "services=recipes:0",
                "services=recipes:01",
                "services=recipes:1,recipes:2",
                "services=recipes:1,",
                "unknown",
                "services=recipes:10000"))
            assertEquals(
                    400,
                    assertThrows(
                                    RequestException.class,
                                    () -> registry.reply(
                                            new Headers()
                                                    .add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL)
                                                    .add(Catalog.VIEW_HEADER, invalid),
                                            now))
                            .status);
        assertThrows(IllegalArgumentException.class, () -> Catalog.Filter.parse(new String(new char[2301])));
        StringBuilder tooMany = new StringBuilder("services=");
        for (int i = 0; i < 33; i++)
            tooMany.append(i == 0 ? "" : ",").append("service-").append(i).append(":1");
        assertThrows(IllegalArgumentException.class, () -> Catalog.Filter.parse(tooMany.toString()));
    }

    @Test
    void brokerRejectsWrongViewsAndReplacesExpiredRowsWithAFreshFullResponse() throws Exception {
        Registry registry = new Registry(60_000, JsonLimits.DEFAULT);
        long now = System.nanoTime() + 60_001_000_000L;
        registry.register(parse(instance("a", 1, "http://a:8080", "get")), now);
        ServiceConfig config = ServiceConfig.from(
                "shopping",
                key -> key.equals("HOORI_HEARTBEAT_MS")
                        ? "100"
                        : key.equals("HOORI_CATALOG_MAX_AGE_MS") ? "150" : null);
        boolean[] wrong = {false};
        List<Integer> statuses = new ArrayList<>();
        ServiceBroker broker = new ServiceBroker(
                shopping(),
                config,
                (t, m, h, b, budget) -> {
                    Response response = registry.reply(h, now);
                    statuses.add(response.status);
                    assertEquals("services=recipes:1", h.get(Catalog.VIEW_HEADER));

                    if (wrong[0])
                        response = new Response(response.status, withView(response.headers, "public"), response.body);

                    return response;
                },
                JsonLimits.DEFAULT);
        broker.beat(false);
        Catalog first = broker.catalog();
        broker.beat(false);
        assertSame(first, broker.catalog());
        wrong[0] = true;
        broker.beat(false);
        Thread.sleep(180);
        assertSame(Catalog.EMPTY, broker.catalog());
        // Expiry keeps only the version watermark, not rows or raw bodies.
        var field = ServiceBroker.class.getDeclaredField("view");
        field.setAccessible(true);
        var rows = field.get(broker).getClass().getDeclaredField("catalog");
        rows.setAccessible(true);
        assertEquals(0, ((Catalog) rows.get(field.get(broker))).instances.length);
        wrong[0] = false;
        broker.beat(false);
        assertEquals(List.of(200, 204, 204, 200), statuses);
        assertEquals(1, broker.catalog().instances.length);
        assertNotSame(first, broker.catalog());
        Catalog recovered = broker.catalog();
        broker.accept(Json.encode(
                new Catalog(recovered.epoch, recovered.revision - 1, true, new Catalog.Instance[0]), Catalog.CODEC));
        assertSame(recovered, broker.catalog());
    }

    @Test
    void publicationIsExplicitNamespacedAndConflictFree() {
        ServiceDefinition definition = definition("recipes");
        definition.endpoint(endpoint("private"), null);
        assertThrows(IllegalArgumentException.class, () -> definition.endpoint(endpoint("bad"), "admin:all"));
        assertThrows(IllegalArgumentException.class, () -> definition.endpoint(endpoint("bad"), ""));
        assertThrows(IllegalArgumentException.class, () -> endpoint("_hoori/invoke"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new hoori.rest.Router()
                        .get("/r/{id}", request -> json(200, "null"))
                        .get("/r/{x}", request -> json(200, "null"))
                        .freeze());

        Catalog.Entry get =
                new Catalog.Entry(new HttpEndpoint("GET", "/recipes/{id}", "", "application/json"), "recipes:read");
        Catalog.Entry other =
                new Catalog.Entry(new HttpEndpoint("GET", "/recipes/{key}", "", "application/json"), "recipes:read");
        Catalog.Entry search =
                new Catalog.Entry(new HttpEndpoint("GET", "/recipes/search", "", "application/json"), "recipes:read");
        Gateway.Route[] routes = Gateway.build(new Catalog("test", 1, true, new Catalog.Instance[] {
            new Catalog.Instance("a", "recipes", 1, "http://a:8080", new Catalog.Entry[] {get, search}),
            new Catalog.Instance("b", "recipes", 1, "http://b:8080", new Catalog.Entry[] {get, search})
        }));
        assertEquals(2, routes.length); // Two instances of the same endpoints are one route each.
        routes = Gateway.build(new Catalog("test", 1, true, new Catalog.Instance[] {
            new Catalog.Instance("a", "recipes", 1, "http://a:8080", new Catalog.Entry[] {get, search}),
            new Catalog.Instance("b", "recipes", 1, "http://b:8080", new Catalog.Entry[] {other})
        }));
        assertEquals(1, routes.length); // Contradicting /recipes/{id} definitions are both withheld.
        assertEquals("/recipes/search", routes[0].path);
    }

    @Test
    void registrationsAreValidated() {
        assertNotNull(parse(instance("recipes-a", 1, "http://a:8080", "get")));
        String valid = instance("a", 1, "http://a", "get");
        for (String bad : new String[] {
            valid.replace("http://a", "http://u:p@a"),
            valid.replace("\"version\":1", "\"version\":0"),
            instance("a", 1, "http://a", "get", "get"),
            valid.replace("\"consumes\":\"application/json\"", "\"consumes\":\"text/html\""),
            valid.replace("\"endpoints\"", "\"actions\"")
        })
            assertThrows(
                    RuntimeException.class, () -> Json.decode(bad.getBytes(StandardCharsets.UTF_8), Catalog.INSTANCE));
    }

    @Test
    void wireBudgetsAreBoundedAndRootCallsShareTheirDeadline() throws Exception {
        RequestBudget local = RequestBudget.afterMillis(500);
        assertSame(local, Context.incomingBudget(new Headers(), local));
        assertTrue(Context.incomingBudget(new Headers().add(Context.BUDGET_HEADER, "0"), local)
                .isExpired());
        assertTrue(Context.incomingBudget(new Headers().add(Context.BUDGET_HEADER, "600000"), local)
                        .remainingNanos()
                <= local.remainingNanos() + 1000000);
        for (String invalid : new String[] {"", "-1", "+1", "01", "1.0", "1,2", "600001", "999999999", "bad"})
            assertEquals(
                    400,
                    assertThrows(
                                    RequestException.class,
                                    () -> Context.incomingBudget(
                                            new Headers().add(Context.BUDGET_HEADER, invalid), local))
                            .status);
        assertThrows(
                RequestException.class,
                () -> Context.incomingBudget(
                        new Headers().add(Context.BUDGET_HEADER, "200").add("x-hoori-budget-ms", "100"), local));
        int[] calls = {0};
        ServiceBroker broker = broker((t, m, h, b, budget) -> {
            calls[0]++;
            assertTrue(budget.remainingMillis() <= 100);

            return json(200, "\"ok\"");
        });
        broker.accept(catalog(true, instance("recipes", 1, "http://recipes:8080", "get")));
        RequestBudget budget = RequestBudget.afterMillis(100);
        assertEquals("ok", call(broker, null, budget, GET, "x"));
        Thread.sleep(120);
        assertThrows(SocketTimeoutException.class, () -> call(broker, null, budget, GET, "x"));
        assertEquals(1, calls[0]);
        assertEquals(0, broker.admissionStats().active);
        assertEquals(0, broker.admissionStats().pending);
        assertThrows(IllegalStateException.class, () -> new Context(broker).invocation());
        assertEquals(1, calls[0]);
    }

    @Test
    void preparedGatewaySnapshotsAreAtomicBoundedAndExpire() throws Exception {
        ServiceConfig config = ServiceConfig.from("shopping", key -> switch (key) {
            case "HOORI_HEARTBEAT_MS" -> "100";
            case "HOORI_CATALOG_MAX_AGE_MS" -> "101";
            default -> null;
        });
        ServiceBroker broker =
                new ServiceBroker(shopping(), config, (t, m, h, b, budget) -> json(200, "{}"), JsonLimits.DEFAULT);
        broker.followPublicCatalog();
        Catalog.Entry first =
                new Catalog.Entry(new HttpEndpoint("GET", "/first", "", "application/json"), "recipes:read");
        Catalog initial = new Catalog("snapshots", 1, true, new Catalog.Instance[] {
            new Catalog.Instance("a", "recipes", 1, "http://a", new Catalog.Entry[] {first})
        });
        broker.accept(Json.encode(initial, Catalog.CODEC));
        ServiceBroker.View snapshot = broker.snapshot();
        assertEquals(1, snapshot.catalog.revision);
        assertEquals("/first", snapshot.routes[0].path);
        broker.accept(Json.encode(initial, Catalog.CODEC));
        assertSame(snapshot.routes, broker.snapshot().routes);
        Catalog.Entry changed =
                new Catalog.Entry(new HttpEndpoint("GET", "/first", "", "application/json"), "recipes:admin");
        Catalog conflict = new Catalog("snapshots", 2, true, new Catalog.Instance[] {
            initial.instances[0], new Catalog.Instance("b", "recipes", 1, "http://b", new Catalog.Entry[] {changed})
        });
        broker.accept(Json.encode(conflict, Catalog.CODEC));
        assertEquals(2, broker.snapshot().catalog.revision);
        assertEquals(0, broker.snapshot().routes.length);
        Catalog.Instance[] many = new Catalog.Instance[3];
        for (int i = 0; i < many.length; i++) {
            Catalog.Entry[] entries = new Catalog.Entry[i == 2 ? 1 : 128];
            for (int j = 0; j < entries.length; j++)
                entries[j] = new Catalog.Entry(
                        new HttpEndpoint("GET", "/many/" + i + "/" + j, "", "application/json"),
                        "extra-" + i + ":read");
            many[i] = new Catalog.Instance("extra-" + i, "extra-" + i, 1, "http://a", entries);
        }
        ServiceBroker.View retained = broker.snapshot();
        assertThrows(
                RuntimeException.class,
                () -> broker.accept(Json.encode(new Catalog("snapshots", 3, true, many), Catalog.CODEC)));
        assertSame(retained, broker.snapshot());
        Thread.sleep(120);
        assertSame(Catalog.EMPTY, broker.snapshot().catalog);
        assertEquals(0, broker.snapshot().routes.length);
    }

    @Test
    void providerContractsAreFrozenAndVersionsAreExplicit() {
        ServiceDefinition provider = definition("recipes");
        provider.endpoint(GET.endpoint, "recipes:read");
        provider.endpoint(RECOMMEND.endpoint, null);
        assertEquals("recipes:read", provider.endpoints.get(GET.endpoint.key()).permission);
        assertNull(provider.endpoints.get(RECOMMEND.endpoint.key()).permission);
        assertThrows(IllegalArgumentException.class, () -> provider.endpoint(GET.endpoint, null));
        provider.dependency("pantry", 1);
        provider.dependency("pantry", 1);
        assertThrows(IllegalArgumentException.class, () -> provider.dependency("pantry", 2));
        provider.frozen = true;
        assertThrows(IllegalStateException.class, () -> provider.endpoint(endpoint("extra"), null));
        assertEquals("DELETE /recipes/{id} - -", new HttpEndpoint("DELETE", "/recipes/{id}", "", "").key());
    }

    private static ServiceDefinition definition(String name, String... dependencies) {
        ServiceDefinition definition = new ServiceDefinition(name, 1);
        for (String dependency : dependencies) definition.dependency(dependency, 1);

        return definition;
    }

    private static ServiceDefinition shopping() {
        return definition("shopping", "recipes");
    }

    private record ExchangeCase<T>(String service, HttpEndpoint endpoint, JsonCodec<T> input, JsonCodec<T> output) {}

    private static <T> T call(
            ServiceBroker broker, Invocation invocation, RequestBudget budget, ExchangeCase<T> test, T value)
            throws IOException {
        return broker.call(
                invocation,
                budget,
                test.service,
                1,
                test.endpoint,
                limits -> new ClientRequest(test.endpoint.path()).body(value, test.input, limits),
                (response, limits) -> RemoteClient.json(response, test.output, limits));
    }

    private static HttpEndpoint endpoint(String path) {
        return new HttpEndpoint("POST", "/" + path, "application/json", "application/json");
    }

    private static ServiceBroker broker(ServiceBroker.Exchange exchange) {
        return new ServiceBroker(shopping(), ServiceConfig.from("shopping", key -> null), exchange, JsonLimits.DEFAULT);
    }

    private static String instance(String id, int version, String url, String... paths) {
        Catalog.Entry[] endpoints = new Catalog.Entry[paths.length];
        for (int i = 0; i < paths.length; i++) endpoints[i] = new Catalog.Entry(endpoint(paths[i]), null);

        return new String(
                Json.encode(new Catalog.Instance(id, "recipes", version, url, endpoints), Catalog.INSTANCE),
                StandardCharsets.UTF_8);
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

    private static Headers withView(Headers original, String scope) {
        Headers result = new Headers();
        for (int i = 0; i < original.size(); i++)
            if (!original.name(i).equalsIgnoreCase(Catalog.VIEW_HEADER))
                result.add(original.name(i), original.value(i));

        return result.add(Catalog.VIEW_HEADER, scope);
    }

    private static Headers version(Catalog catalog) {
        return new Headers()
                .add(Catalog.PROTOCOL_HEADER, Catalog.PROTOCOL)
                .add(Catalog.EPOCH_HEADER, catalog.epoch)
                .add(Catalog.REVISION_HEADER, Long.toString(catalog.revision));
    }
}
