package hoori.micro.openapi;

import static hoori.micro.openapi.ContractJson.array;
import static hoori.micro.openapi.ContractJson.object;
import static hoori.micro.openapi.ContractJson.text;

import hoori.micro.HttpEndpoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One service-owned OpenAPI 3.1 JSON artifact, with a deliberately finite executable profile. */
public final class OpenApiDocument {
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options");
    private final byte[] bytes;
    private final String hash;
    private final String group;
    private final int serviceVersion;
    private final Map<String, Object> root;
    private final List<Operation> operations = new ArrayList<>();
    private int schemaNodes;

    public record Operation(String method, String path, String id, String permission, String hash) {
        public String key() {
            return method + " " + path;
        }
    }

    public OpenApiDocument(byte[] source) {
        root = object(ContractJson.read(source));
        keys(root, "openapi", "info", "paths", "components", "tags", "externalDocs", "servers", "security");
        String version = text(root.get("openapi"));
        require(version.equals("3.1.0") || version.equals("3.1.1") || version.equals("3.1.2"), "OpenAPI 3.1 required");
        Map<String, Object> info = object(root.get("info"));
        require(!text(info.get("title")).isEmpty() && !text(info.get("version")).isEmpty(), "OpenAPI info required");
        group = root.containsKey("x-hoori-api-group") ? text(root.get("x-hoori-api-group")) : "public";
        require(name(group), "Invalid OpenAPI API group");
        Object major = root.get("x-hoori-service-version");
        require(major instanceof ContractJson.NumberToken, "x-hoori-service-version required");
        serviceVersion = Integer.parseInt(((ContractJson.NumberToken) major).value());
        require(serviceVersion >= 1 && serviceVersion <= 9999, "Service version 1-9999");

        if (root.containsKey("security"))
            require(array(root.get("security")).isEmpty(), "Authentication needs a runtime security binding");

        if (root.containsKey("components")) {
            Map<String, Object> components = object(root.get("components"));
            keys(components, "schemas", "parameters", "requestBodies", "responses", "headers");

            if (components.containsKey("schemas"))
                for (Object value : object(components.get("schemas")).values()) schema(value, new HashSet<>(), 0);
        }

        Set<String> ids = new HashSet<>();
        for (var path : object(root.get("paths")).entrySet()) {
            Map<String, Object> item = object(path.getValue());
            keys(
                    item,
                    "summary",
                    "description",
                    "parameters",
                    "get",
                    "post",
                    "put",
                    "patch",
                    "delete",
                    "head",
                    "options");
            for (String method : METHODS) {
                if (!item.containsKey(method)) continue;

                require(operations.size() < 128, "At most 128 OpenAPI operations");
                Map<String, Object> operation = object(item.get(method));
                keys(
                        operation,
                        "operationId",
                        "summary",
                        "description",
                        "tags",
                        "externalDocs",
                        "deprecated",
                        "parameters",
                        "requestBody",
                        "responses",
                        "security");
                String id = text(operation.get("operationId"));
                require(id.length() <= 128 && !id.isEmpty() && ids.add(id), "Unique bounded operationId required");
                String permission = text(operation.get("x-hoori-permission"));
                require(permission.length() <= 127 && permission.indexOf(':') > 0, "x-hoori-permission required");

                if (operation.containsKey("security"))
                    require(
                            array(operation.get("security")).isEmpty(),
                            "Authentication needs a runtime security binding");

                Map<String, Object> materialized = materialize(path.getKey(), method);
                Set<String> parameters = new HashSet<>();
                for (Object value : array(materialized.get("parameters"))) {
                    Map<String, Object> parameter = resolve(value);
                    keys(
                            parameter,
                            "name",
                            "in",
                            "required",
                            "description",
                            "deprecated",
                            "schema",
                            "example",
                            "examples",
                            "style",
                            "explode",
                            "allowReserved");
                    String in = text(parameter.get("in")), parameterName = text(parameter.get("name"));
                    require(in.equals("path") || in.equals("query"), "Public parameters support path and query only");
                    require(parameters.add(in + " " + parameterName), "Duplicate OpenAPI parameter");
                    require(
                            !in.equals("path") || Boolean.TRUE.equals(parameter.get("required")),
                            "Path parameters are required");
                    require(
                            !Boolean.TRUE.equals(parameter.get("allowReserved")),
                            "Reserved parameter encoding is unsupported");
                    String style = parameter.containsKey("style")
                            ? text(parameter.get("style"))
                            : in.equals("path") ? "simple" : "form";
                    require(style.equals(in.equals("path") ? "simple" : "form"), "Unsupported parameter serialization");
                    schema(parameter.get("schema"), new HashSet<>(), 0);
                    Map<String, Object> type = resolve(parameter.get("schema"));

                    if (types(type).contains("array"))
                        require(
                                in.equals("query") && !Boolean.FALSE.equals(parameter.get("explode")),
                                "Lists use repeated query parameters");
                }

                if (materialized.containsKey("requestBody")) {
                    Map<String, Object> body = resolve(materialized.get("requestBody"));
                    keys(body, "description", "required", "content");
                    require(Boolean.TRUE.equals(body.get("required")), "MVC request body must be required");
                    content(body.get("content"), false);
                }

                Map<String, Object> responses = object(materialized.get("responses"));
                require(!responses.isEmpty(), "OpenAPI responses required");
                boolean success = false;
                for (var response : responses.entrySet()) {
                    String status = response.getKey();
                    require(
                            status.equals("default")
                                    || status.length() == 3
                                            && status.charAt(0) >= '1'
                                            && status.charAt(0) <= '5'
                                            && digits(status.substring(1)),
                            "Explicit HTTP response status or default required");
                    success |= status.startsWith("2");
                    Map<String, Object> fields = resolve(response.getValue());
                    keys(fields, "description", "content", "headers");
                    text(fields.get("description"));

                    if (fields.containsKey("content")) {
                        require(!status.equals("204") && !status.equals("304"), "Empty status cannot declare content");
                        content(fields.get("content"), !status.startsWith("2"));
                    }

                    if (fields.containsKey("headers"))
                        for (var header : object(fields.get("headers")).entrySet()) {
                            require(
                                    List.of(
                                                    "Location",
                                                    "ETag",
                                                    "Cache-Control",
                                                    "Vary",
                                                    "Retry-After",
                                                    "Last-Modified",
                                                    "Allow",
                                                    "X-Request-ID")
                                            .contains(header.getKey()),
                                    "Unsupported public response header");
                            Map<String, Object> fieldsHeader = resolve(header.getValue());
                            keys(
                                    fieldsHeader,
                                    "description",
                                    "required",
                                    "schema",
                                    "example",
                                    "examples",
                                    "deprecated");
                            schema(fieldsHeader.get("schema"), new HashSet<>(), 0);
                        }
                }
                require(success, "Explicit successful response required");
                String verb = method.toUpperCase(java.util.Locale.ROOT);
                new HttpEndpoint(
                        verb,
                        path.getKey(),
                        materialized.containsKey("requestBody") ? "application/json" : "",
                        "application/json");
                Map<String, Object> wire = new LinkedHashMap<>();
                wire.put("method", verb);
                wire.put("path", path.getKey());
                wire.put("permission", permission);
                wire.put("operationId", id);
                for (String key : List.of("parameters", "requestBody", "responses", "security"))
                    if (materialized.containsKey(key))
                        wire.put(
                                key,
                                expand(
                                        materialized.get(key),
                                        new HashSet<>(),
                                        new int[1],
                                        0,
                                        child(Position.OPERATION, key)));
                operations.add(new Operation(
                        verb, path.getKey(), id, permission, ContractJson.hash(ContractJson.bytes(wire))));
            }
        }
        require(!operations.isEmpty(), "OpenAPI needs a public operation");
        bytes = ContractJson.bytes(root);
        hash = ContractJson.hash(bytes);
    }

