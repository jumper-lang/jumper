package me.padej.jumper;

import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.parser.ParseError;
import me.padej.jumper.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `import "m.jmp"` in a check (Interpreter.check: `jmp --check`, the language server) parses the module and
 * never runs it: its functions and classes are known from their declarations. A normal run still runs it.
 */
class ModuleCheckTest {
    private static List<String> check(Path file) throws Exception {
        Parser.Result r = Interpreter.runWithBigStack(() -> new Interpreter().check(Files.readString(file), file));
        return r.errors().stream().map(e -> e.line + ":" + e.col + " " + e.reason).toList();
    }

    private static final String LIB = """
            println("MODULE RAN");
            class Vec { int x; Vec(int x) { this.x = x; } int twice() { return x * 2; } static Vec zero() { return new Vec(0); } }
            int helper(int a) { return a + 1; }
            dyn counter = 0;
            while (true) { counter++; }
            """;

    @Test
    void aCheckKnowsTheModulesNamesWithoutRunningIt(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lib.jmp"), LIB);
        Path main = Files.writeString(dir.resolve("main.jmp"), """
                import "lib";
                Vec v = new Vec(3);
                Vec z = Vec.zero();
                int r = helper(v.twice()) + counter;
                nope();
                new Vec(1, 2);
                class Big extends Vec { Big() { super(9); } }
                """);
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(printed));
        List<String> errors;
        long t0 = System.nanoTime();
        try {
            errors = check(main);
        } finally {
            System.setOut(out);
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "the module's endless loop never ran");
        assertEquals("", printed.toString(), "the module's println never ran");
        // its class is a type with a known constructor, its function and variable are names
        assertEquals(List.of("5:1 Undefined variable 'nope'", "6:1 Constructor Vec expects 1 argument, got 2"), errors);
    }

    @Test
    void errorsOfTheModuleAreReportedOnTheImport(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad.jmp"), "dyn ok() { return 1; }\ndyn x = ;\n");
        Path main = Files.writeString(dir.resolve("main.jmp"), "import \"bad\";\ndyn z = ok();\ndyn w = 1 +;\n");
        // the names it does declare are still imported: ok() is known
        assertEquals(List.of("1:1 In module bad, line 2: Unexpected token ';'", "3:12 Unexpected token ';'"), check(main));
    }

    @Test
    void cyclesAndNestedModulesInACheck(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("c1.jmp"), "import \"c2\";\n");
        Files.writeString(dir.resolve("c2.jmp"), "import \"c1\";\n");
        List<String> cyc = check(dir.resolve("c1.jmp"));
        assertEquals(1, cyc.size(), cyc.toString());
        assertTrue(cyc.get(0).startsWith("1:1 In module c2, line 1: ") && cyc.get(0).contains("Circular import"), cyc.toString());

        Files.writeString(dir.resolve("base.jmp"), "println(\"BASE RAN\");\nclass Shape { double area() { return 0; } }\n");
        Files.writeString(dir.resolve("mid.jmp"), "import \"base\";\nShape unit() { return new Shape(); }\n");
        Path main = Files.writeString(dir.resolve("main.jmp"), "import \"mid\";\ndyn s = unit();\ndyn t = new Shape();\n");
        // mid's own import is not re-exported: Shape is unknown in main (as when running)
        assertEquals(List.of("3:13 Undefined variable 'Shape'"), check(main));
    }

    @Test
    void runningStillRunsTheModule(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lib.jmp"), "dyn counter = 41;\ncounter++;\nint helper(int a) { return a + counter; }\n");
        Path main = Files.writeString(dir.resolve("main.jmp"), "import \"lib\";\nreturn helper(1) + counter;\n");
        assertEquals(85, Interpreter.runWithBigStack(() -> new Interpreter().evalFile(main)));
        // and a broken module is an error of the import, as before
        Files.writeString(dir.resolve("bad.jmp"), "dyn x = ;\n");
        Path b = Files.writeString(dir.resolve("b.jmp"), "import \"bad\";\n");
        assertThrows(ParseError.class, () -> Interpreter.runWithBigStack(() -> new Interpreter().evalFile(b)));
    }
}
