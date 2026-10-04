package hoori.micro.processor;

import static hoori.micro.openapi.ContractJson.array;
import static hoori.micro.openapi.ContractJson.object;
import static hoori.micro.openapi.ContractJson.text;
import static hoori.rest.mvc.processor.HttpContract.require;

import hoori.micro.openapi.ContractJson;
import hoori.micro.openapi.OpenApiDocument;
import hoori.rest.mvc.processor.HttpContract;
import hoori.rest.processor.JsonCodecs;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;

/** Checks an authored contract against the original MVC analysis at build time. */
final class OpenApiBinding {
    private final ProcessingEnvironment env;
    private final ClientGenerator clients;
    private final TypeElement app;
    private final OpenApiDocument document;

    OpenApiBinding(ProcessingEnvironment env, ClientGenerator clients, TypeElement app, String resource)
            throws IOException {
        this.env = env;
        this.clients = clients;
        this.app = app;
        require(
                !resource.startsWith("/") && !resource.contains("..") && resource.endsWith(".json"),
                app,
                "openApi must name a relative classpath JSON resource");
        try (var input = env.getFiler()
                .getResource(StandardLocation.CLASS_OUTPUT, "", resource)
                .openInputStream()) {
            document = new OpenApiDocument(ContractJson.source(input));
        } catch (IllegalArgumentException | hoori.rest.json.JsonException invalid) {
            throw new HttpContract.Invalid(app, "Invalid OpenAPI: " + invalid.getMessage());
        }
    }

