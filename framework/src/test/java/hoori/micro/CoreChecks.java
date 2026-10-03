package hoori.micro;

import java.util.HashMap;
import java.util.Map;

/** Dependency-free checks, also executable as an ordinary Hoori guest main. */
public final class CoreChecks {
    private static int assertions;

    public static void main(String[] args) {
        assertions = 0;
        names();
        origins();
        configuration();
        System.out.println("Core checks passed: " + assertions + " assertions");
    }

    private static void names() {
        for (String invalid : new String[] {"", "Recipes", "-a", "a-", "1a", "a_b", "a.b", "a/b", "ä", null})
            reject(() -> ServiceName.require(invalid));
        String max = letters(63);
        equal(max, ServiceName.require(max));
        reject(() -> ServiceName.require(max + "a"));
        equal("ai-worker2", ServiceName.qualified("ai-worker2.get")[0]);
        equal("get", ServiceName.qualified("ai-worker2.get")[1]);
        for (String invalid : new String[] {"recipes", "recipes.", ".get", "a.b.c", "Recipes.get", "recipes.Get"})
            reject(() -> ServiceName.qualified(invalid));
    }

    private static void origins() {
        for (String invalid : new String[] {
            "",
            " https://host",
            "ftp://host",
            "HTTP://host",
            "http://u:p@host",
            "http://host/base",
            "http://host?x=1",
            "http://host#frag",
            "http://host:0",
            "http://host:65536",
            "http://host:-1",
            "http://bad_host",
            "http://",
            "file:///tmp/a",
            "//other",
            "http://host/../"
        }) reject(() -> ServiceConfig.from("shopping", key -> key.equals("HOORI_REGISTRY_URL") ? invalid : null));
        for (String valid : new String[] {
            "http://localhost:8090",
            "https://api.example",
            "http://127.0.0.1:8081",
            "http://[::1]:8090",
            "https://api.example:65535"
        }) {
            equal(
                    valid,
                    ServiceConfig.from("shopping", key -> key.equals("HOORI_REGISTRY_URL") ? valid : null).registryUrl);
            equal(
                    valid,
                    ServiceConfig.from("shopping", key -> key.equals("HOORI_ADVERTISE_URL") ? valid : null)
                            .advertiseUrl);
        }
        equal(
                "https://api.example:8443",
                ServiceConfig.from(
                                "shopping",
                                key -> key.equals("HOORI_REGISTRY_URL") ? "https://api.example:8443/" : null)
                        .registryUrl);
    }

