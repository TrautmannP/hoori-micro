package hoori.micro;

import java.net.URI;

/** Validated startup configuration; no per-request environment reads. */
public final class ServiceConfig {
    public final String name, bindAddress, instanceId, registryUrl, advertiseUrl;
    public final int port, bodyBytes, serverConnections, clientConnections, clientPerOrigin, clientPendingAcquires;
    public final int requestTimeoutMillis, clientTimeoutMillis, clientIdleMillis, shutdownGraceMillis;
    public final int heartbeatMillis, registryTtlMillis, catalogMaxAgeMillis, controlTimeoutMillis;
    public final int incomingCalls, incomingPendingCalls, outgoingCalls, outgoingPendingCalls;

    private ServiceConfig(String serviceName, Environment env) {
        name = ServiceName.require(serviceName);
        bindAddress = text(env, "HOORI_BIND_ADDRESS", "0.0.0.0");
        requireIpv4(bindAddress);
        port = number(env, "HOORI_PORT", 8080, 1, 65535);
        bodyBytes = number(env, "HOORI_BODY_BYTES", 65536, 1024, 16777216);
        serverConnections = number(env, "HOORI_SERVER_CONNECTIONS", 32, 1, 512);
        clientConnections = number(env, "HOORI_CLIENT_CONNECTIONS", 16, 1, 512);
        clientPerOrigin = number(env, "HOORI_CLIENT_PER_ORIGIN", Math.min(8, clientConnections), 1, clientConnections);
        clientPendingAcquires = number(env, "HOORI_CLIENT_PENDING_ACQUIRES", 0, 0, 4096);
        incomingCalls = number(env, "HOORI_INCOMING_CALLS", Math.min(16, serverConnections), 1, serverConnections);
        incomingPendingCalls = number(env, "HOORI_INCOMING_PENDING_CALLS", 0, 0, serverConnections - incomingCalls);
        outgoingCalls = number(env, "HOORI_OUTGOING_CALLS", clientPerOrigin, 1, clientConnections);
        outgoingPendingCalls = number(env, "HOORI_OUTGOING_PENDING_CALLS", outgoingCalls, 0, 512);
        requestTimeoutMillis = number(env, "HOORI_REQUEST_TIMEOUT_MS", 10000, 1, 600000);
        clientTimeoutMillis = number(env, "HOORI_CLIENT_TIMEOUT_MS", 2000, 1, 600000);
        clientIdleMillis = number(env, "HOORI_CLIENT_IDLE_MS", 5000, 1, 600000);
        shutdownGraceMillis = number(env, "HOORI_SHUTDOWN_GRACE_MS", 10000, 0, 600000);
        heartbeatMillis = number(env, "HOORI_HEARTBEAT_MS", 2000, 100, 60000);
        registryTtlMillis = number(env, "HOORI_REGISTRY_TTL_MS", 3 * heartbeatMillis, heartbeatMillis + 1, 600000);
        controlTimeoutMillis = number(
                env,
                "HOORI_CONTROL_TIMEOUT_MS",
                Math.min(1000, registryTtlMillis / 2),
                1,
                Math.min(10000, registryTtlMillis / 2));
        catalogMaxAgeMillis = number(env, "HOORI_CATALOG_MAX_AGE_MS", 30000, heartbeatMillis + 1, 3600000);
        registryUrl = origin(text(env, "HOORI_REGISTRY_URL", "http://registry:8080"), "HOORI_REGISTRY_URL");
        String host = env.get("HOSTNAME");
        advertiseUrl = origin(
                text(
                        env,
                        "HOORI_ADVERTISE_URL",
                        "http://" + (host == null || host.isEmpty() ? name : host) + ":" + port),
                "HOORI_ADVERTISE_URL");
        instanceId = ServiceName.require(text(
                env,
                "HOORI_INSTANCE_ID",
                (name.length() > 43 ? name.substring(0, 43) : name) + "-"
                        + Long.toString((System.nanoTime() ^ System.currentTimeMillis() << 20) & Long.MAX_VALUE)));
    }

    public static ServiceConfig from(String serviceName, Environment env) {
        if (env == null) throw new NullPointerException("environment");

        return new ServiceConfig(serviceName, env);
    }

    private static String text(Environment env, String key, String fallback) {
        String value = env.get(key);

        if (value == null) return fallback;

        if (value.isEmpty() || !value.equals(value.trim()))
            throw new IllegalArgumentException("Empty or padded configuration: " + key);

        return value;
    }

    private static int number(Environment env, String key, int fallback, int min, int max) {
        String value = env.get(key);

        if (value == null) return fallback;

        try {
            if (value.isEmpty()) throw new NumberFormatException();

            for (int i = 0; i < value.length(); i++)
                if (value.charAt(i) < '0' || value.charAt(i) > '9') throw new NumberFormatException();
            int result = Integer.parseInt(value);

            if (result >= min && result <= max) return result;
        } catch (NumberFormatException invalid) {
            /* Report the key, not a potentially sensitive value. */
        }
        throw new IllegalArgumentException("Out-of-range configuration: " + key);
    }

    /** HTTP(S) origin without credentials, path, query or fragment; returned without trailing slash. */
    static String origin(String value, String key) {
        if (value.length() > 2048) throw new IllegalArgumentException("Invalid origin in " + key);

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid origin in " + key);
        }
        String scheme = uri.getScheme();
        String path = uri.getRawPath();

        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || path == null
                || !(path.isEmpty() || path.equals("/")))
            throw new IllegalArgumentException("Expected an HTTP(S) origin without credentials/path in " + key);

        return scheme + "://" + uri.getRawAuthority();
    }

    private static void requireIpv4(String value) {
        int start = 0, count = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '.') {
                int length = i - start;

                if (length == 0 || length > 3 || length > 1 && value.charAt(start) == '0')
                    throw new IllegalArgumentException("HOORI_BIND_ADDRESS requires numeric IPv4");

                int number = 0;
                for (int j = start; j < i; j++) {
                    char c = value.charAt(j);

                    if (c < '0' || c > '9') throw new IllegalArgumentException("Invalid bind address");

                    number = number * 10 + c - '0';
                }

                if (number > 255) throw new IllegalArgumentException("Invalid bind address");

                start = i + 1;
                count++;
            }
        }

        if (count != 4) throw new IllegalArgumentException("HOORI_BIND_ADDRESS requires numeric IPv4");
    }
}
