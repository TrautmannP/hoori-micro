package hoori.micro.processor;

import static org.junit.jupiter.api.Assertions.*;

import hoori.micro.openapi.ContractJson;
import hoori.micro.openapi.OpenApiDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OpenApiBindingTest {
    @TempDir
    Path directory;

    private static final String CONTRACT = """
            {"openapi":"3.1.0","info":{"title":"Test","version":"1"},"x-hoori-service-version":1,
              "paths":{"/items/{id}":{"get":{"operationId":"getItem","x-hoori-permission":"test:read",
                "parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"integer","format":"int64"}}],
                "responses":{"200":{"description":"Found","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Item"}}}}}}}},
              "components":{"schemas":{"Item":{"type":"object","properties":{"id":{"type":"integer","format":"int64"},"name":{"type":"string"}},"required":["id","name"]}}}}
            """;
    private static final String CONTROLLER = """
            package test;
            @hoori.rest.mvc.RestController public final class Web {
             @hoori.rest.mvc.GetMapping("/items/{id}") @hoori.micro.app.GatewayRoute(permission="test:read")
             public Item get(@hoori.rest.mvc.PathVariable("id") long id) { return new Item(id,"test"); }
            }
            """;

    private String compile(String contract, String controller, String baseline) throws Exception {
        Path work = Files.createTempDirectory(directory, "compile");
        Path output = Files.createDirectory(work.resolve("classes"));
        Files.writeString(output.resolve("openapi.json"), contract);

        if (baseline != null) Files.writeString(output.resolve("baseline.json"), baseline);

        Path app = work.resolve("App.java"), web = work.resolve("Web.java"), dto = work.resolve("Item.java");
        Files.writeString(
                app,
                "package test; @hoori.micro.app.MicroApplication(name=\"test\",openApi=\"openapi.json\""
                        + (baseline == null ? "" : ",openApiBaseline=\"baseline.json\"") + ") public class App {}");
        Files.writeString(web, controller);
        Files.writeString(dto, "package test; public record Item(long id, String name) {}");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "-proc:only",
                            "-XDaddTypeAnnotationsToSymbol=true",
                            "-d",
                            output.toString(),
                            "-s",
                            output.toString(),
                            "-classpath",
                            System.getProperty("java.class.path")),
                    null,
                    files.getJavaFileObjects(app, web, dto));
            task.setProcessors(List.of(new ApplicationProcessor(), new hoori.rest.mvc.processor.MvcProcessor()));

            if (!task.call()) return diagnostics.getDiagnostics().toString();
        }
        OpenApiDocument expected = document(contract);
        assertArrayEquals(
                expected.bytes(), Files.readAllBytes(output.resolve("META-INF/hoori-micro/test.App/openapi.json")));
        assertTrue(Files.readString(output.resolve("test/AppMicroModule.java")).contains(expected.hash()));

        return "OK";
    }

    @Test
    void checksAuthoredContractAndProducesCanonicalResource() throws Exception {
        assertEquals("OK", compile(CONTRACT, CONTROLLER, CONTRACT));
        String malformed = compile("{", CONTROLLER, null);
        assertTrue(malformed.contains("Invalid OpenAPI"), malformed);
        String mismatch = compile(
                CONTRACT.replace("\"name\":{\"type\":\"string\"}", "\"name\":{\"type\":\"boolean\"}"),
                CONTROLLER,
                null);
        assertTrue(mismatch.contains("OpenAPI type differs"), mismatch);
        mismatch = compile(CONTRACT.replace("test:read", "test:write"), CONTROLLER, null);
        assertTrue(mismatch.contains("permission differs"), mismatch);
        mismatch = compile(CONTRACT.replace("\"200\"", "\"201\""), CONTROLLER, null);
        assertTrue(mismatch.contains("response status missing"), mismatch);
        mismatch = compile(CONTRACT.replace("/items/{id}", "/different/{id}"), CONTROLLER, null);
        assertTrue(mismatch.contains("no published controller"), mismatch);
        mismatch = compile(CONTRACT.replace("\"required\":true", "\"required\":false"), CONTROLLER, null);
        assertTrue(mismatch.contains("Path parameters are required"), mismatch);
        mismatch = compile(
                CONTRACT, CONTROLLER.replace("long id)", "@jakarta.validation.constraints.Positive long id)"), null);
        assertTrue(mismatch.contains("Positive constraint missing"), mismatch);
    }

    @Test
    void baselineAllowsNewOperationsAndRequiresMajorForChangedOnes() throws Exception {
        OpenApiDocument before = document(CONTRACT);
        Map<String, Object> tree = before.document();
        Map<String, Object> operation =
                new LinkedHashMap<>(before.operation(before.operations().getFirst()));
        operation.put("operationId", "getNewItem");
        ContractJson.object(tree.get("paths")).put("/new/{id}", Map.of("get", operation));
        OpenApiCompatibility.check(before, new OpenApiDocument(ContractJson.bytes(tree)));
        String changed = CONTRACT.replace("getItem", "readItem");
        String result = compile(changed, CONTROLLER, CONTRACT);
        assertTrue(result.contains("baseline changed"), result);
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenApiCompatibility.check(before, document(CONTRACT.replace("int64", "int32"))));
        OpenApiCompatibility.check(
                before, document(changed.replace("\"x-hoori-service-version\":1", "\"x-hoori-service-version\":2")));
    }

    @Test
    void defaultsMustBePresentOnBothSidesAndMatchTheirScalarType() throws Exception {
        for (String[] scalar : List.of(
                new String[] {"Integer", "\"type\":\"integer\",\"format\":\"int32\"", "20", "20"},
                new String[] {
                    "Long", "\"type\":\"integer\",\"format\":\"int64\"", "9223372036854775807", "9223372036854775807"
                },
                new String[] {"Boolean", "\"type\":\"boolean\"", "true", "true"},
                new String[] {"String", "\"type\":\"string\"", "\"\"", ""})) {
            String schema = "{" + scalar[1] + ",\"default\":" + scalar[2] + "}";
            String contract = queryContract(schema);
            String controller = queryController(scalar[0], "defaultValue=\"" + scalar[3] + "\"");
            assertEquals("OK", compile(contract, controller, contract));
            String absent = queryController(scalar[0], "required=false");
            String diagnostic = compile(contract, absent, contract);
            assertTrue(diagnostic.contains("default presence differs"), diagnostic);
            String noDefault = queryContract("{" + scalar[1] + "}");
            assertEquals("OK", compile(noDefault, absent, null));
            diagnostic = compile(noDefault, controller, null);
            assertTrue(diagnostic.contains("default presence differs"), diagnostic);
        }
        assertEquals(
                "OK",
                compile(
                        queryContract("{\"type\":\"integer\",\"format\":\"int32\",\"default\":20.0}"),
                        queryController("Integer", "defaultValue=\"020\""),
                        null));
        for (String fallback : List.of("21", "\"20\"", "null")) {
            String diagnostic = compile(
                    queryContract("{\"type\":\"integer\",\"format\":\"int32\",\"default\":" + fallback + "}"),
                    queryController("Integer", "defaultValue=\"20\""),
                    null);
            assertTrue(diagnostic.contains("default differs"), diagnostic);
        }
    }

    @Test
    void narrowIntegerInputsNeedBoundsButOutputsMayBeWider() throws Exception {
        for (String type : List.of("byte", "Byte", "short", "Short")) {
            int min = type.equalsIgnoreCase("byte") ? -128 : -32768;
            int max = type.equalsIgnoreCase("byte") ? 127 : 32767;
            String controller = queryController(type, "required=true");
            for (String bounds : List.of(
                    "",
                    ",\"minimum\":" + (min - 1) + ",\"maximum\":" + max,
                    ",\"minimum\":" + min + ",\"maximum\":" + (max + 1))) {
                String diagnostic = compile(integerQuery(bounds), controller, null);
                assertTrue(diagnostic.contains("input range exceeds") && diagnostic.contains(type), diagnostic);
            }
            for (String bounds : List.of(
                    ",\"minimum\":" + min + ",\"maximum\":" + max,
                    ",\"exclusiveMinimum\":" + (min - 1) + ",\"exclusiveMaximum\":" + (max + 1),
                    ",\"minimum\":0,\"maximum\":10",
                    ",\"enum\":[0,10]",
                    ",\"const\":10")) assertEquals("OK", compile(integerQuery(bounds), controller, null));
            String diagnostic = compile(integerQuery(",\"enum\":[0," + (max + 1) + "]"), controller, null);
            assertTrue(diagnostic.contains("input range exceeds"), diagnostic);
        }
        assertEquals("OK", compile(integerQuery(""), queryController("int", "required=true"), null));
        assertEquals("OK", compile(integerQuery(""), queryController("Integer", "required=true"), null));
        assertEquals(
                "OK",
                compile(
                        integerQuery(",\"minimum\":-128.5,\"maximum\":127.5"),
                        queryController("byte", "required=true"),
                        null));
        assertEquals(
                "OK",
                compile(
                        integerQuery(",\"minimum\":1,\"maximum\":127"),
                        queryController("byte", "required=true")
                                .replace("byte value", "@jakarta.validation.constraints.Positive byte value"),
                        null));
        assertEquals(
                "OK",
                compile(
                        integerQuery("")
                                .replace(
                                        "\"schema\":{\"type\":\"string\"}",
                                        "\"schema\":{\"type\":\"integer\",\"format\":\"int32\"}"),
                        queryController("int", "required=true")
                                .replace("public String get", "public byte get")
                                .replace("return \"ok\"", "return 1"),
                        null));
    }

    @Test
    void inheritedParametersUseTheOperationOverrideForBindingAndBaseline() throws Exception {
        Map<String, Object> tree = document(integerQuery(",\"default\":10")).document();
        Map<String, Object> item =
                ContractJson.object(ContractJson.object(tree.get("paths")).get("/items"));
        Map<String, Object> operation = ContractJson.object(item.get("get"));
        Map<String, Object> inherited = ContractJson.object(
                ContractJson.array(operation.get("parameters")).getFirst());
        Map<String, Object> schema = new LinkedHashMap<>(ContractJson.object(inherited.get("schema")));
        schema.put("default", new ContractJson.NumberToken("20"));
        Map<String, Object> parameter = new LinkedHashMap<>(inherited);
        parameter.put("schema", schema);
        item.put("parameters", List.of(parameter));
        String withOverride = new String(ContractJson.bytes(tree), StandardCharsets.UTF_8);
        assertEquals(
                "OK",
                compile(
                        withOverride,
                        queryController("Integer", "defaultValue=\"10\""),
                        integerQuery(",\"default\":10")));
        String diagnostic = compile(withOverride, queryController("Integer", "defaultValue=\"20\""), null);
        assertTrue(diagnostic.contains("default differs"), diagnostic);
    }

    private static String integerQuery(String bounds) {
        String contract = queryContract("{\"type\":\"integer\",\"format\":\"int32\"" + bounds + "}");

        return bounds.contains("default") ? contract : contract.replace("\"required\":false", "\"required\":true");
    }

    private static String queryContract(String schema) {
        return "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Test\",\"version\":\"1\"},\"x-hoori-service-version\":1,"
                + "\"paths\":{\"/items\":{\"get\":{\"operationId\":\"itemsGet\",\"x-hoori-permission\":\"test:read\","
                + "\"parameters\":[{\"name\":\"value\",\"in\":\"query\",\"required\":false,\"schema\":" + schema + "}],"
                + "\"responses\":{\"200\":{\"description\":\"OK\",\"content\":{\"application/json\":{\"schema\":{\"type\":\"string\"}}}}}}}}}";
    }

    private static String queryController(String type, String options) {
        return "package test; @hoori.rest.mvc.RestController public final class Web {"
                + "@hoori.rest.mvc.GetMapping(\"/items\") @hoori.micro.app.GatewayRoute(permission=\"test:read\")"
                + "public String get(@hoori.rest.mvc.RequestParam(value=\"value\"," + options + ") " + type
                + " value) { return \"ok\"; }}";
    }

    private static OpenApiDocument document(String json) {
        return new OpenApiDocument(json.getBytes(StandardCharsets.UTF_8));
    }
}
