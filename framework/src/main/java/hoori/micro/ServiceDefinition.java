package hoori.micro;

import java.util.LinkedHashMap;

/** Generated startup metadata, frozen before any listener, readiness or registration. */
final class ServiceDefinition {
    static final int MAX_ENDPOINTS = 128, MAX_DEPENDENCIES = 32, MAX_VERSION = 9999;
    final String name;
    final int version;
    final LinkedHashMap<String, Catalog.Entry> endpoints = new LinkedHashMap<>();
    final LinkedHashMap<String, Integer> dependencies = new LinkedHashMap<>();
    boolean frozen;
    String contractHash, apiGroup;

    ServiceDefinition(String name, int version) {
        this.name = ServiceName.require(name);

        if (version < 1 || version > MAX_VERSION) throw new IllegalArgumentException("Version 1-9999");

        this.version = version;
    }

    void dependency(String name, int version) {
        mutable();
        ServiceName.require(name);

        if (version < 1 || version > MAX_VERSION) throw new IllegalArgumentException("Version 1-9999");

        Integer previous = dependencies.get(name);

        if (previous != null) {
            if (previous != version) throw new IllegalArgumentException("Conflicting service dependency versions");

            return;
        }

        if (dependencies.size() == MAX_DEPENDENCIES) throw new IllegalArgumentException("At most 32 dependencies");

        dependencies.put(name, version);
    }

    void endpoint(HttpEndpoint endpoint, String permission) {
        mutable();

        if (permission != null) Gateway.permission(name, permission);

        if (endpoints.size() == MAX_ENDPOINTS || endpoints.containsKey(endpoint.key()))
            throw new IllegalArgumentException("Duplicate endpoint or endpoint limit");

        endpoints.put(endpoint.key(), new Catalog.Entry(endpoint, permission));
    }

    Catalog.Entry endpoint(String method, String template) {
        for (Catalog.Entry entry : endpoints.values())
            if (entry.contract.method().equals(method) && entry.contract.path().equals(template)) return entry;

        return null;
    }

    void mutable() {
        if (frozen) throw new IllegalStateException("Application metadata is frozen");
    }
}
