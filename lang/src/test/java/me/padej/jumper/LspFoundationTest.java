package me.padej.jumper;

import me.padej.jumper.ast.Expr;
import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.Stmt;
import me.padej.jumper.ast.Stmts;
import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.parser.ParseError;
import me.padej.jumper.parser.Parser;
import me.padej.jumper.parser.Range;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What lang gives a language server (lsp-plan.md, step 1): parsing without side effects in Java,
 * source ranges on the tree, a tolerant parse that reports every error, `jmp --check`.
 */
class LspFoundationTest {

    // ------------------------------------------------------------------ parsing does not initialize Java classes

    @Test
    void parsingDoesNotRunJavaStaticInitializers() {
        // InitTrace is a separate class: reading the counter does not initialize the probe
        java.util.concurrent.atomic.AtomicInteger inits = me.padej.jumper.fixtures.InitProbe.InitTrace.COUNT;
        String src = """
                import me.padej.jumper.fixtures.InitProbe;
                dyn k = InitProbe.class;
                dyn f() { return InitProbe.twice(InitProbe.value); }
                return f();
                """;
        new Parser(src).parseProgram();
        Parser.tolerant(src).parseTolerant();
        new Interpreter().check(src, null);
        assertEquals(0, inits.get(), "a parse ran the static initializer");
        // running is what initializes it, once
        assertEquals(84, Interpreter.runWithBigStack(() -> new Interpreter().eval(src)));
        assertEquals(1, inits.get());
    }

    // ------------------------------------------------------------------ source ranges

    private Map<Object, Range> ranges;

    private int[] range(Object node) {
        Range r = ranges.get(node);
        assertNotNull(r, "no range for " + node.getClass().getSimpleName());
        return new int[] {r.startLine(), r.startCol(), r.endLine(), r.endCol()};
    }

    private List<Stmt> top(String src) {
        Parser ps = new Parser(src).keepRanges();
        FunctionNode main = ps.parseProgram();
        ranges = ps.ranges();
        return List.of(((Stmts.Block) main.body).stmts);
    }

    @Test
    void aStrictParseKeepsNoRangesUnlessAsked() {
        Parser ps = new Parser("dyn x = 1 + 2;");
        ps.parseProgram();
        assertTrue(ps.ranges().isEmpty());
    }

    @Test
    void nodesCarryTheirSourceRange() {
        List<Stmt> st = top("dyn a = 1;\ndyn b = 2;\ndyn x = a + b * \"s\\n\".length();\nif (x) {\n  println(x);\n}\n");
        Stmts.VarDecl x = (Stmts.VarDecl) st.get(2);
        assertArrayEquals(new int[] {3, 1, 3, 32}, range(x));                  // `dyn x = ... ;` with the `;`
        assertArrayEquals(new int[] {3, 9, 3, 31}, range(x.init));             // `a + b * "s\n".length()`
        Expr mul = ((me.padej.jumper.ast.Exprs.Binary) x.init).right;
        assertArrayEquals(new int[] {3, 13, 3, 31}, range(mul));
        Expr call = ((me.padej.jumper.ast.Exprs.Binary) mul).right;
        assertArrayEquals(new int[] {3, 17, 3, 31}, range(call));
        // a string's end is its source end: `"s\n"` is 5 characters, its value 2
        assertArrayEquals(new int[] {3, 17, 3, 22}, range(((me.padej.jumper.ast.Exprs.MethodCall) call).obj));
        Stmts.If iff = (Stmts.If) st.get(3);
        assertArrayEquals(new int[] {4, 1, 6, 2}, range(iff));
        assertArrayEquals(new int[] {4, 5, 4, 6}, range(iff.cond));
        assertArrayEquals(new int[] {4, 8, 6, 2}, range(iff.then));           // the block, braces included
    }

    @Test
    void everyRangeLiesInsideItsParentsRange() throws Exception {
        // over the whole corpus of test scripts: a child never sticks out of its parent
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(scriptsDir())) { s.filter(p -> p.toString().endsWith(".jmp")).sorted().forEach(files::add); }
        assertFalse(files.isEmpty());
        int checked = 0;
        for (Path f : files) {
            Parser.Result r = Parser.tolerant(Files.readString(f)).source(f.getParent(), null).parseTolerant();
            if (r.program() == null) continue;
            checked += walk(r.program().body, null, r.ranges(), f.getFileName().toString(),
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        }
        assertTrue(checked > 1000, "checked " + checked);
    }

    private static Path scriptsDir() throws Exception {
        java.net.URL u = LspFoundationTest.class.getResource("/scripts");
        assertNotNull(u, "test scripts on the classpath");
        return Path.of(u.toURI());
    }