    String bind(List<TypeElement> components) throws IOException {
        var application = HttpContract.annotation(app.getAnnotationMirrors(), "hoori.micro.app.MicroApplication");
        require(
                document.serviceVersion() == (Integer) clients.contracts.value(application, "version"),
                app,
                "OpenAPI service version differs from MicroApplication");
        Map<String, HttpContract.Method> methods = new LinkedHashMap<>();
        Map<String, String> permissions = new LinkedHashMap<>();
        for (TypeElement component : components) {
            if (clients.contracts.annotation(component, "RestController") == null) continue;

            for (var method : clients.contracts.analyze(component)) {
                var gateway = HttpContract.annotation(
                        method.element().getAnnotationMirrors(), "hoori.micro.app.GatewayRoute");

                if (gateway == null) continue;

                String key = method.verb() + " " + method.path();
                methods.put(key, method);
                permissions.put(key, (String) clients.contracts.value(gateway, "permission"));
            }
        }
        for (var operation : document.operations()) {
            var method = methods.remove(operation.key());
            require(method != null, app, "OpenAPI operation has no published controller: " + operation.key());
            require(
                    operation.permission().equals(permissions.get(operation.key())),
                    method.element(),
                    "OpenAPI permission differs from GatewayRoute");
            Map<String, Object> contract = document.operation(operation);
            Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
            for (Object value : array(contract.get("parameters"))) {
                var parameter = document.resolve(value);
                parameters.put(text(parameter.get("in")) + " " + text(parameter.get("name")), parameter);
            }
            for (var parameter : method.parameters()) {
                if (parameter.source().equals("BODY")) continue;

                var declaration = parameters.remove(
                        parameter.source().toLowerCase(java.util.Locale.ROOT) + " " + parameter.name());
                require(
                        declaration != null,
                        parameter.element(),
                        "MVC parameter missing in OpenAPI: " + parameter.name());
                boolean required = parameter.required() && parameter.defaultValue() == null;
                require(
                        required == Boolean.TRUE.equals(declaration.get("required")),
                        parameter.element(),
                        "OpenAPI parameter required/default differs from MVC");
                shape(parameter.type(), declaration.get("schema"), parameter.element(), true, true, 0);
                var schema = document.resolve(declaration.get("schema"));
                require(
                        !OpenApiDocument.types(schema).contains("null"),
                        parameter.element(),
                        "Text parameters have no null wire value; use required=false for absence");

                if (parameter.list())
                    require(
                            schema.containsKey("maxItems")
                                    && number(schema.get("maxItems"))
                                                    .compareTo(BigDecimal.valueOf(parameter.maxElements()))
                                            <= 0,
                            parameter.element(),
                            "OpenAPI query list exceeds the MVC parameter limit");

                if (parameter.defaultValue() != null) {
                    Object fallback = schema.get("default");
                    String actual =
                            fallback instanceof ContractJson.NumberToken n ? n.value() : String.valueOf(fallback);
                    require(
                            fallback != null && actual.equals(parameter.defaultValue()),
                            parameter.element(),
                            "OpenAPI default differs from MVC");
                }
            }
            require(parameters.isEmpty(), method.element(), "OpenAPI parameter has no MVC binding");
            require(
                    (method.body() != null) == contract.containsKey("requestBody"),
                    method.element(),
                    "OpenAPI request body differs from MVC");

            if (method.body() != null) {
                var parameter = method.parameters().stream()
                        .filter(p -> p.source().equals("BODY"))
                        .findFirst()
                        .orElseThrow();
                var body = document.resolve(contract.get("requestBody"));
                require(
                        !OpenApiDocument.types(document.resolve(jsonSchema(body)))
                                .contains("null"),
                        parameter.element(),
                        "MVC rejects a null request body");
                shape(method.body(), jsonSchema(body), parameter.element(), true, true, 0);
            }

            var responses = object(contract.get("responses"));
            boolean dynamic = method.responseKind().equals("RESULT");

            if (!dynamic)
                require(
                        responses.containsKey(Integer.toString(method.status())),
                        method.element(),
                        "MVC response status missing from OpenAPI");

            for (var entry : responses.entrySet()) {
                if (!entry.getKey().startsWith("2")) continue;

                if (!dynamic)
                    require(
                            entry.getKey().equals(Integer.toString(method.status())),
                            method.element(),
                            "Static MVC method cannot return the declared OpenAPI status");

                var response = document.resolve(entry.getValue());

                if (response.containsKey("content")) {
                    require(method.result() != null, method.element(), "Void MVC method cannot return OpenAPI content");
                    shape(method.result(), jsonSchema(response), method.element(), false, false, 0);
                } else
                    require(dynamic || method.result() == null, method.element(), "DTO response needs OpenAPI content");
            }
        }
        require(methods.isEmpty(), app, "Published MVC operation missing in OpenAPI: " + methods.keySet());
        String baseline = env.getOptions().get("hoori.openapi.baseline");
        String bundledBaseline = (String) clients.contracts.value(application, "openApiBaseline");

        if (baseline != null || !bundledBaseline.isEmpty()) {
            byte[] bytes;

            if (baseline != null) {
                try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(baseline))) {
                    bytes = ContractJson.source(input);
                }
            } else {
                require(
                        !bundledBaseline.startsWith("/")
                                && !bundledBaseline.contains("..")
                                && bundledBaseline.endsWith(".json"),
                        app,
                        "Invalid OpenAPI baseline resource");
                try (var input = env.getFiler()
                        .getResource(StandardLocation.CLASS_OUTPUT, "", bundledBaseline)
                        .openInputStream()) {
                    bytes = ContractJson.source(input);
                }
            }

