package hoori.micro;

import java.net.URI;

/**
 * Immutable, bounded logical-name directory. Docker/orchestrator DNS resolves the host;
 * this class never resolves or caches IP addresses and never enumerates containers.
 */
public final class ServiceDirectory {
    public static final int MAX_SERVICES = 32;
    private final String[] names;
    private final String[] origins;

    private ServiceDirectory(String[] names, String[] origins) {
        this.names = names;
        this.origins = origins;
    }

    public static ServiceDirectory from(Environment environment, String... dependencies) {
        if (environment == null || dependencies == null) throw new NullPointerException();
        if (dependencies.length > MAX_SERVICES)
            throw new IllegalArgumentException("At most 32 declared service dependencies");
        String[] names = new String[dependencies.length];
        String[] origins = new String[dependencies.length];
        for (int i = 0; i < dependencies.length; i++) {
            String name = ServiceName.require(dependencies[i]);
            for (int j = 0; j < i; j++) {
                if (names[j].equals(name)) throw new IllegalArgumentException("Duplicate service dependency");
            }
            String key = ServiceName.urlKey(name);
            String configured = environment.get(key);
            names[i] = name;
            origins[i] = origin(configured == null ? "http://" + name + ":8080" : configured, key);
        }
        return new ServiceDirectory(names, origins);
    }

    /** Requires an origin-form target, never an absolute or network-path URI. */
    public URI target(String service, String pathAndQuery) {
        if (pathAndQuery == null || pathAndQuery.isEmpty() || pathAndQuery.charAt(0) != '/'
                || pathAndQuery.startsWith("//") || pathAndQuery.indexOf('#') >= 0
                || pathAndQuery.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Expected /path with an optional query, not a URL");
        for (int i = 0; i < pathAndQuery.length(); i++) {
            char c = pathAndQuery.charAt(i);
            if (c <= 32 || c >= 127) throw new IllegalArgumentException("URI target must be encoded ASCII");
        }
        // Concatenation after validation, NOT URI.resolve: callers cannot replace the origin.
        return URI.create(originOf(service) + pathAndQuery);
    }

    public String originOf(String service) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(service)) return origins[i];
        throw new IllegalArgumentException("Undeclared service dependency");
    }

    public String[] names() { return names.clone(); }

    private static String origin(String value, String key) {
        if (value.length() > 2048) throw new IllegalArgumentException("Invalid origin in " + key);
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid origin in " + key); }
        String scheme = uri.getScheme();
        String path = uri.getRawPath();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || path == null || !(path.isEmpty() || path.equals("/")))
            throw new IllegalArgumentException("Expected an HTTP(S) origin without credentials/path in " + key);
        return scheme + "://" + uri.getRawAuthority();
    }
}
