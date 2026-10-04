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

    private static OpenApiDocument document(String json) {
        return new OpenApiDocument(json.getBytes(StandardCharsets.UTF_8));
    }
}
