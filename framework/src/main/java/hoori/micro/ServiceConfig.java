package hoori.micro;

/** Validated startup configuration; no per-request environment reads. */
public final class ServiceConfig {
    public final String name, bindAddress;
    public final int port, bodyBytes, serverConnections, clientConnections, clientPerOrigin;
    public final int requestTimeoutMillis, clientTimeoutMillis, clientIdleMillis, shutdownGraceMillis;

    private ServiceConfig(String defaultName, Environment env) {
        name = ServiceName.require(text(env, "HOORI_SERVICE_NAME", ServiceName.require(defaultName)));
        bindAddress = text(env, "HOORI_BIND_ADDRESS", "0.0.0.0");
        requireIpv4(bindAddress);
        port = number(env, "HOORI_PORT", 8080, 1, 65535);
        bodyBytes = number(env, "HOORI_BODY_BYTES", 65536, 1024, 16777216);
        serverConnections = number(env, "HOORI_SERVER_CONNECTIONS", 32, 1, 512);
        clientConnections = number(env, "HOORI_CLIENT_CONNECTIONS", 16, 1, 512);
        clientPerOrigin = number(env, "HOORI_CLIENT_PER_ORIGIN", Math.min(8, clientConnections), 1, clientConnections);
        requestTimeoutMillis = number(env, "HOORI_REQUEST_TIMEOUT_MS", 10000, 1, 600000);
        clientTimeoutMillis = number(env, "HOORI_CLIENT_TIMEOUT_MS", 2000, 1, 600000);
        clientIdleMillis = number(env, "HOORI_CLIENT_IDLE_MS", 5000, 1, 600000);
        shutdownGraceMillis = number(env, "HOORI_SHUTDOWN_GRACE_MS", 10000, 0, 600000);
    }

    public static ServiceConfig from(String defaultName, Environment env) {
        if (env == null) throw new NullPointerException("environment");
        return new ServiceConfig(defaultName, env);
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
        } catch (NumberFormatException invalid) { /* Report the key, not a potentially sensitive value. */ }
        throw new IllegalArgumentException("Out-of-range configuration: " + key);
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
