package hoori.micro;

import hoori.micro.openapi.ContractJson;
import hoori.micro.openapi.OpenApiDocument;
import hoori.rest.json.Json;
import hoori.rest.json.JsonLimits;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Same bounded contract/publication checks on the host and the actual guest. */
public final class OpenApiChecks {
    private static int checks;
    public static final String DOCUMENT = """
            {"openapi":"3.1.0","info":{"title":"Widgets","version":"1"},"x-hoori-service-version":1,
             "paths":{"/widgets/{id}":{"get":{"operationId":"widgetsGet","x-hoori-permission":"widgets:read",
              "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64","exclusiveMinimum":0}}],
              "responses":{"200":{"description":"Found","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Widget"}}}}}}}},
             "components":{"schemas":{"Widget":{"type":"object","properties":{"id":{"type":"integer","format":"int64"},
              "description":{"type":"string"}},"required":["id","description"]}}}}
            """;

    public static void main(String[] args) {
        checks = 0;
        OpenApiDocument first = document(DOCUMENT);
        check(first.hash().length() == 64 && first.bytes().length > 0, "Artifact identity");
        check(
                document(new String(first.bytes(), StandardCharsets.UTF_8))
                        .hash()
                        .equals(first.hash()),
                "Canonical identity");
        try {
            byte[] resource = ContractJson.source(new java.io.ByteArrayInputStream(first.bytes()));
            check(ContractJson.hash(resource).equals(first.hash()), "Bounded resource loading on the guest");
        } catch (java.io.IOException failed) {
            throw new AssertionError(failed);
        }
        String exact = "{\"integer\":9223372036854775807,\"decimal\":1.000000000000000001}";
        String encoded = new String(
                ContractJson.bytes(ContractJson.read(exact.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
        check(
                encoded.contains("9223372036854775807") && encoded.contains("1.000000000000000001"),
                "Numbers stay exact");
        rejected(() -> ContractJson.read("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)), "Duplicate member");
        rejected(
                () -> document(DOCUMENT.replace("#/components/schemas/Widget", "https://example.invalid/schema")),
                "External reference");
        rejected(
                () -> document(DOCUMENT.replace(
                        "\"description\":{\"type\":\"string\"}",
                        "\"description\":{\"$ref\":\"#/components/schemas/Widget\"}")),
                "Recursive schema");
        rejected(
                () -> document(
                        DOCUMENT.replace("\"type\":\"string\"", "\"type\":\"string\",\"unevaluatedProperties\":false")),
                "Unsupported schema");
        rejected(() -> ContractJson.read(new byte[65537]), "Byte limit");
        OpenApiDocument changed = document(
                DOCUMENT.replace("\"description\":{\"type\":\"string\"}", "\"description\":{\"type\":\"boolean\"}"));
        check(
                !first.operations()
                        .get(0)
                        .hash()
                        .equals(changed.operations().get(0).hash()),
                "Property named description is contract data");

        Map<String, Object> expanded = first.document();
        Map<String, Object> paths = ContractJson.object(expanded.get("paths"));
        Map<String, Object> extra =
                new LinkedHashMap<>(first.operation(first.operations().get(0)));
        extra.put("operationId", "widgetsLatest");
        extra.put("parameters", java.util.List.of());
        paths.put("/widgets/latest", Map.of("get", extra));
        OpenApiDocument next = new OpenApiDocument(ContractJson.bytes(expanded));
        boolean preserved = false;
        for (var operation : next.operations())
            if (operation.key().equals(first.operations().get(0).key()))
                preserved = first.operations().get(0).hash().equals(operation.hash());
        check(preserved, "Added operation preserves old hash");
        Catalog catalog = new Catalog(
                "registry-test", 3, true, new Catalog.Instance[] {instance("old", first), instance("new", next)});
        Catalog decoded =
                Json.decode(Json.encode(catalog, Catalog.CODEC, JsonLimits.DEFAULT), Catalog.CODEC, JsonLimits.DEFAULT);
        check(decoded.instances[0].sameMetadata(catalog.instances[0]), "Catalog metadata roundtrip");
        check(!instance("old", first).sameMetadata(instance("old", changed)), "Registration notices schema change");
        Gateway.Route[] routes = Gateway.build(catalog);
        check(routes.length == 2, "Compatible rolling routing");
        GatewayPublication publication = GatewayPublication.build(catalog, routes);
        check(
                publication != null && publication.complete && publication.selections.size() == 2,
                "Publication matches routing");
        Map<String, byte[]> groups = OpenApi.aggregate(publication, Map.of(first.hash(), first, next.hash(), next));
        Map<String, Object> publicApi = ContractJson.object(ContractJson.read(groups.get("public")));
        check(ContractJson.object(publicApi.get("paths")).size() == routes.length, "All accepted routes documented");
        check(!new String(groups.get("public"), StandardCharsets.UTF_8).contains("$ref"), "Local references inlined");
        check(publication.id.equals(publicApi.get("x-hoori-publication-id")), "Publication identity propagated");
        rejected(
                () -> OpenApi.aggregate(publication, Map.of(first.hash(), changed, next.hash(), changed)),
                "Hash mismatch");
        Catalog conflicting = new Catalog(
                "registry-test", 4, true, new Catalog.Instance[] {instance("old", first), instance("bad", changed)});
        check(Gateway.build(conflicting).length == 0, "Incompatible rolling operation withheld");
        GatewayPublication rejected = GatewayPublication.build(conflicting, Gateway.build(conflicting));
        check(
                ContractJson.array(ContractJson.object(ContractJson.read(rejected.bytes))
                                        .get("withheld"))
                                .size()
                        == 1,
                "Conflict is visible in manifest");
        Catalog.Instance bare = new Catalog.Instance("bare", "widgets", 1, "http://bare:8080", new Catalog.Entry[] {
            new Catalog.Entry(firstEndpoint(), "widgets:read")
        });
        Catalog undocumented = new Catalog("registry-test", 5, true, new Catalog.Instance[] {bare});
        check(
                !GatewayPublication.build(undocumented, Gateway.build(undocumented)).complete,
                "Undocumented route is explicit");
        Catalog.Instance internal =
                new Catalog.Instance("internal", "widgets", 1, "http://internal:8080", new Catalog.Entry[] {
                    new Catalog.Entry(firstEndpoint(), null)
                });
        Catalog mixed =
                new Catalog("registry-test", 6, true, new Catalog.Instance[] {internal, instance("public", first)});
        Gateway.Route published = Gateway.build(mixed)[0];
        check(
                mixed.select("widgets", 1, firstEndpoint().key(), 0, published)
                        .id
                        .equals("public"),
                "Gateway selection honors published contract");
        System.out.println("OpenAPI checks passed: " + checks + " assertions");
    }

    private static HttpEndpoint firstEndpoint() {
        return new HttpEndpoint("GET", "/widgets/{id}", "", "application/json");
    }

    static Catalog.Instance instance(String id, OpenApiDocument document) {
        Catalog.Entry[] endpoints = new Catalog.Entry[document.operations().size()];
        for (int i = 0; i < endpoints.length; i++) {
            var operation = document.operations().get(i);
            endpoints[i] = new Catalog.Entry(
                    new HttpEndpoint(operation.method(), operation.path(), "", "application/json"),
                    operation.permission(),
                    operation.hash());
        }

        return new Catalog.Instance(
                id, "widgets", 1, "http://" + id + ":8080", endpoints, document.hash(), document.group());
    }

    private static OpenApiDocument document(String text) {
        return new OpenApiDocument(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);

        checks++;
    }

    private static void rejected(Runnable work, String message) {
        try {
            work.run();
        } catch (IllegalArgumentException | hoori.rest.json.JsonException expected) {
            checks++;

            return;
        }
        throw new AssertionError(message);
    }
}