    /** Checks containment below `node`; returns the number of nodes with a range. */
    private static int walk(Object node, Range parent, Map<Object, Range> ranges, String file, java.util.Set<Object> seen) throws Exception {
        if (node == null || !seen.add(node)) return 0;
        Range r = ranges.get(node);
        int n = 0;
        if (r != null) {
            n = 1;
            assertTrue(r.startLine() < r.endLine() || r.startLine() == r.endLine() && r.startCol() <= r.endCol(),
                    file + ": inverted range " + node.getClass().getSimpleName());
            if (parent != null) {
                Range par = parent;
                assertTrue(par.contains(r), () -> file + ": " + node.getClass().getSimpleName() + " " + r + " outside its parent " + par);
            }
            parent = r;
        }
        if (node instanceof FunctionNode fn) return n + walk(fn.body, parent, ranges, file, seen);
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getName().equals("kids") || f.getName().equals("target")) continue;
                Class<?> t = f.getType();
                boolean one = Expr.class.isAssignableFrom(t) || Stmt.class.isAssignableFrom(t) || t == FunctionNode.class;
                boolean many = t.isArray() && (Expr.class.isAssignableFrom(t.getComponentType()) || Stmt.class.isAssignableFrom(t.getComponentType()));
                if (!one && !many) continue;
                f.setAccessible(true);
                Object v = f.get(node);
                // a function's body is its own range (a lambda inside an expression is checked against it)
                Range p = v instanceof FunctionNode ? null : parent;
                if (one) n += walk(v, p, ranges, file, seen);
                else if (v != null) for (Object o : (Object[]) v) n += walk(o, p, ranges, file, seen);
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ tolerant parse

    private static List<String> errors(String src) {
        List<String> out = new ArrayList<>();
        for (ParseError e : Parser.tolerant(src).parseTolerant().errors()) out.add(e.line + ":" + e.col + " " + e.reason);
        return out;
    }

    @Test
    void tolerantParseReportsEveryErrorOnce() {
        String src = String.join("\n",
                "dyn a = 1 +;",                                                  // 1
                "int b = \"s\";",                                                // 2
                "dyn c = 0x;",                                                   // 3
                "dyn s = \"ab\\q\" + 1;",                                        // 4
                "for (int i = 0; i < 3; i++) { dyn z = ; println(z); }",         // 5
                "if (a) { x = 1; } else { break; }",                             // 6
                "class P { int m() { return 1; } int m() { return 2; } }",       // 7
                "dyn ok = a + b + c + s;",                                       // 8: no error - a, b, c, s are declared
                "println(ok) # 1;",                                              // 9
                "nope(1);",                                                      // 10
                "}",                                                             // 11
                "println(ok);");                                                 // 12
        assertEquals(List.of(
                "1:12 Unexpected token ';'",
                "2:5 Cannot assign string to int",
                "3:9 Hex literal without digits: 0x",
                "4:12 Bad escape \\q",
                "5:39 Unexpected token ';'",
                "6:10 Undefined variable 'x'",
                "6:26 'break' outside of a loop",
                "7:37 Method 'm' is already declared in P",
                "9:13 Unexpected character '#'",
                "9:15 Expected ';' but got '1'",
                "10:1 Undefined variable 'nope'",
                "11:1 Unexpected '}'"), errors(src));
        // the strict parser still stops at the first one - the lexer's, which runs before parsing
        ParseError first = assertThrows(ParseError.class, () -> new Parser(src).parseProgram());
        assertEquals(3, first.line);
        assertEquals(9, first.col);
    }

