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
            task.setProcessors(List.of(new ApplicationProcessor()));
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
}