    private static void configuration() {
        Map<String, String> env = new HashMap<>();
        ServiceConfig c = ServiceConfig.from("shopping", env::get);
        equal("shopping", c.name);
        equal("0.0.0.0", c.bindAddress);
        equal(8080, c.port);
        equal(65536, c.bodyBytes);
        equal(16, c.clientConnections);
        equal(8, c.clientPerOrigin);
        equal(2000, c.clientTimeoutMillis);
        equal(0, c.clientPendingAcquires);
        equal(16, c.incomingCalls);
        equal(0, c.incomingPendingCalls);
        equal(8, c.outgoingCalls);
        equal(8, c.outgoingPendingCalls);
        equal(1000, c.controlTimeoutMillis);
        equal(10000, c.requestTimeoutMillis);
        equal("http://registry:8080", c.registryUrl);
        equal("http://shopping:8080", c.advertiseUrl);
        equal(2000, c.heartbeatMillis);
        equal(6000, c.registryTtlMillis);
        equal(30000, c.catalogMaxAgeMillis);
        equal(true, c.instanceId.startsWith("shopping-"));
        env.put("HOSTNAME", "a1b2c3");
        env.put("HOORI_PORT", "8081");
        equal("http://a1b2c3:8081", ServiceConfig.from("shopping", env::get).advertiseUrl);
        env.put("HOORI_INSTANCE_ID", "shopping-1");
        equal("shopping-1", ServiceConfig.from("shopping", env::get).instanceId);
        env.put("HOORI_INSTANCE_ID", "Shopping_1");
        reject(() -> ServiceConfig.from("shopping", env::get));
        equal(true, ServiceConfig.from(letters(63), key -> null).instanceId.length() <= 63);
        env.clear();
        env.put("HOORI_HEARTBEAT_MS", "5000");
        env.put("HOORI_REGISTRY_TTL_MS", "5000");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.clear();
        env.put("HOORI_HEARTBEAT_MS", "5000");
        env.put("HOORI_CATALOG_MAX_AGE_MS", "4000");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.clear();
        env.put("HOORI_CLIENT_CONNECTIONS", "2");
        equal(2, ServiceConfig.from("shopping", env::get).clientPerOrigin);
        env.put("HOORI_CLIENT_PER_ORIGIN", "3");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.clear();
        env.put("HOORI_SERVER_CONNECTIONS", "2");
        equal(2, ServiceConfig.from("shopping", env::get).incomingCalls);
        env.put("HOORI_INCOMING_PENDING_CALLS", "1");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.put("HOORI_INCOMING_CALLS", "1");
        equal(1, ServiceConfig.from("shopping", env::get).incomingPendingCalls);
        env.clear();
        env.put("HOORI_CLIENT_CONNECTIONS", "2");
        env.put("HOORI_OUTGOING_CALLS", "3");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.put("HOORI_OUTGOING_CALLS", "1");
        env.put("HOORI_OUTGOING_PENDING_CALLS", "0");
        equal(0, ServiceConfig.from("shopping", env::get).outgoingPendingCalls);
        env.put("HOORI_OUTGOING_PENDING_CALLS", "513");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.clear();
        env.put("HOORI_CLIENT_PENDING_ACQUIRES", "0");
        equal(0, ServiceConfig.from("shopping", env::get).clientPendingAcquires);
        for (String invalid : new String[] {"-1", "4097"}) {
            env.put("HOORI_CLIENT_PENDING_ACQUIRES", invalid);
            reject(() -> ServiceConfig.from("shopping", env::get));
        }
        env.clear();
        env.put("HOORI_HEARTBEAT_MS", "100");
        env.put("HOORI_REGISTRY_TTL_MS", "600");
        equal(300, ServiceConfig.from("shopping", env::get).controlTimeoutMillis);
        env.put("HOORI_CONTROL_TIMEOUT_MS", "301");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.clear();
        for (String key : new String[] {
            "HOORI_PORT",
            "HOORI_BODY_BYTES",
            "HOORI_SERVER_CONNECTIONS",
            "HOORI_CLIENT_CONNECTIONS",
            "HOORI_CLIENT_PER_ORIGIN",
            "HOORI_REQUEST_TIMEOUT_MS",
            "HOORI_CLIENT_TIMEOUT_MS",
            "HOORI_WORK_TIMEOUT_MS",
            "HOORI_CONTROL_TIMEOUT_MS",
            "HOORI_INCOMING_CALLS",
            "HOORI_OUTGOING_CALLS",
            "HOORI_CLIENT_IDLE_MS"
        }) {
            env.put(key, "0");
            reject(() -> ServiceConfig.from("shopping", env::get));
            env.clear();
        }
        for (String invalid : new String[] {"-1", "+1", "", " 8080", "8080 ", "65536", "99999999999999", "NaN"}) {
            env.put("HOORI_PORT", invalid);
            reject(() -> ServiceConfig.from("shopping", env::get));
        }
        env.clear();
        env.put("HOORI_SHUTDOWN_GRACE_MS", "0");
        equal(0, ServiceConfig.from("shopping", env::get).shutdownGraceMillis);
        for (String bad :
                new String[] {"localhost", "::1", "256.0.0.1", "1.2.3", "1.2.3.4.5", "1..2.3", "01.2.3.4", ""}) {
            env.put("HOORI_BIND_ADDRESS", bad);
            reject(() -> ServiceConfig.from("shopping", env::get));
        }
        env.put("HOORI_BIND_ADDRESS", "127.0.0.1");
        equal("127.0.0.1", ServiceConfig.from("shopping", env::get).bindAddress);
        reject(() -> ServiceConfig.from("Shopping", key -> null));
    }

    /** String.repeat is not in Hoori's guest classlib. */
    private static String letters(int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) value.append('a');

        return value.toString();
    }

    private static void reject(Runnable action) {
        assertions++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void equal(Object expected, Object actual) {
        assertions++;

        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