            try {
                OpenApiCompatibility.check(new OpenApiDocument(bytes), document);
            } catch (IllegalArgumentException | hoori.rest.json.JsonException incompatible) {
                throw new HttpContract.Invalid(app, incompatible.getMessage());
            }
        }

        String resource = "META-INF/hoori-micro/" + app.getQualifiedName() + "/openapi.json";
        try (var output = env.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", resource, app)
                .openOutputStream()) {
            output.write(document.bytes());
        }

        return "      app.openApi(" + app.getQualifiedName() + ".class, " + HttpContract.literal("/" + resource) + ", "
                + HttpContract.literal(document.hash()) + ");\n";
    }

    private Object jsonSchema(Map<String, Object> owner) {
        return object(object(owner.get("content")).get("application/json")).get("schema");
    }

    private void shape(TypeMirror type, Object value, Element origin, boolean input, boolean validated, int depth) {
        shape(type, value, origin, input, validated, true, depth);
    }

    private void shape(
            TypeMirror type,
            Object value,
            Element origin,
            boolean input,
            boolean validated,
            boolean declarations,
            int depth) {
        require(depth < 32, origin, "OpenAPI DTO depth limit");
        Map<String, Object> schema = document.resolve(value);
        List<String> types = OpenApiDocument.types(schema);
        String java = clients.codecs.typeName(type, origin);
        String scalar = switch (java) {
            case "java.lang.String" -> "string";
            case "boolean", "java.lang.Boolean" -> "boolean";
            case "byte",
                    "short",
                    "int",
                    "long",
                    "java.lang.Byte",
                    "java.lang.Short",
                    "java.lang.Integer",
                    "java.lang.Long" -> "integer";
            case "float", "double", "java.lang.Float", "java.lang.Double" -> "number";
            default -> null;
        };
        require(
                !type.getKind().isPrimitive() || !types.contains("null"),
                origin,
                "Primitive cannot accept nullable OpenAPI data");

        if (scalar != null) {
            require(types.contains(scalar), origin, "OpenAPI type differs from " + java);

            if (scalar.equals("integer")) {
                String format = java.equals("long") || java.equals("java.lang.Long") ? "int64" : "int32";
                require(format.equals(schema.get("format")), origin, "OpenAPI integer format differs from " + java);
            }
        } else if (type instanceof DeclaredType declared && declared.asElement().getKind() == ElementKind.ENUM) {
            require(
                    types.contains("string") && schema.containsKey("enum"),
                    origin,
                    "Enum needs an OpenAPI string enum");
            Set<String> constants = new HashSet<>();
            for (Element element : declared.asElement().getEnclosedElements())
                if (element.getKind() == ElementKind.ENUM_CONSTANT)
                    constants.add(element.getSimpleName().toString());
            Set<String> declaredValues = new HashSet<>();
            for (Object item : array(schema.get("enum"))) declaredValues.add(text(item));
            require(
                    input ? constants.containsAll(declaredValues) : declaredValues.containsAll(constants),
                    origin,
                    "OpenAPI enum differs from MVC values");
        } else if (type instanceof DeclaredType declared
                && declared.asElement().toString().equals("java.util.List")) {
            require(
                    types.contains("array") && declared.getTypeArguments().size() == 1,
                    origin,
                    "List needs an OpenAPI array");
            shape(
                    declared.getTypeArguments().getFirst(),
                    schema.get("items"),
                    origin,
                    input,
                    validated,
                    false,
                    depth + 1);
            int max = Integer.parseInt(env.getOptions()
                    .getOrDefault("hoori.mvc.listLimit", Integer.toString(JsonCodecs.DEFAULT_LIST_LIMIT)));
            AnnotationMirror list = annotation(type, "hoori.rest.codegen.JsonList");

            if (list != null) max = (Integer) clients.contracts.value(list, "max");

            require(
                    schema.containsKey("maxItems")
                            && (input
                                    ? number(schema.get("maxItems")).compareTo(BigDecimal.valueOf(max)) <= 0
                                    : number(schema.get("maxItems")).compareTo(BigDecimal.valueOf(max)) >= 0),
                    origin,
                    "OpenAPI array must declare the codec maxItems bound");
        } else if (type instanceof DeclaredType declared && declared.asElement().getKind() == ElementKind.RECORD) {
            require(types.contains("object"), origin, "Record needs an OpenAPI object");
            var record = (TypeElement) declared.asElement();
            var properties = object(schema.get("properties"));
            List<?> required = schema.containsKey("required") ? array(schema.get("required")) : List.of();
            Set<String> names = new HashSet<>();
            for (var field : record.getRecordComponents()) {
                var config = HttpContract.annotation(field.getAnnotationMirrors(), "hoori.rest.codegen.JsonField");
                String name = config == null ? "" : (String) clients.contracts.value(config, "name");

                if (name.isEmpty()) name = field.getSimpleName().toString();

                names.add(name);
                require(properties.containsKey(name), field, "DTO field missing from OpenAPI: " + name);
                boolean requiredByCodec = config == null || (Boolean) clients.contracts.value(config, "required");
                boolean validatedFields = validated && valid(origin, type);
                boolean requiredByValidation = validatedFields && (nonNull(field, field.asType()));
                require(
                        !input || !(requiredByCodec || requiredByValidation) || required.contains(name),
                        field,
                        "Codec-required field is optional in OpenAPI: " + name);
                shape(field.asType(), properties.get(name), field, input, validatedFields, true, depth + 1);
            }
            require(names.equals(properties.keySet()), origin, "OpenAPI properties differ from DTO fields");
        } else require(false, origin, "No OpenAPI binding for " + java);

        require(
                annotation(type, "hoori.rest.codegen.JsonUsing") == null
                        && annotation(type, "hoori.rest.codegen.JsonNumber") == null,
                origin,
                "Custom codecs and number mappings need an explicit OpenAPI binding and are unsupported");

        if (input && validated) constraints(type, schema, origin, declarations);
    }

    private void constraints(TypeMirror type, Map<String, Object> schema, Element origin, boolean declarations) {
        Map<String, AnnotationMirror> annotations = new LinkedHashMap<>();

        if (declarations)
            for (var annotation : origin.getAnnotationMirrors())
                annotations.put(annotation.getAnnotationType().toString(), annotation);

        for (var annotation : type.getAnnotationMirrors())
            annotations.put(annotation.getAnnotationType().toString(), annotation);
        for (var entry : annotations.entrySet()) {
            String name = entry.getKey();
            var annotation = entry.getValue();

            if (name.equals("jakarta.validation.constraints.NotNull")
                    || name.equals("jakarta.validation.constraints.NotBlank"))
                require(
                        !OpenApiDocument.types(schema).contains("null"),
                        origin,
                        "Constraint rejects nullable OpenAPI input");

            if (name.equals("jakarta.validation.constraints.NotBlank"))
                require(
                        Boolean.TRUE.equals(schema.get("x-hoori-not-blank")),
                        origin,
                        "NotBlank needs x-hoori-not-blank to retain Java whitespace semantics");

            if (name.equals("jakarta.validation.constraints.Positive"))
                require(
                        schema.containsKey("exclusiveMinimum")
                                        && number(schema.get("exclusiveMinimum"))
                                                        .signum()
                                                >= 0
                                || schema.containsKey("minimum")
                                        && number(schema.get("minimum")).signum() > 0,
                        origin,
                        "Positive constraint missing from OpenAPI");

            if (name.equals("jakarta.validation.constraints.Min")
                    || name.equals("jakarta.validation.constraints.Max")) {
                boolean min = name.endsWith(".Min");
                String key = min ? "minimum" : "maximum";
                BigDecimal bound = BigDecimal.valueOf((Long) clients.contracts.value(annotation, "value"));
                require(
                        schema.containsKey(key)
                                && (min
                                        ? number(schema.get(key)).compareTo(bound) >= 0
                                        : number(schema.get(key)).compareTo(bound) <= 0),
                        origin,
                        "Numeric validation bound missing from OpenAPI");
            }

            if (name.equals("jakarta.validation.constraints.Size")) {
                boolean string = OpenApiDocument.types(schema).contains("string");
                String min = string ? "x-hoori-min-utf16-length" : "minItems",
                        max = string ? "x-hoori-max-utf16-length" : "maxItems";
                int lower = (Integer) clients.contracts.value(annotation, "min"),
                        upper = (Integer) clients.contracts.value(annotation, "max");
                require(
                        lower == 0
                                || schema.containsKey(min)
                                        && number(schema.get(min)).compareTo(BigDecimal.valueOf(lower)) >= 0,
                        origin,
                        "Size minimum missing from OpenAPI");
                require(
                        upper == Integer.MAX_VALUE
                                || schema.containsKey(max)
                                        && number(schema.get(max)).compareTo(BigDecimal.valueOf(upper)) <= 0,
                        origin,
                        "Size maximum missing from OpenAPI");
            }
        }
    }

    private boolean valid(Element origin, TypeMirror type) {
        return HttpContract.annotation(origin.getAnnotationMirrors(), "jakarta.validation.Valid") != null
                || annotation(type, "jakarta.validation.Valid") != null;
    }

    private static boolean nonNull(Element origin, TypeMirror type) {
        for (String name : List.of("jakarta.validation.constraints.NotNull", "jakarta.validation.constraints.NotBlank"))
            if (HttpContract.annotation(origin.getAnnotationMirrors(), name) != null || annotation(type, name) != null)
                return true;

        return false;
    }

    private static AnnotationMirror annotation(TypeMirror type, String name) {
        return HttpContract.annotation(type.getAnnotationMirrors(), name);
    }

    private static BigDecimal number(Object value) {
        return new BigDecimal(((ContractJson.NumberToken) value).value());
    }
}
