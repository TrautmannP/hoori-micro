package hoori.micro.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationProcessorTest {
    @TempDir
    Path output;

    private String compile(String... sources) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var files = new java.util.ArrayList<JavaFileObject>();
        for (int i = 0; i < sources.length; i += 2) {
            final String source = sources[i + 1];
            files.add(
                    new SimpleJavaFileObject(
                            URI.create("string:///test/" + sources[i] + ".java"), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignore) {
                            return source;
                        }
                    });
        }
        Path generated = Files.createTempDirectory(output, "generated");
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(
                    null,
                    manager,
                    diagnostics,
                    List.of(
                            "-proc:only",
                            "-d",
                            generated.toString(),
                            "-s",
                            generated.toString(),
                            "-classpath",
                            System.getProperty("java.class.path")),
                    null,
                    files);
            task.setProcessors(List.of(new ApplicationProcessor(), new hoori.tasks.processor.TasksProcessor()));
            boolean success = task.call();

            return success ? "OK" : diagnostics.getDiagnostics().toString();
        }
    }

    private static final String APP =
            "package test; @hoori.micro.app.MicroApplication(name=\"test\") public class App {}";

    @Test
    void resolvesConcreteSingletonsAndFactories() throws Exception {
        assertEquals(
                "OK",
                compile(
                        "App",
                        APP,
                        "Store",
                        "package test; @hoori.micro.app.Repository public class Store {}",
                        "Work",
                        "package test; @hoori.micro.app.Service public class Work { public Work(Store store, java.time.Duration duration) {} }",
                        "Config",
                        "package test; @hoori.micro.app.Configuration public class Config { @hoori.micro.app.Bean public java.time.Duration duration() { return java.time.Duration.ZERO; } }"));
    }

    @Test
    void missingDependencyIncludesChain() throws Exception {
        String result = compile(
                "App",
                APP,
                "Work",
                "package test; @hoori.micro.app.Service public class Work { public Work(java.util.List<String> missing) {} }");
        assertTrue(result.contains("Missing dependency java.util.List<java.lang.String> in test.Work"), result);
    }

    @Test
    void ambiguousDependencyFails() throws Exception {
        String result = compile(
                "App",
                APP,
                "A",
                "package test; @hoori.micro.app.Service public class A implements Runnable { public void run() {} }",
                "B",
                "package test; @hoori.micro.app.Service public class B implements Runnable { public void run() {} }",
                "Work",
                "package test; @hoori.micro.app.Service public class Work { public Work(Runnable duplicate) {} }");
        assertTrue(result.contains("Ambiguous dependency java.lang.Runnable"), result);
    }

    @Test
    void cycleIncludesBothTypes() throws Exception {
        String result = compile(
                "App",
                APP,
                "A",
                "package test; @hoori.micro.app.Service public class A { public A(B b) {} }",
                "B",
                "package test; @hoori.micro.app.Service public class B { public B(A a) {} }");
        assertTrue(result.contains("Dependency cycle: test.A -> test.B -> test.A"), result);
    }

    @Test
    void duplicateAppFails() throws Exception {
        String result = compile("App", APP, "Other", APP.replace("App {}", "Other {}"));
        assertTrue(result.contains("Duplicate MicroApplication"), result);
    }

    @Test
    void ambiguousConstructorsFail() throws Exception {
        String result = compile(
                "App",
                APP,
                "Work",
                "package test; @hoori.micro.app.Service public class Work { public Work() {} public Work(String s) {} }");
        assertTrue(result.contains("exactly one public constructor"), result);
    }

    @Test
    void clientUsesSharedHttpModelIncludingLiteralSegmentsAndVoid() throws Exception {
        assertEquals(
                "OK",
                compile(
                        "App",
                        APP,
                        "Recipes",
                        """
                package test;
                @hoori.micro.app.ServiceClient(name="recipes")
                public interface Recipes {
                    @hoori.rest.mvc.GetMapping("/recipes/{id}")
                    String get(@hoori.rest.mvc.PathVariable("id") long id);
                    @hoori.rest.mvc.DeleteMapping("/recipes/{id}")
                    void delete(@hoori.rest.mvc.PathVariable("id") long id);
                    @hoori.rest.mvc.GetMapping("/recipes")
                    java.util.List<String> search(@hoori.rest.mvc.RequestParam(value="tag", required=false, max=3) java.util.List<String> tags);
                }
                """,
                        "Work",
                        "package test; @hoori.micro.app.Service public class Work { public Work(Recipes recipes) {} }"));
    }

    @Test
    void clientRejectsUnboundPathsAndCredentialHeaders() throws Exception {
        String declaration =
                "package test; @hoori.micro.app.ServiceClient(name=\"recipes\") public interface Recipes { ";
        String result =
                compile("Recipes", declaration + "@hoori.rest.mvc.GetMapping(\"/recipes/{id}\") String get(); }");
        assertTrue(result.contains("PathVariable binding"), result);
        result = compile(
                "Recipes",
                declaration
                        + "@hoori.rest.mvc.GetMapping(\"/recipes\") String get(@hoori.rest.mvc.RequestHeader(\"Authorization\") String auth); }");
        assertTrue(result.contains("reserved or contains credentials"), result);
    }

    @Test
    void publicationRequiresMappedControllerAndSupportedHeaders() throws Exception {
        String result = compile(
                "App",
                APP,
                "Stray",
                "package test; public class Stray { @hoori.micro.app.GatewayRoute(permission=\"test:read\") public void run() {} }");
        assertTrue(result.contains("requires a RestController"), result);
        result = compile("App", APP, "Web", """
                package test; @hoori.rest.mvc.RestController public class Web {
                 @hoori.micro.app.GatewayRoute(permission="test:read")
                 @hoori.rest.mvc.GetMapping("/test")
                 public String get(@hoori.rest.mvc.RequestHeader("X-Mode") String mode) { return mode; }
                }
                """);
        assertTrue(result.contains("Gateway forwards only"), result);
    }

    private static final String SCOPED =
            "package test; @hoori.tasks.TaskScoped public interface Work { void run() throws Exception; }";
    private static final String DELEGATE =
            "package test; @hoori.micro.app.Service public class RealWork implements Work { public void run() {} }";
    private static final String USE =
            "package test; @hoori.micro.app.Service public class Use { public Use(Work work) {} }";

    @Test
    void scopedInterfaceUsesOriginalDelegateAndRejectsAmbiguityAndCycles() throws Exception {
        assertEquals("OK", compile("App", APP, "Work", SCOPED, "RealWork", DELEGATE, "Use", USE));
        String result = compile(
                "App",
                APP,
                "Work",
                SCOPED,
                "RealWork",
                DELEGATE,
                "OtherWork",
                DELEGATE.replace("RealWork", "OtherWork"),
                "Use",
                USE);
        assertTrue(result.contains("exactly one delegate"), result);
        result = compile(
                "App",
                APP,
                "Work",
                SCOPED,
                "RealWork",
                DELEGATE.replace("public void run()", "public RealWork(Use use) {} public void run()"),
                "Use",
                USE);
        assertTrue(result.contains("Dependency cycle"), result);
    }

    @Test
    void transactionalDecorationRequiresOneStableManager() throws Exception {
        String contract = SCOPED.replace("TaskScoped", "Transactional");
        String result = compile("App", APP, "Work", contract, "RealWork", DELEGATE, "Use", USE);
        assertTrue(result.contains("exactly one TransactionManager"), result);
        String config =
                "package test; @hoori.micro.app.Configuration public class Config { @hoori.micro.app.Bean public hoori.transaction.TransactionManager<?> manager() { return null; } }";
        assertEquals("OK", compile("App", APP, "Work", contract, "RealWork", DELEGATE, "Use", USE, "Config", config));
        result = compile(
                "App",
                APP,
                "Work",
                contract,
                "RealWork",
                DELEGATE,
                "Use",
                USE,
                "Config",
                config,
                "OtherConfig",
                config.replace("class Config", "class OtherConfig"));
        assertTrue(result.contains("exactly one TransactionManager"), result);
    }
}
