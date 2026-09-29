import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Host-only Spotless step: blank lines around if statements and before return statements. */
// ponytail: one JDK launch per file; batch the step only if repository size makes formatting slow.
final class BlankLines {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            System.out.println("Blank-line formatter checks passed: 5 fixtures");
        } else if (args.length == 0) {
            String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.print(format(source));
        } else {
            throw new IllegalArgumentException("usage: java scripts/BlankLines.java [--self-test]");
        }
    }

    static String format(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null) throw new IllegalStateException("BlankLines requires a full JDK 21");

        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(
                    null, files, diagnostics, List.of("--release", "21", "-proc:none"), null, List.of(file));
            var unit = task.parse().iterator().next();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    throw new IllegalArgumentException("Invalid Java source at line " + diagnostic.getLineNumber());
            }
            var positions = Trees.instance(task).getSourcePositions();
            // One insertion per gap; adjacent if/return rules must not accumulate blank lines.
            var insertions = new TreeMap<Integer, String>();
            new TreeScanner<Void, Void>() {
                void separate(List<? extends StatementTree> statements) {
                    for (int i = 1; i < statements.size(); i++) {
                        var previous = statements.get(i - 1);
                        var current = statements.get(i);

                        if (kind(previous) == Tree.Kind.IF
                                || kind(current) == Tree.Kind.IF
                                || kind(current) == Tree.Kind.RETURN) {
                            int from = (int) positions.getEndPosition(unit, previous);
                            int to = (int) positions.getStartPosition(unit, current);
                            pad(source, from, to, insertions);
                        }
                    }
                }

                @Override
                public Void visitBlock(BlockTree block, Void unused) {
                    separate(block.getStatements());

                    return super.visitBlock(block, unused);
                }

                @Override
                public Void visitCase(CaseTree branch, Void unused) {
                    if (branch.getStatements() != null) separate(branch.getStatements());

                    return super.visitCase(branch, unused);
                }
            }.scan(unit, null);
            var result = new StringBuilder(source);
            for (var insertion : insertions.descendingMap().entrySet()) {
                result.insert(insertion.getKey().intValue(), insertion.getValue());
            }

            return result.toString();
        }
    }

    private static Tree.Kind kind(StatementTree statement) {
        while (statement instanceof LabeledStatementTree label) statement = label.getStatement();

        return statement.getKind();
    }

    private static void pad(String source, int from, int to, TreeMap<Integer, String> insertions) {
        // Same-line comments belong to the preceding statement; later comments stay with the next one.
        while (from < to) {
            int end = from;
            int newlines = 0;
            while (end < to && Character.isWhitespace(source.charAt(end))) {
                if (source.charAt(end) == '\n') newlines++;

                end++;
            }

            if (newlines >= 2) return;

            if (newlines == 1) {
                insertions.put(source.indexOf('\n', from), "\n");

                return;
            }

            if (source.startsWith("//", end)) {
                int newline = source.indexOf('\n', end);
                from = newline < 0 || newline > to ? to : newline;
            } else if (source.startsWith("/*", end)) {
                int close = source.indexOf("*/", end + 2);
                // Javac also accepts Unicode-escaped comment delimiters; never scan beyond this gap.
                from = close < 0 || close + 2 > to ? to : close + 2;
            } else {
                // Normally Palantir has already split statements onto separate lines.
                int line = source.lastIndexOf('\n', to - 1) + 1;
                int indent = line;
                while (indent < to && (source.charAt(indent) == ' ' || source.charAt(indent) == '\t')) indent++;
                insertions.put(end, "\n\n" + source.substring(line, indent));

                return;
            }
        }
        insertions.put(to, "\n\n");
    }

    private static void selfTest() throws Exception {
        String source = """
                class Example {
                    int run(boolean first, boolean second) {
                        int value = 0; // previous statement
                        /* next statement
                         * if (text) return text;
                         */
                        if (first) {
                            value++;
                            return value;
                        } else if (second) {
                            return 2;
                        }
                        // result
                        return value;
                    }
                }
                """;
        String expected = source.replace("        /* next statement", "\n        /* next statement")
                .replace("            return value;", "\n            return value;")
                .replace("        // result", "\n        // result");
        check(source, expected);
        String guards = """
                class Example {
                    int run(int value) {
                        if (value < 0) return 0;
                        if (value == 0) return 1;
                        return value;
                    }
                    int only() { return 1; }
                    void last(boolean value) {
                        if (value) run(1);
                    }
                }
                """;
        check(
                guards,
                guards.replace("        if (value == 0)", "\n        if (value == 0)")
                        .replace("        return value;", "\n        return value;"));
        String cases = """
                class Example {
                    int run(int value) {
                        switch (value) {
                            case 1:
                                value++;
                                if (value < 0) return 0;
                                return value;
                            default:
                                return 0;
                        }
                    }
                }
                """;
        check(
                cases,
                cases.replace("                if (value < 0)", "\n                if (value < 0)")
                        .replace("                return value;", "\n                return value;"));
        String literals = String.join(
                "\n",
                "class Example {",
                "    String run() {",
                "        String text = \"\"\"",
                "                /* if (x) { return y; } */",
                "                // if (x) return y;",
                "                \"\"\";",
                "        char slash = '/';",
                "        String quoted = \"\\\" // if return /*\";",
                "        return text;",
                "    }",
                "}",
                "");
        check(literals, literals.replace("        return text;", "\n        return text;"));
        try {
            format("class Invalid { void run( }");
            throw new AssertionError("Malformed Java was accepted");
        } catch (IllegalArgumentException expectedError) {
            // Invalid input must fail before anything is sent to Spotless on stdout.
        }
    }

    private static void check(String source, String expected) throws Exception {
        String actual = format(source);

        if (!actual.equals(expected) || !format(actual).equals(actual))
            throw new AssertionError("Blank-line formatting or idempotence failed");
    }
}