    @Test
    void tolerantParseOfAValidFileIsCleanAndMatchesTheStrictOne() throws Exception {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(scriptsDir())) { s.filter(p -> p.toString().endsWith(".jmp")).sorted().forEach(files::add); }
        for (Path f : files) {
            String src = Files.readString(f);
            List<ParseError> strict = new ArrayList<>();
            try { new Parser(src).source(f.getParent(), null).parseProgram(); } catch (ParseError e) { strict.add(e); }
            Parser.Result r = Parser.tolerant(src).source(f.getParent(), null).parseTolerant();
            if (strict.isEmpty()) assertEquals(List.of(), r.errors(), f.toString());
            else assertEquals(strict.get(0).getMessage(), r.errors().get(0).getMessage(), f.toString());
        }
    }

    @Test
    void tolerantParseSurvivesAnyDamage() {
        // random damage to a real script: only ParseErrors, always an end, never a hang
        String base = """
                class Vec { double x, y; Vec(double x, double y) { this.x = x; this.y = y; }
                    Vec plus(Vec o) { return new Vec(x + o.x, y + o.y); } }
                dyn sum(arr) { dyn s = 0; for (dyn v : arr) { if (v > 2) continue; s += v; } return s; }
                dyn t = { a: 1, b: [1, 2, 3], f: (q) -> q * 2 };
                dyn r = switch (t.a) { case 1 -> "one"; default -> "?"; };
                try { throw "x"; } catch (e) { println(e); } finally { println("done"); }
                int n = 0; while (n < 10) { n++; }
                return sum(t.b) + new Vec(1, 2).plus(new Vec(3, 4)).x;
                """;
        String junk = "{}()[];,.=+-*/\"'x1 \n";
        java.util.Random rnd = new java.util.Random(7);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(60), () -> {
            for (int k = 0; k < 2000; k++) {
                StringBuilder sb = new StringBuilder(base);
                int muts = 1 + rnd.nextInt(8);
                for (int m = 0; m < muts && sb.length() > 0; m++) {
                    int i = rnd.nextInt(sb.length());
                    switch (rnd.nextInt(3)) {
                        case 0 -> sb.deleteCharAt(i);
                        case 1 -> sb.insert(i, junk.charAt(rnd.nextInt(junk.length())));
                        default -> sb.delete(i, Math.min(sb.length(), i + rnd.nextInt(20)));
                    }
                }
                String s = sb.toString();
                Parser.Result r = Parser.tolerant(s).parseTolerant();
                // a strict parse fails exactly when the tolerant one reports something
                boolean strictFails;
                try { new Parser(s).parseProgram(); strictFails = false; } catch (ParseError e) { strictFails = true; }
                assertEquals(strictFails, !r.ok(), s);
            }
        });
    }

    @Test
    void tolerantLexerStandsInForBrokenTokens() {
        // a bad number becomes a zero: the rest of the statement parses, no extra errors
        assertEquals(List.of("1:9 Exponent without digits: 1e"), errors("dyn x = 1e + 2;"));
        assertEquals(List.of("1:9 Long literal too large: 99999999999999999999L", "2:9 Hex literal too large for int (use L suffix): 0x1FFFFFFFF"),
                errors("dyn x = 99999999999999999999L;\ndyn y = 0x1FFFFFFFF;"));
        // a broken string ends at its quote or at the end of the line
        assertEquals(List.of("1:11 Bad escape \\u: expected 4 hex digits"), errors("dyn s = \"a\\u12\" + \"b\";"));
        assertEquals(List.of("1:9 Newline in string literal", "2:1 Expected ';' but got 'dyn'"), errors("dyn s = \"open\ndyn t = 1;"));
        assertEquals(List.of("2:3 Unterminated comment"), errors("dyn a = 1;\n  /* never closed\ndyn b = 2;"));
    }

    @Test
    void manyErrorsAreCapped() {
        String src = "dyn x = ;\n".repeat(1000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> {
            List<ParseError> e = Parser.tolerant(src).parseTolerant().errors();
            assertEquals(200, e.size());
        });
    }

    // ------------------------------------------------------------------ jmp --check

    @Test
    void checkReportsWithoutRunning(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad.jmp"), cfg = dir.resolve("c.jmc"), ok = dir.resolve("ok.jmp"), ran = dir.resolve("ran.txt");
        Files.writeString(bad, "dyn a = 1 +;\nint b = \"s\";\n");
        Files.writeString(cfg, "int port = 25565;\nfor (dyn i : [1]) {}\n");
        // a script that would leave a trace if it ran
        Files.writeString(ok, "import java.nio.file.Files; import java.nio.file.Path;\nFiles.writeString(Path.of(\""
                + ran.toString().replace("\\", "\\\\") + "\"), \"x\");\n");
        PrintCapture out = new PrintCapture();
        int code;
        try {
            code = Main.check(new String[] {bad.toString(), cfg.toString(), ok.toString()});
        } finally {
            out.close();
        }
        assertEquals(1, code);
        assertEquals(List.of(bad + ":1:12: error: Unexpected token ';'", bad + ":2:5: error: Cannot assign string to int",
                cfg + ":2:1: error: A loop is not allowed in a config (.jmc)"), out.errors());
        assertFalse(Files.exists(ran), "--check ran the script");
        out = new PrintCapture();
        try {
            assertEquals(0, Main.check(new String[] {ok.toString()}));
            assertEquals(2, Main.check(new String[] {dir.resolve("missing.jmp").toString()}));
        } finally {
            out.close();
        }
        assertFalse(Files.exists(ran));
    }

    /** Captures System.out for the duration. */
    private static final class PrintCapture implements AutoCloseable {
        private final java.io.PrintStream saved = System.out, savedErr = System.err;
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        PrintCapture() {
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        }

        List<String> lines() {
            String s = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            return s.isEmpty() ? List.of() : List.of(s.split("\\R"));
        }

        /** Without the `note:` lines (how the context of a file was found). */
        List<String> errors() {
            return lines().stream().filter(l -> !l.contains(": note: ")).toList();
        }

        @Override
        public void close() {
            System.setOut(saved);
            System.setErr(savedErr);
        }
    }
}