    private static boolean digits(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;

        return true;
    }

    public static boolean name(String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > 63
                || value.charAt(0) < 'a'
                || value.charAt(0) > 'z'
                || value.endsWith("-")) return false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) return false;
        }

        return true;
    }

    private void content(Object value, boolean error) {
        Map<String, Object> content = object(value);
        require(!content.isEmpty(), "Response content is empty");
        for (var entry : content.entrySet()) {
            require(
                    entry.getKey().equals("application/json")
                            || error && entry.getKey().equals("text/plain"),
                    "Unsupported MVC media type");
            Map<String, Object> media = object(entry.getValue());
            keys(media, "schema", "example", "examples");
            schema(media.get("schema"), new HashSet<>(), 0);
        }
    }

    private void schema(Object value, Set<String> active, int depth) {
        require(depth < 32 && ++schemaNodes <= 8192, "OpenAPI schema expansion limit");
        Map<String, Object> input = object(value);

        if (input.containsKey("$ref")) {
            String reference = text(input.get("$ref"));
            require(
                    input.size() == 1 && active.add(reference),
                    "Recursive or extended schema reference is unsupported");
            schema(resolve(input), active, depth + 1);
            active.remove(reference);

            return;
        }

        keys(
                input,
                "type",
                "format",
                "title",
                "description",
                "default",
                "examples",
                "enum",
                "const",
                "properties",
                "required",
                "additionalProperties",
                "items",
                "minItems",
                "maxItems",
                "minimum",
                "maximum",
                "exclusiveMinimum",
                "exclusiveMaximum",
                "minLength",
                "maxLength",
                "pattern",
                "deprecated");
        List<String> types = types(input);
        require(
                types.size() == 1 || types.size() == 2 && types.contains("null"),
                "Only nullable scalar unions are supported");
        for (String type : types)
            require(
                    List.of("object", "array", "string", "boolean", "integer", "number", "null")
                            .contains(type),
                    "Unsupported schema type");

        if (types.contains("object")) {
            Map<String, Object> properties = object(input.get("properties"));
            require(properties.size() <= 128, "OpenAPI property limit");
            for (Object child : properties.values()) schema(child, active, depth + 1);

            if (input.containsKey("required")) {
                Set<String> required = new HashSet<>();
                for (Object field : array(input.get("required")))
                    require(properties.containsKey(text(field)) && required.add(text(field)), "Invalid required field");
            }

            if (input.containsKey("additionalProperties"))
                require(
                        input.get("additionalProperties") instanceof Boolean,
                        "Typed additionalProperties is unsupported");
        }

        if (types.contains("array")) schema(input.get("items"), active, depth + 1);

        if (input.containsKey("enum")) require(!array(input.get("enum")).isEmpty(), "Empty enum");

        for (String key : List.of(
                "minimum",
                "maximum",
                "exclusiveMinimum",
                "exclusiveMaximum",
                "minLength",
                "maxLength",
                "minItems",
                "maxItems"))
            if (input.containsKey(key))
                require(input.get(key) instanceof ContractJson.NumberToken, "Numeric schema bound required");
        for (String key : List.of(
                "minLength",
                "maxLength",
                "minItems",
                "maxItems",
                "x-hoori-min-utf16-length",
                "x-hoori-max-utf16-length")) {
            if (!input.containsKey(key)) continue;

            require(input.get(key) instanceof ContractJson.NumberToken, "Integer size bound required");
            require(
                    Integer.parseInt(((ContractJson.NumberToken) input.get(key)).value()) >= 0,
                    "Nonnegative size bound required");
        }

        if (input.containsKey("x-hoori-not-blank"))
            require(input.get("x-hoori-not-blank") instanceof Boolean, "Boolean NotBlank extension required");
    }

    public static List<String> types(Map<String, Object> schema) {
        Object type = schema.get("type");

        if (type instanceof String text) return List.of(text);

        List<String> result = new ArrayList<>();
        for (Object item : array(type)) result.add(text(item));

        return result;
    }

    public Map<String, Object> resolve(Object value) {
        Map<String, Object> result = object(value);
        Set<String> seen = new HashSet<>();
        while (result.containsKey("$ref")) {
            String reference = text(result.get("$ref"));
            require(
                    result.size() == 1 && reference.startsWith("#/components/") && seen.add(reference),
                    "Only acyclic local component references are supported");
            Object current = root;
            for (String part : reference.substring(2).split("/")) {
                require(!part.isEmpty(), "Empty reference segment");
                current = object(current).get(part.replace("~1", "/").replace("~0", "~"));
            }
            result = object(current);
        }

        return result;
    }

    private enum Position {
        OPERATION,
        PARAMETERS,
        PARAMETER,
        REQUEST_BODY,
        RESPONSES,
        RESPONSE,
        HEADERS,
        HEADER,
        CONTENT,
        MEDIA,
        SCHEMA,
        PROPERTIES,
        EXAMPLES,
        EXAMPLE,
        LITERAL
    }

    private static Position child(Position position, String key) {
        return switch (position) {
            case OPERATION ->
                switch (key) {
                    case "parameters" -> Position.PARAMETERS;
                    case "requestBody" -> Position.REQUEST_BODY;
                    case "responses" -> Position.RESPONSES;
                    default -> Position.LITERAL;
                };
            case RESPONSES -> Position.RESPONSE;
            case HEADERS -> Position.HEADER;
            case CONTENT -> Position.MEDIA;
            case PROPERTIES -> Position.SCHEMA;
            case EXAMPLES -> Position.EXAMPLE;
            case SCHEMA ->
                switch (key) {
                    case "properties" -> Position.PROPERTIES;
                    case "items" -> Position.SCHEMA;
                    default -> Position.LITERAL;
                };
            case PARAMETER, HEADER, MEDIA ->
                switch (key) {
                    case "schema" -> Position.SCHEMA;
                    case "examples" -> Position.EXAMPLES;
                    default -> Position.LITERAL;
                };
            case REQUEST_BODY, RESPONSE ->
                switch (key) {
                    case "content" -> Position.CONTENT;
                    case "headers" -> Position.HEADERS;
                    default -> Position.LITERAL;
                };
            default -> Position.LITERAL;
        };
    }

    private Object expand(Object value, Set<String> active, int[] nodes, int depth, Position position) {
        require(++nodes[0] <= 8192 && depth < 32, "Expanded operation limit");

        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = object(value);

            if (map.containsKey("$ref")
                    && (position == Position.SCHEMA
                            || position == Position.PARAMETER
                            || position == Position.REQUEST_BODY
                            || position == Position.RESPONSE
                            || position == Position.HEADER
                            || position == Position.EXAMPLE)) {
                String ref = text(map.get("$ref"));
                require(active.add(ref), "Recursive contract reference");
                Object result = expand(resolve(map), active, nodes, depth + 1, position);
                active.remove(ref);

                return result;
            }

            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                copy.put(
                        entry.getKey(),
                        expand(entry.getValue(), active, nodes, depth + 1, child(position, entry.getKey())));
            }

            return copy;
        }

        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list)
                copy.add(expand(
                        item,
                        active,
                        nodes,
                        depth + 1,
                        position == Position.PARAMETERS ? Position.PARAMETER : Position.LITERAL));

            return copy;
        }

        return value;
    }

    /** A detached operation with path-level parameters made explicit. */
    public Map<String, Object> operation(Operation operation) {
        return object(ContractJson.read(ContractJson.bytes(
                materialize(operation.path(), operation.method().toLowerCase(java.util.Locale.ROOT)))));
    }

    /** Inline the finite local references so aggregation cannot collide on component names. */
    public Map<String, Object> expandedOperation(Operation operation) {
        return object(expand(operation(operation), new HashSet<>(), new int[1], 0, Position.OPERATION));
    }

    private Map<String, Object> materialize(String path, String method) {
        Map<String, Object> item = object(object(root.get("paths")).get(path));
        Map<String, Object> operation = new LinkedHashMap<>(object(item.get(method)));
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (Map<String, Object> level : List.of(item, operation)) {
            Set<String> seen = new HashSet<>();

            if (!level.containsKey("parameters")) continue;

            for (Object value : array(level.get("parameters"))) {
                Map<String, Object> parameter = resolve(value);
                String key = text(parameter.get("in")) + " " + text(parameter.get("name"));
                require(seen.add(key), "Duplicate OpenAPI parameter");
                parameters.put(key, parameter);
            }
        }
        operation.put("parameters", new ArrayList<>(parameters.values()));

        return operation;
    }

    public List<Operation> operations() {
        return List.copyOf(operations);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int byteSize() {
        return bytes.length;
    }

    public String hash() {
        return hash;
    }

    public String group() {
        return group;
    }

    public int serviceVersion() {
        return serviceVersion;
    }

    public Map<String, Object> document() {
        return object(ContractJson.read(bytes));
    }

    private static void keys(Map<String, Object> object, String... allowed) {
        List<String> names = List.of(allowed);
        for (String key : object.keySet())
            require(key.startsWith("x-") || names.contains(key), "Unsupported OpenAPI field: " + key);
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
