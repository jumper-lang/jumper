package me.padej.jumper;

import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.parser.ParseError;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the issues of lang-issues.md (P = parser/lexer, S = semantics, R = runtime flags).
 * Each test fails on the code before the fix. The class runs in every mode of the test script
 * (tier1=force, tier1=0, tableprims=0, layout=0), so each T0/T1 divergence is checked in both tiers.
 */
class IssueFixesTest {
    private static Object run(String src) {
        return Interpreter.runWithBigStack(() -> new Interpreter().eval(src));
    }

    private static ParseError parseError(String src) {
        return assertThrows(ParseError.class, () -> run(src), src);
    }

    private static void assertParseError(String src, String fragment) {
        ParseError e = parseError(src);
        assertTrue(e.getMessage().contains(fragment), () -> src + " -> " + e.getMessage());
    }

    // ------------------------------------------------------------------ P1: cyclic inheritance

    @Test
    void p1CyclicInheritanceIsAParseErrorNotAHang() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertParseError("class A extends A { int x; }", "Cyclic inheritance");
            assertParseError("class A extends A { void m() { dyn q = this.x; } }", "Cyclic inheritance");
            // through a hoisted sibling: A extends B, B extends A
            assertParseError("class A extends B { int x; }\nclass B extends A { int y; }", "Cyclic inheritance");
            assertParseError("class A extends C {}\nclass B extends A {}\nclass C extends B {}", "Cyclic inheritance");
        });
        ParseError e = parseError("class A extends A {}");
        assertEquals(1, e.line);
        // a normal chain still works
        assertEquals(3, run("class A { int a = 1; } class B extends A { int b = 2; } B o = new B(); return o.a + o.b;"));
    }

    // ------------------------------------------------------------------ P2: the same module in sibling blocks

    @Test
    void p2ModuleImportedInTwoSiblingBlocksIsVisibleInBoth(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("lib.jmp"), "int helper(int x) { return x * 10; }\n");
        java.nio.file.Files.writeString(dir.resolve("main.jmp"), """
                int r = 0;
                if (true) { import "lib.jmp"; r += helper(1); }
                if (true) { import "lib.jmp"; r += helper(2); }
                return r;
                """);
        assertEquals(30, Interpreter.runWithBigStack(() -> new Interpreter().evalFile(dir.resolve("main.jmp"))));
        // twice in the same scope is still a no-op (not a conflict)
        java.nio.file.Files.writeString(dir.resolve("twice.jmp"), "import \"lib.jmp\";\nimport \"lib.jmp\";\nreturn helper(3);");
        assertEquals(30, Interpreter.runWithBigStack(() -> new Interpreter().evalFile(dir.resolve("twice.jmp"))));
    }

    // ------------------------------------------------------------------ P3, P4, P5: lexer errors

    @Test
    void p3BadLiteralsAreParseErrorsWithAPosition() {
        for (String src : new String[] {"dyn x = 0x;", "dyn x = 1e;", "dyn x = 1e+;", "dyn x = 9223372036854775808L;",
                "dyn x = 99999999999999999999L;", "dyn x = \"a\\", "dyn x = \"\\u", "dyn x = \"\\u12\";", "dyn x = \"\\q\";"}) {
            ParseError e = parseError(src);
            assertEquals(1, e.line, src);
        }
        assertParseError("dyn x = 0x;", "Hex literal without digits");
        assertParseError("dyn x = 1e;", "Exponent without digits");
        assertParseError("dyn x = 9223372036854775808L;", "Long literal too large");
        assertParseError("dyn x = \"\\u12\";", "expected 4 hex digits");
        // the escape error points at the backslash
        ParseError esc = parseError("dyn x = \"ab\\q\";");
        assertEquals(12, esc.col);
        // the edge values still work
        assertEquals(Long.MIN_VALUE, run("return -9223372036854775808L;"));
        assertEquals(Long.MAX_VALUE, run("return 9223372036854775807L;"));
        assertEquals(Integer.MIN_VALUE, run("return -2147483648;"));
        assertEquals(1.5e10, run("return 1.5e10;"));
        assertEquals(2.0E-3, run("return 2e-3;"));
        assertEquals("\u00e9", run("return \"\\u00e9\";"));
    }

    @Test
    void p4HexThatDoesNotFitIsAnError() {
        assertParseError("dyn x = 0x1FFFFFFFF;", "too large for int");
        assertParseError("dyn x = 0x1FFFFFFFFFFFFFFFFL;", "too large for long");
        assertEquals(-1, run("return 0xFFFFFFFF;"));
        assertEquals(0x1FFFFFFFFL, run("return 0x1FFFFFFFFL;"));
        assertEquals(-1L, run("return 0xFFFFFFFFFFFFFFFFL;"));
        assertEquals(255, run("return 0x00000000000000FF;"));   // leading zeros do not count
    }

    @Test
    void p5UnterminatedBlockCommentIsAnError() {
        ParseError e = parseError("int a = 1;\n  /* never closed\nint b = 2;");
        assertTrue(e.getMessage().contains("Unterminated comment"), e.getMessage());
        assertEquals(2, e.line);
        assertEquals(3, e.col);
        // Config: the fast reader and the full path agree
        assertThrows(ParseError.class, () -> me.padej.jumper.interp.Config.parse("int a = 1; /* x"));
        assertThrows(ParseError.class, () -> me.padej.jumper.interp.Config.parseFull("int a = 1; /* x"));
        assertEquals(2, run("/* ok */ return /**/ 2; /* tail */"));
    }

    // ------------------------------------------------------------------ P6: this/super in static code

    @Test
    void p6ThisAndSuperInStaticCodeSayWhatIsWrong() {
        assertParseError("class C { static dyn m() { return this; } }", "not available in a static method");
        assertParseError("class C { static dyn f = this; }", "static initializer");
        assertParseError("class P { dyn m() { return 1; } } class C extends P { static dyn s() { return super.m(); } }",
                "'super' is not available in a static method");
        assertParseError("dyn f() { return this; }", "'this' is only valid inside a class");
        assertParseError("dyn f() { return super.x; }", "'super' is only valid inside a class");
        assertParseError("class C { int x; static int bad() { return x; } }", "instance member 'x'");
    }

    // ------------------------------------------------------------------ P7: break/continue outside a loop

    @Test
    void p7BreakAndContinueOutsideALoopAreErrors() {
        assertParseError("break;", "'break' outside of a loop");
        assertParseError("dyn f() { continue; }", "'continue' outside of a loop");
        assertParseError("if (true) { break; }", "outside of a loop");
        // a lambda inside a loop does not see the loop
        assertParseError("while (true) { dyn f = () -> { break; }; }", "outside of a loop");
        assertParseError("for (int i = 0; i < 3; i++) { dyn g() { continue; } }", "outside of a loop");
        // a switch is not a loop, but a switch inside a loop is fine
        assertEquals(3, run("int n = 0; for (int i = 0; i < 10; i++) { switch (i) { case 3 -> break; default -> n++; } } return n;"));
        assertEquals(12, run("int s = 0; for (dyn v : [1, 2, 3, 4, 5]) { if (v == 3) continue; if (v == 5) break; s += v; }"
                + " int k = 0; do { k++; if (k < 5) continue; break; } while (true); return s + k + 0;"));
        assertEquals(4, run("int i = 0; while (true) { i++; if (i == 4) break; } return i;"));
    }

    // ------------------------------------------------------------------ P8: duplicate methods

    @Test
    void p8DuplicateMethodIsAnError() {
        ParseError e = parseError("class C {\n  int m() { return 1; }\n  int m(int x) { return 2; }\n}");
        assertTrue(e.getMessage().contains("Method 'm' is already declared in C"), e.getMessage());
        assertEquals(3, e.line);
        assertParseError("class C { static int m() { return 1; } static int m() { return 2; } }", "already declared");
        assertParseError("class C { int m() { return 1; } static int m() { return 2; } }", "already declared");
        // overriding in a subclass is not a duplicate
        assertEquals(2, run("class P { int m() { return 1; } } class C extends P { int m() { return 2; } } return new C().m();"));
    }

    // ------------------------------------------------------------------ P9: hidden switch variables

    @Test
    void p9SwitchExpressionDoesNotLeakItsHiddenVariable() throws Exception {
        String src = "dyn k = \"b\";\ndyn v = switch (k + \"\") { case \"a\" -> 1; case \"b\" -> 2; default -> 0; };\n";
        me.padej.jumper.interp.Script s = new Interpreter().script(src);
        Interpreter.runWithBigStack(s::run);
        assertEquals(java.util.Set.of("k", "v"), s.names());
        assertEquals(2, s.get("v"));
        // the configuration result has only the declared names
        Object cfg = me.padej.jumper.interp.Config.parseFull(src);
        assertEquals(java.util.Set.of("k", "v"), new java.util.HashSet<>(((me.padej.jumper.runtime.JTable) cfg).keys()));
        // ScriptEngine: no <switch..> binding
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.eval(src);
        for (String key : engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).keySet())
            assertFalse(key.startsWith("<"), key);
        assertEquals(2, engine.get("v"));
        // nested switch expressions and switches in a loop still work
        assertEquals("b/y", run("dyn a = \"q\" + 1; dyn r = switch (a) { case \"q1\" -> \"b/\" + switch (a + 1) { case \"q11\" -> \"y\"; default -> \"n\"; }; default -> \"?\"; }; return r;"));
    }

    // ------------------------------------------------------------------ P10: Java import over a binding

    @Test
    void p10JavaImportDoesNotSilentlyReplaceABinding() {
        assertParseError("dyn List = 1; import java.util.List;", "conflicts with a declaration");
        assertParseError("dyn ArrayList() { return 1; } import java.util.ArrayList;", "conflicts with a declaration");
        // the same class twice is fine; in an inner block it shadows
        assertEquals(0, run("import java.util.ArrayList; import java.util.ArrayList; return new ArrayList().size();"));
        assertEquals(1, run("dyn List = 1; if (true) { import java.util.List; } return List;"));
    }

    // ------------------------------------------------------------------ S1: ternary of int and double

    @Test
    void s1TernaryHasItsStaticTypeInBothTiers() {
        // T0 widened to 1.0, T1 kept 1
        assertEquals(1.0, run("dyn f(boolean c) { dyn x = c ? 1 : 2.5; return x; } f(true); f(false); return f(true);"));
        assertEquals(2.5, run("dyn f(boolean c) { dyn x = c ? 1 : 2.5; return x; } return f(false);"));
        assertEquals(1.0, run("boolean c = true; return c ? 1 : 2.5;"));
        assertEquals(3L, run("dyn f(boolean c) { dyn x = c ? 3 : 4L; return x; } return f(true);"));
        // an unboxed `dyn` operand is not widened by what unboxing learnt (T0: its static type is dyn)
        String unboxed = "dyn f(boolean c) { dyn n = 0; for (int i = 0; i < 3; i++) n += 1; dyn x = c ? n : 2.5; return x; } ";
        assertEquals(3, run(unboxed + "f(false); return f(true);"));
        assertEquals(2.5, run(unboxed + "return f(false);"));
        assertEquals(3.0, run("dyn f(boolean c) { dyn n = 0; for (int i = 0; i < 3; i++) n += 1; double x = c ? n : 2.5; return x; } return f(true);"));
    }

    // ------------------------------------------------------------------ S2: calls in Tier 1 = calls in Tier 0

    @Test
    void s2FunctionCanBeCalledAboveItsDeclaration() {
        assertEquals(4, run("dyn r = twice(2);\ndyn twice(x) { return x * 2; }\nreturn r;"));
        assertEquals(10, run("dyn outer() { dyn r = inner(5); dyn inner(int x) { return x * 2; } return r; } return outer();"));
        // mutual recursion, called before both declarations
        assertEquals(true, run("dyn r = even(10);\nboolean even(int n) { return n == 0 ? true : odd(n - 1); }\n"
                + "boolean odd(int n) { return n == 0 ? false : even(n - 1); }\nreturn r;"));
        // the value is the declaration's closure: it sees the block's variables as they are at the call
        assertEquals(7, run("int k = 7; dyn r = get(); dyn get() { return k; } return r;"));
    }

    @Test
    void s2TypedParametersConvertTheSameWayInBothTiers() {
        // null into an int / long / double / boolean parameter: the type's default (T1 used to throw)
        assertEquals(0, run("int twice(int x) { return x * 2; } dyn n = null; twice(1); return twice(n);"));
        assertEquals(1L, run("long inc(long x) { return x + 1; } dyn n = null; inc(1); return inc(n);"));
        assertEquals(0.5, run("double half(double x) { return x + 0.5; } dyn n = null; half(1); return half(n);"));
        assertEquals(false, run("boolean id(boolean b) { return b; } dyn n = null; id(true); return id(n);"));
        // a boolean parameter takes only a boolean (T1 used to apply truthiness)
        assertThrows(me.padej.jumper.runtime.JmpError.class,
                () -> run("boolean id(boolean b) { return b; } dyn one = 1; id(true); return id(one);"));
        assertThrows(me.padej.jumper.runtime.JmpError.class,
                () -> run("int twice(int x) { return x * 2; } dyn s = \"a\"; twice(1); return twice(s);"));
        // widening still works; methods and constructors the same
        assertEquals(6.0, run("double d(double x) { return x * 2; } dyn i = 3; d(1.0); return d(i);"));
        assertEquals(1, run("class C { int m(int x) { return x + 1; } } dyn n = null; C c = new C(); c.m(1); return c.m(n);"));
        assertEquals(0, run("class P { int v; P(int x) { v = x; } } dyn n = null; new P(1); return new P(n).v;"));
        assertEquals(false, run("class C { boolean m(boolean b) { return b; } } dyn n = null; C c = new C(); c.m(true); return c.m(n);"));
    }

    // ------------------------------------------------------------------ S3: for-each over Java arrays

    @Test
    void s3ForEachNormalizesJavaArrayElementsInBothTiers() {
        assertEquals("string:a,string:b,", run("dyn s = \"\"; for (dyn ch : \"ab\".toCharArray()) s = s + type(ch) + \":\" + ch + \",\"; return s;"));
        assertEquals(1, run("int n = 0; for (dyn ch : \"ab\".toCharArray()) if (ch == \"b\") n++; return n;"));
        assertEquals("int:97,", run("dyn s = \"\"; for (dyn b : \"a\".getBytes()) s = s + type(b) + \":\" + b + \",\"; return s;"));
        assertEquals(3, run("int t = 0; for (int b : \"\\u0001\\u0002\".getBytes()) t += b; return t;"));
    }

    // ------------------------------------------------------------------ S4: shifts

    @Test
    void s4ShiftHasTheTypeOfItsLeftOperand() {
        assertEquals(8, run("int a = 1; long b = 3L; dyn r = a << b; return r;"));
        assertEquals("int", run("int a = 1; long b = 3L; return type(a << b);"));
        assertEquals(2, run("int a = 1; long b = 33L; return a << b;"));                // int shift: 33 & 31 = 1
        assertEquals(2L << 32, run("long a = 2L; int b = 32; return a << b;"));       // long shift stays long
        assertEquals(-1 >>> 28, run("int a = -1; long b = 28L; return a >>> b;"));
        assertEquals(-4, run("int a = -8; long b = 1L; return a >> b;"));
        // the same as the dynamic path
        assertEquals(run("dyn a = 1; dyn b = 33L; return a << b;"), run("int a = 1; long b = 33L; return a << b;"));
        assertEquals(8, run("int f(int a, long b) { int r = a << b; return r; } return f(1, 3L);"));
    }

    // ------------------------------------------------------------------ S5: compound assignment to an engine global

    @Test
    void s5CompoundAssignmentToATopLevelNameInvalidatesCallSites() throws Exception {
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.eval("dyn step(x) { return x + 1; }\ndyn run() { return step(1); }");
        javax.script.Invocable inv = (javax.script.Invocable) engine;
        for (int i = 0; i < 3; i++) assertEquals(2, inv.invokeFunction("run"));
        engine.eval("dyn spoil() { step += \"!\"; }\nspoil();");
        assertTrue(engine.get("step") instanceof String);
        // step is a string now: run() must not call the old function through a stale site
        assertThrows(Exception.class, () -> inv.invokeFunction("run"));
        // and a numeric top-level value read by a compiled function sees the compound update
        engine.eval("dyn total = 0;\ndyn add(x) { total += x; return total; }\ndyn read() { return total; }");
        inv.invokeFunction("add", 5);
        inv.invokeFunction("add", 6);
        assertEquals(11, inv.invokeFunction("read"));
    }

    // ------------------------------------------------------------------ S6: one conversion into typed locations

    @Test
    void s6TypedLocationsTakeADynamicValueLikeAParameter() {
        // null -> the default (a parameter already did this; a local threw)
        assertEquals(0, run("dyn n = null; int x = n; return x;"));
        assertEquals(0.0, run("dyn n = null; double x = 1.5; x = n; return x;"));
        assertEquals(0, run("int f() { dyn n = null; return n; } f(); return f();"));
        assertEquals(0, run("dyn f() { dyn n = null; int x = 5; x = n; return x; } f(); return f();"));
        // boolean: no truthiness (it used to take 1 as true)
        for (String src : new String[] {"dyn one = 1; boolean b = one;", "boolean b = false; dyn one = 1; b = one;",
                "boolean f() { dyn one = 1; return one; } f();",
                "class C { boolean f; void set(dyn v) { f = v; } } new C().set(1);",
                "class C { static boolean f; static void set(dyn v) { f = v; } } C.set(\"x\");"}) {
            me.padej.jumper.runtime.JmpError e = assertThrows(me.padej.jumper.runtime.JmpError.class, () -> run(src), src);
            assertTrue(e.getMessage().contains("Expected boolean"), () -> src + " -> " + e.getMessage());
        }
        assertEquals(false, run("class C { boolean f = true; void set(dyn v) { f = v; } } C c = new C(); c.set(null); return c.f;"));
        assertEquals(true, run("dyn t = true; boolean b = t; return b;"));
        // a wrong type is still an error, with the same message
        me.padej.jumper.runtime.JmpError e = assertThrows(me.padej.jumper.runtime.JmpError.class, () -> run("dyn d = 1.5; int x = d;"));
        assertTrue(e.getMessage().contains("Expected int, got double"), e.getMessage());
        // conditions keep truthiness
        assertEquals(1, run("dyn one = 1; if (one) return 1; return 0;"));
        // widening from dyn
        assertEquals(3L, run("dyn i = 3; long l = i; return l;"));
        assertEquals(3.0, run("dyn f() { dyn i = 3; double d = 0; d = i; return d; } f(); return f();"));
    }

    // ------------------------------------------------------------------ S7: typed top-level names of an engine

    @Test
    void s7TypedTopLevelNamesKeepTheirType() throws Exception {
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        assertThrows(javax.script.ScriptException.class, () -> engine.eval("int n = 1; n = \"s\";"));
        assertThrows(javax.script.ScriptException.class, () -> engine.eval("int m = 1; dyn s = \"x\"; m = s;"));
        engine.eval("double d = 1; d = 2;");
        assertEquals(2.0, engine.get("d"));
        engine.eval("int k = 5; dyn z = null; k = z;");
        assertEquals(0, engine.get("k"));
        engine.eval("int c = 1; c += 2; dyn bump() { c += 1; return c; } bump();");
        assertEquals(4, engine.get("c"));
        assertThrows(javax.script.ScriptException.class, () -> engine.eval("int q = 1; q += 0.5;"));
        engine.eval("dyn free = 1; free = \"s\";");   // dyn stays free
        assertEquals("s", engine.get("free"));
    }

    // ------------------------------------------------------------------ S8: throwing a checked Java exception

    @Test
    void s8CheckedExceptionIsCaughtAsItself() {
        Object caught = run("import java.io.IOException; try { throw new IOException(\"x\"); } catch (e) { return e; }");
        assertTrue(caught instanceof java.io.IOException, String.valueOf(caught));
        assertEquals("x", ((Throwable) caught).getMessage());
        assertEquals("x", run("import java.io.IOException; dyn f() { throw new IOException(\"x\"); } try { f(); } catch (e) { return e.getMessage(); }"));
        // runtime exceptions and plain values as before
        assertTrue(run("try { throw new java.lang.IllegalStateException(\"s\"); } catch (e) { return e; }") instanceof IllegalStateException);
        assertEquals("v", run("try { throw \"v\"; } catch (e) { return e; }"));
        // uncaught: still an error with the exception in the message
        me.padej.jumper.runtime.JmpError e = assertThrows(me.padej.jumper.runtime.JmpError.class,
                () -> run("import java.io.IOException; throw new IOException(\"boom\");"));
        assertTrue(e.getMessage().contains("IOException: boom"), e.getMessage());
    }

    // ------------------------------------------------------------------ R1, R2: switches

    @Test
    void r1r2OffValuesMeanOff() throws Exception {
        for (String off : new String[] {"0", "off", "false", "OFF", " 0 "}) assertTrue(me.padej.jumper.runtime.Opts.offValue(off), off);
        for (String on : new String[] {"1", "on", "true", "force", ""}) assertFalse(me.padej.jumper.runtime.Opts.offValue(on), on);
        System.setProperty("jmp.issuetest", "0");
        assertFalse(me.padej.jumper.runtime.Opts.flag("jmp.issuetest", "JMP_ISSUETEST_NONE"));
        System.setProperty("jmp.issuetest", "");
        assertTrue(me.padej.jumper.runtime.Opts.flag("jmp.issuetest", "JMP_ISSUETEST_NONE"));
        System.clearProperty("jmp.issuetest");
        assertFalse(me.padej.jumper.runtime.Opts.flag("jmp.issuetest", "JMP_ISSUETEST_NONE"));
        // the real switches, read once per JVM: a child JVM
        assertEquals("false", child(java.util.Map.of("JMP_TIER1", "0")));
        assertEquals("false", child(java.util.Map.of("JMP_TIER1", "false")));
        assertEquals("true", child(java.util.Map.of("JMP_TIER1", "on")));
        assertEquals("false\ndebug=true", child(java.util.Map.of(), "-Djmp.tier1=0", "-Djmp.debug"));
        assertTrue(child(java.util.Map.of(), "-Djmp.debug=0").endsWith("debug=false"));
        assertTrue(child(java.util.Map.of("JMP_DEBUG", "0")).endsWith("debug=false"));
        assertTrue(child(java.util.Map.of("JMP_DEBUG", "1")).endsWith("debug=true"));
    }

    /** Prints Jit.ENABLED (and debug=... when asked) in a fresh JVM. */
    public static void main(String[] a) throws Exception {
        java.lang.reflect.Field d = me.padej.jumper.jit.Jit.class.getDeclaredField("DEBUG");
        d.setAccessible(true);
        System.out.print(me.padej.jumper.jit.Jit.ENABLED + (a.length > 0 ? "\ndebug=" + d.get(null) : ""));
    }

    private static String child(java.util.Map<String, String> env, String... props) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(java.util.List.of(props));
        cmd.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), IssueFixesTest.class.getName()));
        boolean debug = env.containsKey("JMP_DEBUG") || java.util.Arrays.stream(props).anyMatch(p -> p.startsWith("-Djmp.debug"));
        if (debug) cmd.add("debug");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("JMP_TIER1");
        pb.environment().remove("JMP_DEBUG");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().putAll(env);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor());
        return out;
    }
}
