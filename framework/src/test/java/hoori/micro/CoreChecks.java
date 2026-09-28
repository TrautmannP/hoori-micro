package hoori.micro;

import java.util.HashMap;
import java.util.Map;

/** Dependency-free checks, also executable as an ordinary Hoori guest main. */
public final class CoreChecks {
    private static int assertions;

    public static void main(String[] args) {
        assertions = 0;
        namesAndDiscovery();
        originsAndTargets();
        configuration();
        System.out.println("Core checks passed: " + assertions + " assertions");
    }

    private static void namesAndDiscovery() {
        Map<String, String> values = new HashMap<>();
        ServiceDirectory directory = ServiceDirectory.from(values::get, "recipes", "ai-worker2");
        equal("http://recipes:8080", directory.originOf("recipes"));
        equal("http://ai-worker2:8080/v1/test?x=1", directory.target("ai-worker2", "/v1/test?x=1").toString());
        equal("HOORI_SERVICE_AI_WORKER2_URL", ServiceName.urlKey("ai-worker2"));
        for (String invalid : new String[] {"", "Recipes", "-a", "a-", "1a", "a_b", "a.b", "a/b", "ä"})
            reject(() -> ServiceDirectory.from(values::get, invalid));
        reject(() -> ServiceDirectory.from(values::get, "recipes", "recipes"));
        reject(() -> directory.originOf("unlisted"));
        reject(() -> directory.target("unlisted", "/"));
        String[] detached = directory.names();
        detached[0] = "evil";
        equal("recipes", directory.names()[0]);
        String[] input = {"recipes"};
        ServiceDirectory copy = ServiceDirectory.from(values::get, input);
        input[0] = "changed";
        equal("http://recipes:8080", copy.originOf("recipes"));
        values.put("HOORI_SERVICE_AI_WORKER2_URL", "https://api.example:8443/");
        ServiceDirectory overridden = ServiceDirectory.from(values::get, "ai-worker2");
        equal("https://api.example:8443/v1", overridden.target("ai-worker2", "/v1").toString());
        values.put("HOORI_SERVICE_AI_WORKER2_URL", "http://later.example");
        equal("https://api.example:8443", overridden.originOf("ai-worker2"));
        String[] tooMany = new String[33];
        for (int i = 0; i < tooMany.length; i++) tooMany[i] = "service-" + i;
        reject(() -> ServiceDirectory.from(values::get, tooMany));
        String max = "a".repeat(63);
        equal(max, ServiceName.require(max));
        reject(() -> ServiceName.require(max + "a"));
        equal(0, ServiceDirectory.from(values::get).names().length);
    }

    private static void originsAndTargets() {
        for (String invalid : new String[] {"", " https://host", "ftp://host", "HTTP://host", "http://u:p@host",
                "http://host/base", "http://host?x=1", "http://host#frag", "http://host:0", "http://host:65536",
                "http://host:-1", "http://bad_host", "http://", "file:///tmp/a", "//other", "http://host/../"})
            reject(() -> ServiceDirectory.from(key -> invalid, "recipes"));
        for (String valid : new String[] {"http://localhost:8090", "https://api.example", "http://127.0.0.1:8081",
                "http://[::1]:8090", "https://api.example:65535"})
            equal(valid, ServiceDirectory.from(key -> valid, "recipes").originOf("recipes"));
        ServiceDirectory d = ServiceDirectory.from(key -> null, "recipes");
        for (String invalid : new String[] {"", "v1/a", "http://evil/x", "//evil/x", "/x#frag", "/a b", "/ä",
                "/a\nb", "/a\rb", "/a\tb", "/x%ZZ", "/x%", "/x\\evil"})
            reject(() -> d.target("recipes", invalid));
        equal("http://recipes:8080/", d.target("recipes", "/").toString());
        equal("/v1/a%2Fb", d.target("recipes", "/v1/a%2Fb?q=a%20b").getRawPath());
        equal("recipes", d.target("recipes", "/%2F%2Fevil").getHost());
        equal("recipes", d.target("recipes", "/x?next=http://other").getHost());
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
        equal(10000, c.requestTimeoutMillis);
        env.put("HOORI_CLIENT_CONNECTIONS", "2");
        equal(2, ServiceConfig.from("shopping", env::get).clientPerOrigin);
        env.put("HOORI_CLIENT_PER_ORIGIN", "3");
        reject(() -> ServiceConfig.from("shopping", env::get));
        env.clear();
        for (String key : new String[] {"HOORI_PORT", "HOORI_BODY_BYTES", "HOORI_SERVER_CONNECTIONS",
                "HOORI_CLIENT_CONNECTIONS", "HOORI_CLIENT_PER_ORIGIN", "HOORI_REQUEST_TIMEOUT_MS",
                "HOORI_CLIENT_TIMEOUT_MS", "HOORI_CLIENT_IDLE_MS"}) {
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
        for (String bad : new String[] {"localhost", "::1", "256.0.0.1", "1.2.3", "1.2.3.4.5", "1..2.3", "01.2.3.4", ""}) {
            env.put("HOORI_BIND_ADDRESS", bad);
            reject(() -> ServiceConfig.from("shopping", env::get));
        }
        env.put("HOORI_BIND_ADDRESS", "127.0.0.1");
        equal("127.0.0.1", ServiceConfig.from("shopping", env::get).bindAddress);
        env.put("HOORI_SERVICE_NAME", "another-service");
        equal("another-service", ServiceConfig.from("shopping", env::get).name);
        env.put("HOORI_SERVICE_NAME", " another-service");
        reject(() -> ServiceConfig.from("shopping", env::get));
    }

    private static void reject(Runnable action) {
        assertions++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
