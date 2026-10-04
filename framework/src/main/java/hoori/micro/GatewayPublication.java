package hoori.micro;

import hoori.micro.openapi.ContractJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable documentation selection, built from exactly the catalog used for gateway routing. */
final class GatewayPublication {
    record Selection(Gateway.Route route, String hash, List<Catalog.Instance> providers) {}

    final String id;
    final byte[] bytes;
    final List<Selection> selections;
    final boolean complete;

    private GatewayPublication(Catalog catalog, Gateway.Route[] routes) {
        List<Object> published = new ArrayList<>(), missing = new ArrayList<>(), withheld = new ArrayList<>();
        List<Selection> selected = new ArrayList<>();
        int work = 0;
        for (Gateway.Route route : routes) {
            String hash = null;
            for (Catalog.Instance instance : catalog.instances) {
                if ((++work & 63) == 0) Thread.yield();

                if (matches(route, instance) && (hash == null || instance.contractHash.compareTo(hash) < 0))
                    hash = instance.contractHash;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", route.service);
            row.put("version", route.version);
            row.put("method", route.contract.method());
            row.put("path", route.path);
            row.put("permission", route.permission);

            if (hash == null) {
                missing.add(row);
                continue;
            }

            row.put("apiGroup", route.apiGroup);
            row.put("contractHash", hash);
            row.put("operationHash", route.operationHash);
            published.add(row);
            List<Catalog.Instance> providers = new ArrayList<>();
            for (Catalog.Instance instance : catalog.instances)
                if (hash.equals(instance.contractHash) && matches(route, instance)) providers.add(instance);
            selected.add(new Selection(route, hash, List.copyOf(providers)));
        }
        // Report only bounded route identities, never schemas or internal provider addresses.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Catalog.Instance instance : catalog.instances)
            for (Catalog.Entry entry : instance.endpoints) {
                if (entry.permission == null) continue;

                boolean accepted = false;
                for (Gateway.Route offered : routes) {
                    if ((++work & 63) == 0) Thread.yield();

                    if (offered.matches(instance, entry)) accepted = true;
                }
                String key = entry.contract.method() + " " + entry.contract.path();

                if (!accepted && seen.add(key)) {
                    if (withheld.size() == Gateway.MAX_ROUTES)
                        throw new IllegalArgumentException("Publication conflict limit");

                    withheld.add(Map.of("method", entry.contract.method(), "path", entry.contract.path()));
                }
            }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("profile", "hoori-publication-1");
        manifest.put("catalogEpoch", catalog.epoch);
        manifest.put("catalogRevision", catalog.revision);
        manifest.put("registryComplete", catalog.complete);
        complete = missing.isEmpty();
        manifest.put("complete", complete);
        manifest.put("operations", published);
        manifest.put("undocumented", missing);
        manifest.put("withheld", withheld);
        id = ContractJson.hash(ContractJson.bytes(manifest));
        manifest.put("publicationId", id);
        bytes = ContractJson.bytes(manifest);
        selections = List.copyOf(selected);
    }

    private static boolean matches(Gateway.Route route, Catalog.Instance instance) {
        if (instance.contractHash == null
                || !route.service.equals(instance.service)
                || route.version != instance.version) return false;

        for (Catalog.Entry entry : instance.endpoints)
            if (entry.permission != null && route.matches(instance, entry)) return true;

        return false;
    }

    static GatewayPublication build(Catalog catalog, Gateway.Route[] routes) {
        try {
            return new GatewayPublication(catalog, routes);
        } catch (IllegalArgumentException | hoori.rest.json.JsonException limited) {
            // Documentation limits must not take the business routing snapshot down.
            return null;
        }
    }
}
