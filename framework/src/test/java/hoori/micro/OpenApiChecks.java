package hoori.micro;

import hoori.micro.openapi.ContractJson;
import hoori.micro.openapi.OpenApiDocument;
import hoori.rest.json.Json;
import hoori.rest.json.JsonLimits;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
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
        parameterOverrides(first);
        literalReferences();
        System.out.println("OpenAPI checks passed: " + checks + " assertions");
    }

    private static void parameterOverrides(OpenApiDocument first) {
        Map<String, Object> tree = first.document();
        Map<String, Object> item =
                ContractJson.object(ContractJson.object(tree.get("paths")).get("/widgets/{id}"));
        Map<String, Object> operation = ContractJson.object(item.get("get"));
        Object id = ContractJson.array(operation.get("parameters")).get(0);
        Map<String, Object> inherited = queryParameter("20"), local = queryParameter("10");
        item.put("parameters", List.of(id, inherited));
        operation.put("parameters", List.of(local));
        OpenApiDocument overridden = new OpenApiDocument(ContractJson.bytes(tree));
        var selected = overridden.operations().get(0);
        List<?> parameters =
                ContractJson.array(overridden.expandedOperation(selected).get("parameters"));
        check(parameters.size() == 2, "Override replaces just the matching inherited parameter");
        check(
                ContractJson.object(ContractJson.object(parameters.get(1)).get("schema"))
                        .get("default")
                        .equals(new ContractJson.NumberToken("10")),
                "Override default is effective");
        item.put("parameters", List.of(id, queryParameter("30")));
        OpenApiDocument shadowed = new OpenApiDocument(ContractJson.bytes(tree));
        check(selected.hash().equals(shadowed.operations().get(0).hash()), "Shadowed parameter does not affect hash");
        operation.put("parameters", List.of(queryParameter("11")));
        check(
                !selected.hash()
                        .equals(new OpenApiDocument(ContractJson.bytes(tree))
                                .operations()
                                .get(0)
                                .hash()),
                "Effective parameter affects hash");
        operation.put("parameters", List.of(local, local));
        rejected(() -> new OpenApiDocument(ContractJson.bytes(tree)), "Duplicate local parameter");
        operation.put("parameters", List.of(local));
        item.put("parameters", List.of(id, inherited, inherited));
        rejected(() -> new OpenApiDocument(ContractJson.bytes(tree)), "Duplicate inherited parameter");
        Map<String, Object> sameName = new LinkedHashMap<>(local);
        sameName.put("name", "id");
        item.put("parameters", List.of(id));
        operation.put("parameters", List.of(sameName));
        check(
                ContractJson.array(new OpenApiDocument(ContractJson.bytes(tree))
                                        .operation(selected)
                                        .get("parameters"))
                                .size()
                        == 2,
                "Parameter locations are distinct");
        ContractJson.object(tree.get("components")).put("parameters", Map.of("Old", inherited, "New", local));
        item.put("parameters", List.of(id, Map.of("$ref", "#/components/parameters/Old")));
        operation.put("parameters", List.of(Map.of("$ref", "#/components/parameters/New")));
        OpenApiDocument referenced = new OpenApiDocument(ContractJson.bytes(tree));
        check(
                selected.hash().equals(referenced.operations().get(0).hash()),
                "Referenced parameters use the same override");
        GatewayPublication publication = publication(referenced);
        Map<String, Object> aggregate = ContractJson.object(
                ContractJson.read(OpenApi.aggregate(publication, Map.of(referenced.hash(), referenced))
                        .get("public")));
        Map<String, Object> published = ContractJson.object(
                ContractJson.object(ContractJson.object(aggregate.get("paths")).get("/widgets/{id}"))
                        .get("get"));
        check(
                ContractJson.array(published.get("parameters")).equals(parameters),
                "Published override matches effective parameters");
    }

    private static Map<String, Object> queryParameter(String fallback) {
        return Map.of(
                "name",
                "limit",
                "in",
                "query",
                "required",
                false,
                "schema",
                Map.of("type", "integer", "format", "int32", "default", new ContractJson.NumberToken(fallback)));
    }

    private static void literalReferences() {
        Map<String, Object> tree = document(DOCUMENT).document();
        Map<String, Object> schemas =
                ContractJson.object(ContractJson.object(tree.get("components")).get("schemas"));
        Map<String, Object> widget = ContractJson.object(schemas.get("Widget"));
        Map<String, Object> properties = ContractJson.object(widget.get("properties"));
        properties.put("$ref", properties.remove("description"));
        widget.put("required", List.of("id", "$ref"));
        schemas.put("Detail", Map.of("type", "string"));
        properties.put("nested", Map.of("$ref", "#/components/schemas/Detail"));
        Map<String, Object> literal =
                Map.of("$ref", "#/components/schemas/Widget", "nested", Map.of("$ref", "literal application data"));
        for (String key : List.of("default", "const")) widget.put(key, literal);
        for (String key : List.of("enum", "examples")) widget.put(key, List.of(literal));
        Map<String, Object> operation = ContractJson.object(
                ContractJson.object(ContractJson.object(tree.get("paths")).get("/widgets/{id}"))
                        .get("get"));
        Map<String, Object> media = responseMedia(operation);
        media.put("example", literal);
        media.put("examples", Map.of("sample", Map.of("value", literal)));
        OpenApiDocument document = new OpenApiDocument(ContractJson.bytes(tree));
        Map<String, Object> expanded =
                document.expandedOperation(document.operations().get(0));
        Map<String, Object> expandedMedia = responseMedia(expanded);
        Map<String, Object> schema = ContractJson.object(expandedMedia.get("schema"));
        check(
                ContractJson.object(schema.get("properties")).containsKey("$ref"),
                "Literal property named $ref survives expansion");
        check(
                ContractJson.object(
                                ContractJson.object(schema.get("properties")).get("nested"))
                        .get("type")
                        .equals("string"),
                "Actual nested schema reference is expanded");
        check(
                literal.equals(expandedMedia.get("example"))
                        && literal.equals(schema.get("default"))
                        && literal.equals(schema.get("const"))
                        && List.of(literal).equals(schema.get("enum"))
                        && List.of(literal).equals(schema.get("examples")),
                "Example and schema values remain literal");
        check(
                literal.equals(ContractJson.object(ContractJson.object(expandedMedia.get("examples"))
                                .get("sample"))
                        .get("value")),
                "Example object value stays literal");
        OpenApiDocument canonical = new OpenApiDocument(document.bytes());
        check(
                document.hash().equals(canonical.hash())
                        && document.operations()
                                .get(0)
                                .hash()
                                .equals(canonical.operations().get(0).hash()),
                "Literal data fingerprints are deterministic");
        GatewayPublication publication = publication(document);
        Map<String, Object> aggregate =
                ContractJson.object(ContractJson.read(OpenApi.aggregate(publication, Map.of(document.hash(), document))
                        .get("public")));
        Map<String, Object> published = ContractJson.object(
                ContractJson.object(ContractJson.object(aggregate.get("paths")).get("/widgets/{id}"))
                        .get("get"));
        check(responseMedia(published).equals(expandedMedia), "Publication preserves literal reference data");
    }

    private static Map<String, Object> responseMedia(Map<String, Object> operation) {
        return ContractJson.object(ContractJson.object(ContractJson.object(
                                ContractJson.object(operation.get("responses")).get("200"))
                        .get("content"))
                .get("application/json"));
    }

    private static GatewayPublication publication(OpenApiDocument document) {
        Catalog catalog = new Catalog("checks", 1, true, new Catalog.Instance[] {instance("literal", document)});

        return GatewayPublication.build(catalog, Gateway.build(catalog));
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
