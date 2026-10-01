package me.padej.jumper;

import org.junit.jupiter.api.Test;
import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.parser.ParseError;
import me.padej.jumper.runtime.JmpError;
import me.padej.jumper.runtime.JFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Targeted checks of semantics and error messages. */
class LanguageTest {
    private Object eval(String src) {
        return new Interpreter().eval("return " + src + ";");
    }

    @Test
    void arithmetic() {
        assertEquals(7, eval("1 + 2 * 3"));
        assertEquals(0, eval("1 / 2"));
        assertEquals(0.5, eval("1.0 / 2"));
        assertEquals(30L, eval("10L * 3"));
        assertEquals("a12.5", eval("\"a\" + 1 + 2.5"));
    }

    @Test
    void typedVariableRejectsWrongTypeStatically() {
        ParseError e = assertThrows(ParseError.class, () -> new Interpreter().eval("int x = 1.5;"));
        assertTrue(e.getMessage().contains("Cannot assign double to int"));
        assertEquals(1, e.line);
    }

    @Test
    void typedVariableRejectsWrongTypeAtRuntime() {
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval("dyn d = 1.5;\nint x = d;"));
        assertTrue(e.getMessage().contains("Expected int, got double"));
        assertEquals(2, e.line());
    }

    @Test
    void staticTypeErrorsInExpressions() {
        assertThrows(ParseError.class, () -> new Interpreter().eval("boolean b = true; dyn x = b + 1;"));
        assertThrows(ParseError.class, () -> new Interpreter().eval("int x = 1; x = \"s\";"));
        assertThrows(ParseError.class, () -> new Interpreter().eval("double d = 1.5; dyn x = d & 1;"));
    }

    @Test
    void functionKeywordIsGone() {
        ParseError e = assertThrows(ParseError.class, () -> new Interpreter().eval("function f() { return 1; }"));
        assertTrue(e.getMessage().contains("'dyn name(...)'"));
        assertThrows(ParseError.class, () -> new Interpreter().eval("void f() { return 1; }"));
        assertThrows(ParseError.class, () -> new Interpreter().eval("class A { m() { return 1; } }"));
    }

    @Test
    void classesAreSealed() {
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval("class P { dyn x; }\ndyn p = new P();\np.y = 1;"));
        assertEquals("No field 'y' in P", e.message());
        assertEquals(3, e.line());
        assertThrows(JmpError.class, () -> new Interpreter().eval("class P { dyn x; } dyn p = new P(); return p.nope;"));
        assertEquals(7, new Interpreter().eval("class P { int x = 3; int twice() { return x * 2; } } dyn p = new P(); p.x++; return p.twice() - 1;"));
        assertThrows(ParseError.class, () -> new Interpreter().eval("class A { dyn x; } class B extends A { dyn x; }"));
    }

    @Test
    void classTypedVariables() {
        // a class type is checked statically where possible, at runtime otherwise
        ParseError pe = assertThrows(ParseError.class, () -> new Interpreter().eval("class V { dyn x; } V v = 5;"));
        assertTrue(pe.getMessage().contains("Cannot assign int to V"));
        JmpError re = assertThrows(JmpError.class, () -> new Interpreter().eval("class V { dyn x; } dyn five = 5; V v = five;"));
        assertEquals("Expected V, got int", re.message());
        assertEquals(6.0, new Interpreter().eval("""
                class V { double x; V(double x) { this.x = x; } V plus(V o) { return new V(x + o.x); } }
                V a = new V(1); V b = a.plus(new V(2)).plus(a).plus(a);
                double f(V v) { return v.x + 1; }
                return f(b);
                """));
    }

    @Test
    void staticMembers() {
        assertEquals(3, new Interpreter().eval("""
                class C {
                    static int made = 0;
                    static int total() { return made; }
                    C() { made++; }
                }
                new C(); new C(); C.made++;
                return C.total();
                """));
        // the static member's type is known at parse time; the class name is not a variable
        ParseError pe = assertThrows(ParseError.class, () -> new Interpreter().eval("class C { static int n; } C.n = \"s\";"));
        assertTrue(pe.getMessage().contains("Cannot assign string to int"));
        assertThrows(ParseError.class, () -> new Interpreter().eval("class C { static int n; } C = 5;"));
        assertThrows(ParseError.class, () -> new Interpreter().eval("class C { int x; static int bad() { return x; } }"));
        JmpError re = assertThrows(JmpError.class, () -> new Interpreter().eval("class C { static int n; } return C.nope;"));
        assertEquals("No static member 'nope' in C", re.message());
    }

    @Test
    void modules(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("lib.jmp"), """
                class Vec { double x; Vec(double x) { this.x = x; } Vec twice() { return new Vec(x * 2); } }
                int loads = 0;
                void note() { loads++; }
                int notes() { return loads; }
                """);
        java.nio.file.Files.writeString(dir.resolve("main.jmp"), """
                import "lib.jmp";
                Vec v = new Vec(2);
                note(); note();
                return v.twice().x + notes();
                """);
        assertEquals(6.0, Interpreter.runWithBigStack(() -> new Interpreter().evalFile(dir.resolve("main.jmp"))));
        // a module runs once per interpreter: the second script sees the same state
        Interpreter jj = new Interpreter();
        java.nio.file.Files.writeString(dir.resolve("a.jmp"), "import \"lib.jmp\";\nnote();\nreturn notes();");
        assertEquals(1, Interpreter.runWithBigStack(() -> jj.evalFile(dir.resolve("a.jmp"))));
        assertEquals(2, Interpreter.runWithBigStack(() -> jj.evalFile(dir.resolve("a.jmp"))));
        ParseError missing = assertThrows(ParseError.class, () -> new Interpreter().eval("import \"nope.jmp\";"));
        assertTrue(missing.getMessage().contains("Module not found"));
    }

    @Test
    void switchStatementAndExpression() {
        assertEquals("wed", new Interpreter().eval("""
                int day = 3;
                return switch (day) { case 1, 7 -> "end"; case 3 -> "wed"; default -> "?"; };
                """));
        // the selector is evaluated once
        assertEquals(1, new Interpreter().eval("""
                int calls = 0;
                int next() { calls++; return 9; }
                switch (next()) { case 1 -> calls += 100; default -> calls += 0; }
                return calls;
                """));
        ParseError colon = assertThrows(ParseError.class, () -> new Interpreter().eval("switch (1) { case 1: return 2; }"));
        assertTrue(colon.getMessage().contains("case 1 -> ..."));
        assertThrows(ParseError.class, () -> new Interpreter().eval("return switch (1) { case 1 -> 2; };"));
    }

    @Test
    void javaArraysAndClassLiterals() {
        assertEquals(12, new Interpreter().eval("""
                dyn a = new int[4];
                a[0] = 5; a[3] = 7;
                return a.length + a[0] + a[3] - 4;
                """));
        assertEquals("int[]", new Interpreter().eval("return type(new int[1]);"));
        assertEquals("a-b", new Interpreter().eval("return String.join(\"-\", [\"a\", \"b\"]);"));
        assertEquals("[3, 1]", new Interpreter().eval("return java.util.Arrays.toString([3, 1]);"));
        assertEquals(String.class, new Interpreter().eval("return java.lang.String.class;"));
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval("class A extends java.util.ArrayList {}"));
        assertTrue(e.message().contains("Cannot extend Java class"));
    }

    @Test
    void booleanFromJavaArrayIsFalsy() {
        // Ops.truthy compared Boolean by reference, and Array.get on a primitive array creates
        // a fresh Boolean bypassing the valueOf cache - so false from a boolean[] was silently truthy.
        assertFalse(me.padej.jumper.runtime.Ops.truthy(java.lang.reflect.Array.get(new boolean[1], 0)));
        assertFalse(me.padej.jumper.runtime.Ops.truthy(Boolean.valueOf(false)));
        assertTrue(me.padej.jumper.runtime.Ops.truthy(Boolean.valueOf(true)));
        // both read forms: `boolean b = arr[i]` was correct, `if (arr[i])` was not
        assertEquals(1, new Interpreter().eval("""
                dyn b = new boolean[2];
                b[1] = true;
                int n = 0;
                for (int i = 0; i < 2; i++) { if (b[i]) { n++; } }
                return n;
                """));
        assertEquals(false, new Interpreter().eval("dyn b = new boolean[1]; boolean x = b[0]; return x;"));
        // a sieve over new boolean[n] returned 0 instead of the prime count
        assertEquals(25, Interpreter.runWithBigStack(() -> new Interpreter().eval("""
                int n = 100;
                dyn c = new boolean[n + 1];
                for (int i = 2; i * i <= n; i++) {
                    if (!c[i]) { for (int j = i * i; j <= n; j += i) { c[j] = true; } }
                }
                int cnt = 0;
                for (int i = 2; i <= n; i++) { if (!c[i]) { cnt++; } }
                return cnt;
                """)));
    }

    @Test
    void javaArrayElementsUseJumperTypes() {
        // Java array access goes without java.lang.reflect.Array; values are converted the same
        // way Interop.normalize converts fields and method results
        assertEquals(45, Interpreter.runWithBigStack(() -> new Interpreter().eval("""
                dyn a = new int[10];
                for (int i = 0; i < 10; i++) { a[i] = i; }
                int s = 0;
                for (int i = 0; i < 10; i++) { s += a[i]; }
                return s;
                """)));
        assertEquals(3.0, new Interpreter().eval("dyn a = new double[2]; a[0] = 1; a[1] = 2; return a[0] + a[1];"));
        assertEquals(30L, new Interpreter().eval("dyn a = new long[2]; a[0] = 10L; a[1] = 20L; return a[0] + a[1];"));
        assertEquals(6, new Interpreter().eval("dyn a = new int[]{1, 2, 3}; int s = 0; for (dyn x : a) { s += x; } return s;"));
        // char[] yields a string, like "s"[i]; float[] yields double, not Float
        assertEquals("abc", new Interpreter().eval("dyn a = \"abc\".toCharArray(); return a[0] + a[1] + a[2];"));
        assertEquals("string", new Interpreter().eval("return type(\"a\".toCharArray()[0]);"));
        Interpreter jj = new Interpreter().define("floats", new float[]{1.5f, 1.5f}).define("shorts", new short[]{300, 400});
        assertEquals(3.0, jj.eval("return floats[0] + floats[1];"));   // used to be Float -> integer arithmetic
        assertEquals("double", jj.eval("return type(floats[0]);"));
        assertEquals(700, jj.eval("return shorts[0] + shorts[1];"));
        assertEquals(4.0, jj.eval("floats[0] = 2.5; return floats[0] + floats[1];"));
        // errors stay readable
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval("dyn a = new int[1]; a[0] = \"s\";"));
        assertTrue(e.message().contains("Cannot assign string to int"));
        JmpError st = assertThrows(JmpError.class, () -> new Interpreter().eval("dyn a = new String[1]; dyn v = 1; a[0] = v;"));
        assertTrue(st.message().contains("Cannot store int into String[]"));
    }

    @Test
    void directCallTargetsRespectReassignment() {
        // Tier 1 calls a declared function directly (invokestatic, with no "is it still that
        // function" check). The promise holds only while the name is never assigned - otherwise
        // calls parsed BEFORE the assignment would keep calling the old body.
        assertEquals(19, Interpreter.runWithBigStack(() -> new Interpreter().eval("""
                int f() { return 1; }
                int call() { return f(); }
                dyn x = call();
                f = () -> 9;
                dyn y = call();
                return x * 10 + y;
                """)));
        assertEquals(9, new Interpreter().eval("""
                int f() { return 1; }
                f = () -> 9;
                int call() { return f(); }
                return call();
                """));
        // self-recursion and mutual recursion
        assertEquals(55, Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "int fib(int n) { return n < 2 ? n : fib(n-1) + fib(n-2); } return fib(10);")));
        assertEquals(true, Interpreter.runWithBigStack(() -> new Interpreter().eval("""
                dyn even(int n) { if (n == 0) { return true; } return odd(n - 1); }
                dyn odd(int n) { if (n == 0) { return false; } return even(n - 1); }
                return even(10);
                """)));
        // recursion that needs a frame: the body cannot be static and cannot call itself
        // via invokestatic - guards against IncompatibleClassChangeError
        assertEquals(15, Interpreter.runWithBigStack(() -> new Interpreter().eval("""
                dyn run() {
                    int acc = 0;
                    dyn step(int n) { if (n == 0) { return acc; } acc += n; return step(n - 1); }
                    return step(5);
                }
                return run();
                """)));
        // a function as a value, and its name captured by a closure
        assertEquals(3, new Interpreter().eval(
                "int one() { return 1; }\nint apply(dyn g) { return g() + g() + g(); }\nreturn apply(one);"));
        assertEquals(2, Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "int base() { return 1; }\ndyn make() { return () -> base() + 1; }\nreturn make()();")));
    }

    @Test
    void scriptEngine() throws Exception {
        javax.script.ScriptEngine engine = new javax.script.ScriptEngineManager().getEngineByName("jmp");
        assertNotNull(engine, "jmp engine registered via META-INF/services");
        engine.put("base", 40);
        assertEquals(42, engine.eval("dyn x = base + 2; int twice(int n) { return n * 2; } return x;"));
        assertEquals(42, engine.get("x"));
        assertEquals(84, ((javax.script.Invocable) engine).invokeFunction("twice", 42));
        java.io.StringWriter out = new java.io.StringWriter();
        engine.getContext().setWriter(out);
        engine.eval("println(\"hi\", x);");
        assertEquals("hi 42", out.toString().trim());
        javax.script.CompiledScript cs = ((javax.script.Compilable) engine).compile("return x * 2;");
        assertEquals(84, cs.eval());
        Runnable r = ((javax.script.Invocable) engine).getInterface(engine.eval("return { run: () -> { x = 1; } };"), Runnable.class);
        r.run();
        assertEquals(1, engine.get("x"));
        javax.script.ScriptException se = assertThrows(javax.script.ScriptException.class, () -> engine.eval("dyn a = 1;\nerror(\"boom\");"));
        assertEquals(2, se.getLineNumber());
    }

    @Test
    void undefinedVariableIsSyntaxError() {
        ParseError e = assertThrows(ParseError.class, () -> new Interpreter().eval("dyn a = 1;\nb = 2;"));
        assertTrue(e.getMessage().contains("Undefined variable 'b'"));
        assertEquals(2, e.line);
    }

    @Test
    void runtimeErrorHasLine() {
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval("dyn t = {};\ndyn x = 1;\nt.f();"));
        assertEquals(3, e.line());
    }

    @Test
    void divisionByZero() {
        assertThrows(JmpError.class, () -> eval("1 / 0"));
    }

    @Test
    void hostFunctionsAndValues() {
        Interpreter jj = new Interpreter()
                .define("twice", (JFunction) args -> ((Integer) args[0]) * 2)
                .define("answer", 42);
        assertEquals(84, jj.eval("return twice(answer);"));
    }

    @Test
    void compiledFunctionIsReusable() {
        JFunction f = new Interpreter().compile("int fib(int n) { return n < 2 ? n : fib(n-1) + fib(n-2); } return fib(15);");
        assertEquals(610, f.call(new Object[0]));
        assertEquals(610, f.call(new Object[0]));
    }

    @Test
    void closuresCaptureVariables() {
        Object r = new Interpreter().eval("""
                dyn make() { dyn n = 0; return { inc: () -> { n++; return n; }, get: () -> n }; }
                dyn c = make(); c.inc(); c.inc();
                return c.get();
                """);
        assertEquals(2, r);
    }

    @Test
    void javaInterop() {
        assertEquals("HELLO", eval("\"hello\".toUpperCase()"));
        assertEquals(7, eval("Math.max(3, 7)"));
        assertEquals(3, eval("new java.util.ArrayList(java.util.List.of(1, 2, 3)).size()"));
    }

    /**
     * A top-level class name is bound to its declaration in REPL mode (javax.script) too.
     *
     * <p>Without that binding, `new P(...)` in the engine did not know its ClassNode, Tier 1 did
     * not enable direct instantiation, and every object went through invokedynamic with an
     * Object[] of arguments - twice as expensive in both time and bytes. Regular tests did not
     * catch it: they all run through Interpreter, where the binding always existed, while the
     * benchmark runs through the engine. The observable consequence of the binding is that
     * assigning to a class name is rejected; that is what we check, because both modes must
     * answer the same program the same way.
     */
    @Test
    void replBindsClassNamesLikeInterpreter() throws Exception {
        javax.script.ScriptEngine engine =
                new javax.script.ScriptEngineManager().getEngineByName("jumper");
        assertNotNull(engine, "jumper engine is not registered");

        assertThrows(javax.script.ScriptException.class,
                () -> engine.eval("class P { int a; }\nP = 5;"),
                "assigning to a class name must be rejected in the REPL too");
        assertThrows(ParseError.class, () -> new Interpreter().eval("class P { int a; }\nP = 5;"));

        // and object creation semantics are the same in both modes
        String src = """
                class A { int x; A(int x) { this.x = x; } }
                class B extends A { int y; B(int x, int y) { super(x); this.y = y; } }
                B b = new B(10, 20);
                return b.x + b.y;
                """;
        javax.script.ScriptEngine fresh =
                new javax.script.ScriptEngineManager().getEngineByName("jumper");
        assertEquals(30, fresh.eval(src));
        assertEquals(30, new Interpreter().eval(src));
    }

    /**
     * Dynamic locals that are only ever written with a single primitive type are kept
     * unboxed by Tier 1 - and that must not be observable from the outside.
     *
     * <p>The semantics match because Jumper's dynamic arithmetic on two Integers returns
     * {@code x + y}, i.e. plain int addition with wrap-around overflow, and division by zero
     * yields JmpError on both paths. We check exactly the edges: overflow, division, a type
     * change in the variable, capture by a closure (such a slot lives in the Frame and is not
     * eligible for unboxing) and `dyn` without an initializer.
     *
     * <p>Emergency switch: {@code -Djmp.unbox=0}.
     */
    @Test
    void dynamicLocalsKeepSemanticsWhenUnboxed() {
        // int overflow wraps around, like a typed int
        assertEquals(Integer.MIN_VALUE, Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn x = 2147483647; dyn y = 1; return x + y;")));
        // division by zero stays a Jumper error, not an ArithmeticException
        JmpError dz = assertThrows(JmpError.class, () -> new Interpreter().eval(
                "dyn a = 1; dyn b = 0; return a / b;"));
        assertTrue(dz.getMessage().contains("Division by zero"));
        // a different type is stored into the variable - unboxing must be revoked
        assertEquals("s1", new Interpreter().eval("dyn x = 1; x = \"s\"; return x + 1;"));
        assertEquals(1.5, new Interpreter().eval("dyn x = 1; x = 1.5; return x;"));
        // captured by a closure: the slot lives in the Frame and cannot be unboxed
        assertEquals(10, Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn acc = 0; dyn g = (x) -> { acc = acc + x; }; g(4); g(6); return acc;")));
        // loop counter and accumulator - the case this was all for
        assertEquals(20039064, Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn n = 20000000; dyn c = 0;\n"
                + "for (dyn i = 0; i < n; i++) { if ((i & 1023) != 0) { c += 1; } else { c += 3; } }\n"
                + "return c;")));
        // long and double as well
        assertEquals(3000000000L, Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn s = 0L; for (dyn i = 0; i < 3; i++) { s += 1000000000L; } return s;")));
        assertEquals(6.0, new Interpreter().eval("dyn d = 0.0; for (dyn i = 0; i < 3; i++) { d += 2.0; } return d;"));
    }

    /**
     * The element type of a Java array is known statically, and Tier 1 accesses elements directly.
     *
     * <p>The promise comes from the initializer (`dyn a = new int[n]`) and is revoked by any
     * assignment to the name - including one that appears BELOW the read in the text, and one
     * made from a nested function. If it were not revoked, Tier 1 would be left with
     * `checkcast [I` where the variable already holds something else, and a ClassCastException
     * would replace the usual Jumper error.
     *
     * <p>Yardstick for the gain - a sieve: access through Object with type dispatch inside Ops
     * cost 98.8 ms, direct access 69.2 vs 69.4 for Java on the same algorithm.
     */
    @Test
    void arrayElementTypeIsStaticButRevocable() {
        assertEquals(true, new Interpreter().eval("dyn a = new boolean[2]; a[1] = true; return a[1];"));
        assertEquals(7, new Interpreter().eval("dyn a = new int[2]; a[0] = 7; return a[0];"));
        assertEquals(1.5, new Interpreter().eval("dyn a = new double[2]; a[0] = 1.5; return a[0];"));
        assertEquals(9000000000L, new Interpreter().eval("dyn a = new long[2]; a[0] = 9000000000L; return a[0];"));

        // the name is reassigned BELOW the read - the promise must be revoked for earlier nodes too
        assertEquals("s", new Interpreter().eval(
                "dyn a = new int[2]; a[0] = 1; a = \"str\"; return a[0];"));
        // and on assignment from a nested function
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval(
                "dyn a = new int[2]; dyn f() { a = null; }; f(); return a[0];"));
        assertTrue(e.getMessage().contains("Cannot index"));
        // switching the array to another element type
        assertEquals(true, new Interpreter().eval(
                "dyn a = new int[2]; a = new boolean[2]; a[0] = true; return a[0];"));

        // storing a value that does not match the element type takes the old path - with the old error
        JmpError w = assertThrows(JmpError.class, () -> new Interpreter().eval(
                "dyn a = new int[1]; a[0] = \"s\";"));
        assertTrue(w.getMessage().contains("Cannot assign string to int"));
    }

    /**
     * A class instance is a generated JVM class with real fields (jit/InstanceGen), but from
     * the outside it must behave exactly like the previous array-backed JTable.
     *
     * <p>We check precisely what is easy to lose in generation: field layout across the
     * inheritance chain, default values, key enumeration and conversion to Map. Each item runs
     * both through Interpreter and through the engine - the representation is shared, but the
     * paths to it differ (Tier 1 reads a field in bytecode, interop goes through virtual
     * bitsAt/refAt).
     *
     * <p>Emergency switch: {@code -Djmp.genclass=0} brings the arrays back.
     */
    @Test
    void generatedInstancesBehaveLikeTables() throws Exception {
        // three levels of inheritance: the field index is computed over the whole chain
        assertEquals(6, new Interpreter().eval("""
                class A { int a = 1; }
                class B extends A { int b = 2; }
                class C extends B { int c = 3; }
                C o = new C();
                return o.a + o.b + o.c;
                """));

        // a base-class method called on a subclass reads its own field
        assertEquals(10, new Interpreter().eval("""
                class A { int x; A(int x) { this.x = x; } int get() { return this.x; } }
                class B extends A { B(int x) { super(x); } }
                B b = new B(10);
                return b.get();
                """));

        // default values for every field type
        assertEquals("0 0.0 false null", new Interpreter().eval("""
                class P { int i; double d; boolean b; dyn v; }
                P p = new P();
                return p.i + " " + p.d + " " + p.b + " " + p.v;
                """));

        // the table from outside: keys, size, printing, access by string key
        assertEquals("[a, b]", new Interpreter().eval("class P { int a = 1; int b = 2; } return str(keys(new P()));"));
        assertEquals(2, new Interpreter().eval("class P { int a = 1; int b = 2; } return len(new P());"));
        assertEquals("P{a: 1, b: 2}", new Interpreter().eval("class P { int a = 1; int b = 2; } return str(new P());"));
        assertEquals(9, new Interpreter().eval("class P { int a = 1; } P p = new P(); p[\"a\"] = 9; return p.a;"));
        assertEquals("P", new Interpreter().eval("class P { int a; } return type(new P());"));
        assertEquals(2, new Interpreter().eval("class P { int a = 1; int b = 2; } return new java.util.HashMap(new P()).size();"));

        // the declared class type of a field is still checked on write
        assertThrows(JmpError.class, () -> new Interpreter().eval("""
                class V { int x; V(int x) { this.x = x; } }
                class H { V v; H(V v) { this.v = v; } }
                return new H(5);
                """));

        // increment and compound assignment go through the same field
        assertEquals(12, new Interpreter().eval("""
                class P { int n; P(int n) { this.n = n; } }
                P p = new P(4);
                p.n++;
                p.n += 7;
                return p.n;
                """));

        // the same through the engine: a different path to the field, the result must match
        javax.script.ScriptEngine engine =
                new javax.script.ScriptEngineManager().getEngineByName("jumper");
        assertEquals(4950, engine.eval("""
                class P { int a; P(int a) { this.a = a; } }
                dyn arr = array(100, null);
                for (int i = 0; i < 100; i++) { arr[i] = new P(i); }
                int s = 0;
                for (int i = 0; i < 100; i++) { P p = arr[i]; s += p.a; }
                return s;
                """));
    }

    /**
     * The slot representation of a plain table is a guess, not a declaration.
     *
     * <p>With {@code jmp.tableprims}, a literal's slot that received a primitive lives in prims
     * without boxing. Nothing about that may be visible from the language: not the value's type,
     * not the behavior when a value of another kind is stored, not printing, not key iteration.
     * The testTablePrimsOff task runs this whole file with the flag flipped - the cases it is
     * for are collected here.
     */
    @Test
    void tableSlotRepresentationIsInvisibleFromTheLanguage() throws Exception {
        // a primitive goes in and comes out unchanged
        assertEquals(1.5, new Interpreter().eval("dyn t = { x: 1.5 }; return t.x;"));
        assertEquals(7, new Interpreter().eval("dyn t = { n: 7 }; return t.n;"));
        assertEquals(true, new Interpreter().eval("dyn t = { b: true }; return t.b;"));
        assertEquals("double", new Interpreter().eval("dyn t = { x: 1.5 }; return type(t.x);"));

        // storing a value of another kind: the slot must accept it, neither convert nor fail
        assertEquals("s", new Interpreter().eval("dyn t = { x: 1.5 }; t.x = \"s\"; return t.x;"));
        assertEquals(7, new Interpreter().eval("dyn t = { x: 1.5 }; t.x = 7; return t.x;"));
        assertEquals(1.25, new Interpreter().eval("dyn t = { n: 7 }; t.n = 1.25; return t.n;"));
        assertEquals(null, new Interpreter().eval("dyn t = { x: 1.5, y: 2.5 }; t.x = null; return t.x;"));

        // after switching to the reference kind the slot keeps working
        assertEquals(4.0, new Interpreter().eval("dyn t = { x: 1.5 }; t.x = \"s\"; t.x = 4.0; return t.x;"));

        // neighboring slots are unaffected by the kind change and by the switch to a dictionary
        assertEquals(2.5, new Interpreter().eval("dyn t = { x: 1.5, y: 2.5 }; t.x = null; return t.y;"));
        assertEquals(2.5, new Interpreter().eval("dyn t = { x: 1.5, y: 2.5 }; t.x = \"s\"; return t.y;"));

        // the table from outside: keys, size, printing, access by string key, iteration
        assertEquals("[x, y]", new Interpreter().eval("dyn t = { x: 1.5, y: 2 }; return str(keys(t));"));
        assertEquals(2, new Interpreter().eval("dyn t = { x: 1.5, y: 2 }; return len(t);"));
        assertEquals("{x: 1.5, y: 2}", new Interpreter().eval("dyn t = { x: 1.5, y: 2 }; return str(t);"));
        assertEquals(1.5, new Interpreter().eval("dyn t = { x: 1.5 }; return t[\"x\"];"));
        assertEquals(2, new Interpreter().eval("dyn t = { x: 1.5, y: 2 }; return new java.util.HashMap(t).size();"));
        assertEquals("xy", new Interpreter().eval("""
                dyn t = { x: 1.5, y: 2 };
                dyn s = "";
                for (dyn k : t) { s = s + str(k); }
                return s;
                """));

        // a new key after the literal, increment and compound assignment on a primitive slot
        assertEquals(9, new Interpreter().eval("dyn t = { x: 1.5 }; t.k = 9; return t.k;"));
        assertEquals(12, new Interpreter().eval("dyn t = { n: 4 }; t.n++; t.n += 7; return t.n;"));
        assertEquals(4.0, new Interpreter().eval("dyn t = { x: 1.5 }; t.x += 2.5; return t.x;"));

        // many tables from one literal: they share a shape, otherwise the inline cache would never hit twice
        assertEquals(4950.0, new Interpreter().eval("""
                dyn arr = array(100, null);
                for (dyn i = 0; i < 100; i++) { arr[i] = { x: i * 1.0, y: 0.0 }; }
                dyn s = 0.0;
                for (dyn i = 0; i < 100; i++) { dyn b = arr[i]; b.y += b.x; s += b.y; }
                return s;
                """));

        // the same through the engine: a different path to the field, the result must match
        javax.script.ScriptEngine engine =
                new javax.script.ScriptEngineManager().getEngineByName("jumper");
        assertEquals(4950.0, engine.eval("""
                dyn arr = array(100, null);
                for (dyn i = 0; i < 100; i++) { arr[i] = { x: i * 1.0, y: 0.0 }; }
                dyn s = 0.0;
                for (dyn i = 0; i < 100; i++) { dyn b = arr[i]; b.y += b.x; s += b.y; }
                return s;
                """));
    }
    @Test
    void selfTypedFieldsInsideClass() {
        // Inside its own body a class was not registered as a type: `Node left, right;` silently
        // created no fields, and the error surfaced only at runtime ("No field 'left' in Node").
        // Same for a class declared further down in the text: `class A { B b; } class B {}`.
        assertEquals(10, new Interpreter().eval("""
                class Node {
                    Node left, right;
                    int v;
                    Node(Node l, Node r, int v) { this.left = l; this.right = r; this.v = v; }
                    int check() { return v + (left == null ? 0 : left.check()) + (right == null ? 0 : right.check()); }
                }
                class A { B b; A(B b) { this.b = b; } }
                class B { int x = 7; }
                dyn t = new Node(new Node(null, null, 1), new Node(new Node(null, null, 2), null, 3), 4);
                return t.check() + new A(new B()).b.x - 7;
                """));
        // a class-typed field checks the type inside its own class too
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval("""
                class Node { Node next; Node(n) { this.next = n; } }
                dyn five = 5;
                return new Node(five);
                """));
        assertTrue(e.getMessage().contains("Expected Node"), e.getMessage());
    }

    @Test
    void constructorArityIsChecked() {
        // `new P(1, 2)` with constructor P(x) silently created an object. For a known class the
        // argument count must match - as in Java; functions keep Lua semantics.
        ParseError e = assertThrows(ParseError.class, () -> new Interpreter().eval(
                "class P { int x; P(int x) { this.x = x; } } dyn p = new P(1, 2);"));
        assertTrue(e.getMessage().contains("expects 1 argument, got 2"), e.getMessage());
        // the constructor is inherited from the parent: the arity is the parent's
        assertThrows(ParseError.class, () -> new Interpreter().eval(
                "class Q { Q(int a, int b) {} } class R extends Q {} dyn r = new R(1);"));
        // no constructor anywhere in the chain - zero arguments
        assertThrows(ParseError.class, () -> new Interpreter().eval("class E {} dyn e = new E(1);"));
        // matching arity, and a call before the class declaration in the text - both pass
        assertEquals("R", new Interpreter().eval(
                "dyn f() { return new R(1); } class Q { Q(int a) {} } class R extends Q {} return type(f());"));
        // the dynamic path (class in a variable) stays lenient, like a function call
        assertEquals("P", new Interpreter().eval(
                "class P { P(int x) {} } dyn C = P; return type(new C(1, 2, 3));"));
        // the same in REPL mode (ScriptEngine): the class name is bound statically there too
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        assertThrows(javax.script.ScriptException.class, () -> engine.eval("class P { P(int x) {} } dyn p = new P(1, 2);"));
    }
    @Test
    void engineTopLevelCallIsDirectButHostCanStillReplaceName() throws Exception {
        // Stage 3.4: a call by top-level name in ScriptEngine goes through the cell's SwitchPoint -
        // no guard on the hot path; yet engine.put and an assignment in the script must still
        // intercept the name after warm-up (including inside an already compiled function).
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.eval("""
                int step(int n) { return n + 1; }
                int fib(int n) { if (n < 2) return n; return fib(n - 1) + fib(n - 2); }
                long run(int k) { long s = 0; for (int i = 0; i < k; i++) { s += step(i); } return s + fib(15); }
                """);
        javax.script.Invocable inv = (javax.script.Invocable) engine;
        for (int i = 0; i < 50; i++) assertEquals(5050L + 610, inv.invokeFunction("run", 100));
        // the host replaces the name - an already bound call site must see it
        engine.put("step", (me.padej.jumper.runtime.JFunction) args -> 10);
        assertEquals(1000L + 610, inv.invokeFunction("run", 100));
        engine.put("fib", (me.padej.jumper.runtime.JFunction) args -> 0);
        assertEquals(1000L, inv.invokeFunction("run", 100));
        // the script reassigns the name - same thing; afterwards the new value is bound again
        engine.eval("step = (n) -> n * 2;");
        assertEquals(9900L, inv.invokeFunction("run", 100));
        engine.eval("int step(int n) { return n; }");
        for (int i = 0; i < 20; i++) assertEquals(4950L, inv.invokeFunction("run", 100));
        // remove: calling a removed name is a clear error, not the old function
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).remove("step");
        javax.script.ScriptException ex = assertThrows(javax.script.ScriptException.class, () -> inv.invokeFunction("run", 100));
        assertTrue(ex.getMessage().contains("Attempt to call null"), ex.getMessage());
    }
    @Test
    void generatedClassesAreUnloadedWithTheEngine() throws Exception {
        // Tier 1 classes (Fn*, Inst*) are defined in the interpreter's ScriptLoader, not in Jumper's
        // own loader: previously every repeated eval left ~14 classes behind until process exit.
        if (!me.padej.jumper.jit.Jit.ENABLED) return;
        java.lang.ref.WeakReference<Class<?>>[] refs = generatedClassesOfThrowawayEngine();   // separate method: so no references remain on the stack
        for (int i = 0; i < 50 && (refs[0].get() != null || refs[1].get() != null); i++) { System.gc(); Thread.sleep(20); }
        assertNull(refs[0].get(), "function class was not unloaded with the engine");
        assertNull(refs[1].get(), "instance class was not unloaded with the engine");
    }

    @SuppressWarnings("unchecked")
    private static java.lang.ref.WeakReference<Class<?>>[] generatedClassesOfThrowawayEngine() throws Exception {
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.eval("""
                class P { int x; P(int x) { this.x = x; } int twice() { return x * 2; } }
                int f(int n) { int s = 0; for (int i = 0; i < n; i++) { s += new P(i).twice(); } return s; }
                """);
        assertEquals(90, ((javax.script.Invocable) engine).invokeFunction("f", 10));
        Class<?> fc = ((me.padej.jumper.ast.FunctionNode.ScriptFunction) engine.get("f")).node.jitClass;
        assertNotNull(fc, "f is compiled (has a loop -> on first call)");
        assertTrue(fc.getClassLoader() instanceof me.padej.jumper.jit.ScriptLoader, "function class lives in the interpreter's loader");
        Class<?> ic = ((me.padej.jumper.runtime.JClass) engine.get("P")).newInstance().getClass();
        if (me.padej.jumper.jit.InstanceGen.ENABLED)   // with -Djmp.genclass=0 the instance is a plain JTable.Plain
            assertTrue(ic.getClassLoader() instanceof me.padej.jumper.jit.ScriptLoader, "instance class lives there too: " + ic);
        // with -Djmp.genclass=0 the instance is a JTable.Plain from the app loader; nothing to unload
        return new java.lang.ref.WeakReference[]{new java.lang.ref.WeakReference<>(fc),
                new java.lang.ref.WeakReference<>(me.padej.jumper.jit.InstanceGen.ENABLED ? ic : null)};
    }

    @Test
    void tableFunctionFieldCallsAreDirectButFollowTheSlot() throws Exception {
        // t.f(x) on a plain table is a direct call of the body under a guard on shape and function
        // node (Indy.installTableFunction). The guard is on the node, not the value: a closure of the
        // same node with a different environment must be read from the slot, a different function
        // must go to its own layer.
        assertEquals("[6, 12, 24, 101, 7]", String.valueOf(new Interpreter().eval("""
                dyn mk(k) { return { next: (x) -> x * k }; }
                dyn run(t, n) { dyn s = 0; for (dyn i = 0; i < n; i++) { s += t.next(1); } return s / n; }
                dyn a = mk(2), b = mk(4), c = mk(8);
                dyn out = [];
                out[0] = run(a, 3000) * 3;                      // 2 -> 6
                out[1] = run(b, 3000) * 3;                      // same node, different closure: 4 -> 12
                out[2] = run(c, 3000) * 3;                      // 8 -> 24
                a.next = (x) -> x + 100;                         // a different function in the same slot
                out[3] = run(a, 3000);                           // 101
                dyn typed = { next: (int x) -> x + 6 };          // typed parameter: the argument is converted
                out[4] = run(typed, 3000);                       // 7
                return out;
                """)));
        // a non-function in the slot is a clear error, not a ClassCastException
        JmpError e = assertThrows(JmpError.class, () -> new Interpreter().eval("""
                dyn t = { next: (x) -> x };
                for (dyn i = 0; i < 100; i++) { t.next(i); }
                t.next = 5;
                return t.next(1);
                """));
        assertTrue(e.getMessage().contains("Attempt to call field"), e.getMessage());
    }

    @Test
    void functionalInterfaceBridgesCallTheBodyDirectly() throws Exception {
        // jit/BridgeGen: a script function implementing a functional interface is a generated class
        // that calls the body directly, not a Proxy. Semantics are the same as with Proxy: arguments and
        // result are converted, default methods and Object methods work, a non-functional interface
        // still gets a Proxy.
        Interpreter jj = new Interpreter();
        java.util.function.IntBinaryOperator op = as(java.util.function.IntBinaryOperator.class, jj.eval("return (a, b) -> a * b + 1;"));
        assertEquals(43, op.applyAsInt(6, 7));                                   // int -> dyn, dyn -> int
        java.util.function.IntBinaryOperator typed = as(java.util.function.IntBinaryOperator.class, jj.eval("return (int a, int b) -> a - b;"));
        assertEquals(-1, typed.applyAsInt(6, 7));                                // int -> int directly
        java.util.function.DoubleUnaryOperator du = as(java.util.function.DoubleUnaryOperator.class, jj.eval("return (x) -> x / 2;"));
        assertEquals(1.25, du.applyAsDouble(2.5), 1e-12);
        @SuppressWarnings("unchecked")
        java.util.function.Predicate<Object> pr = as(java.util.function.Predicate.class, jj.eval("return (x) -> x != null && x > 3;"));
        assertTrue(pr.test(5));
        assertFalse(pr.test(null));
        assertFalse(pr.negate().test(5));                                        // interface default method
        me.padej.jumper.runtime.JTable box = (me.padej.jumper.runtime.JTable) jj.eval("return { n: 0 };");
        jj.define("box", box);
        Runnable r = as(Runnable.class, jj.eval("return () -> { box.n = box.n + 1; };"));   // closure, body is not static
        r.run(); r.run();
        assertEquals(2, box.get("n"));
        @SuppressWarnings("unchecked")
        java.util.Comparator<Object> cmp = as(java.util.Comparator.class, jj.eval("return (a, b) -> (a % 10) - (b % 10);"));
        java.util.List<Object> list = new java.util.ArrayList<>(java.util.List.of(15, 3, 27, 41));
        list.sort(cmp);
        assertEquals(java.util.List.of(41, 3, 15, 27), list);
        assertNotNull(cmp.toString());
        assertEquals(cmp, cmp);
        // from a script: the same path through Interop.convert
        assertEquals(60L, jj.eval("""
                dyn list = new java.util.ArrayList();
                for (int i = 1; i <= 5; i++) list.add(i * 4);
                long s = 0L;
                list.forEach(x -> { s += x; });
                return s;
                """));
        // an interface with two abstract methods is not functional: Proxy, both methods call the function
        TwoMethods tm = as(TwoMethods.class, jj.eval("return (x) -> x + 100;"));
        assertEquals(101, tm.one(1));
        assertEquals(102, tm.two(2));
    }

    @SuppressWarnings("unchecked")
    private static <T> T as(Class<T> iface, Object fn) {
        return (T) me.padej.jumper.runtime.Interop.convert(iface, fn);
    }

    public interface TwoMethods {
        Object one(Object x);
        Object two(Object x);
    }

    @Test
    void accessPolicyIsOptionalAndWhitelists() throws Exception {
        // By default everything is open, as before
        assertEquals("x", new Interpreter().eval("return new java.lang.StringBuilder(\"x\").toString();"));
        // Policy: a whitelist; sandbox escape routes are not opened by allowing a package
        // The policy file is a regular Jumper script with the Policy dictionary (*.jma)
        me.padej.jumper.runtime.Access policy = me.padej.jumper.runtime.Access.parse("""
                // test policy
                Policy.allowPackage("java.util");
                Policy.allowPackage("java.lang");            // but not Class / Runtime / System / Thread
                Policy.allowMethod("java.lang.System", "currentTimeMillis");
                Policy.allowClass("java.lang.StringBuilder")
                      .denyMethod("setLength");
                """);
        // the policy file itself runs under the policy: no Java access from inside it
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () ->
                me.padej.jumper.runtime.Access.parse("Policy.allowPackage(\"java.util\"); Runtime.getRuntime().exit(1);"));
        assertTrue(bad.getMessage().contains("Access denied"), bad.getMessage());
        Interpreter jj = new Interpreter().access(policy);
        assertEquals(3, jj.eval("dyn l = new java.util.ArrayList(); l.add(1); l.add(2); l.add(3); return l.size();"));
        assertEquals("ab", jj.eval("return new StringBuilder(\"a\").append(\"b\").toString();"));
        assertTrue((Long) jj.eval("return System.currentTimeMillis();") > 0);          // allowed by name
        String[] denied = {
                "return new ProcessBuilder(\"x\");",                                    // escape: class
                "return Runtime.getRuntime();",                                         // escape: System-like
                "System.exit(3); return 0;",                                             // System is allowed for a single method only
                "return Thread.currentThread();",
                "dyn l = new java.util.ArrayList(); return l.getClass();",             // reflection via getClass
                "return java.util.ArrayList.class.getMethods();",                       // the Class object exists, but its methods are closed
                "import java.lang.reflect.Method; return Method.class;",
                "dyn sb = new StringBuilder(\"abc\"); sb.setLength(1); return sb.toString();",   // deny on a member
                "import java.nio.file.Files; return Files.exists(java.nio.file.Path.of(\".\"));",   // package not allowed
        };
        for (String src : denied) {
            Exception e = assertThrows(Exception.class, () -> jj.eval(src), src);
            assertTrue(String.valueOf(e.getMessage()).contains("Access denied"), src + " -> " + e.getMessage());
        }
        // under a policy, modules are closed until allow modules is said
        Exception m = assertThrows(SecurityException.class, () -> jj.eval("import \"nope.jmp\";"));
        assertTrue(m.getMessage().contains("Access denied"), m.getMessage());
        // a callback from Java runs under the same policy - sorting with a lambda works, escaping from it does not
        assertEquals(java.util.List.of(1, 2, 3), jj.eval("""
                dyn l = new java.util.ArrayList(); l.add(3); l.add(1); l.add(2);
                l.sort((a, b) -> a - b);
                return l;
                """));
        Exception cb = assertThrows(Exception.class, () -> jj.eval("""
                dyn l = new java.util.ArrayList(); l.add(1);
                l.forEach(x -> { Runtime.getRuntime(); });
                """));
        assertTrue(String.valueOf(cb.getMessage()).contains("Access denied"), cb.getMessage());
        // through ScriptEngine - the same
        me.padej.jumper.script.JmpScriptEngine engine = (me.padej.jumper.script.JmpScriptEngine) new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.access(policy);
        assertEquals(2, engine.eval("dyn m = new java.util.HashMap(); m.put(\"a\", 1); m.put(\"b\", 2); return m.size();"));
        // a policy violation is not a script error: it reaches the host as a SecurityException, not a ScriptException
        assertThrows(SecurityException.class, () -> engine.eval("return new ProcessBuilder(\"x\");"));
        // another interpreter in the same thread does not inherit the policy
        assertNotNull(new Interpreter().eval("return Runtime.getRuntime();"));
    }

    @Test
    void cancelStopsLoopsWhenEnabled() throws Exception {
        Interpreter jj = new Interpreter().cancellable(true);
        JFunction f = jj.compile("dyn n = 0; while (true) { n++; } return n;");
        Thread stopper = new Thread(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) {} jj.cancel(); });
        stopper.start();
        JmpError e = assertThrows(JmpError.class, () -> f.call(new Object[0]));
        assertTrue(e.getMessage().contains("Cancelled"), e.getMessage());
        stopper.join();
        // without cancellable there is no check in loops and cancel() does nothing; verify a plain loop works
        assertEquals(10, new Interpreter().eval("int s = 0; for (int i = 0; i < 10; i++) s++; return s;"));
    }

    @Test
    void compileAllGeneratesEveryFunctionAhead() {
        Interpreter jj = new Interpreter();
        JFunction f = jj.compile("""
            int twice(int n) { return n * 2; }
            class P { int x; P(int x) { this.x = x; } int get() { return x; } static int mk() { return 7; } }
            dyn sq = (int n) -> n * n;
            return twice(new P(3).get()) + sq(2) + P.mk();
            """);
        var node = ((me.padej.jumper.ast.FunctionNode.ScriptFunction) f).node;
        int n = me.padej.jumper.jit.Jit.compileAll(node);
        if (me.padej.jumper.jit.Jit.ENABLED) {
            // script, twice, constructor, get, mk, lambda - at least six bodies
            assertTrue(n >= 6, "compileAll: " + n);
        } else {
            assertEquals(0, n);
        }
        assertEquals(17, f.call(new Object[0]));   // 2*3 + 4 + 7 - the result after AOT is the same
    }

    @Test
    void varIsNotAKeyword() {
        // Java's `var` infers and fixes a type; Jumper's dynamic variable is `dyn`. The old word gets a hint.
        ParseError e = assertThrows(ParseError.class, () -> new Interpreter().eval("var x = 1; return x;"));
        assertTrue(e.getMessage().contains("'dyn name = ...'"), e.getMessage());
        assertEquals("s", new Interpreter().eval("dyn x = 1; x = \"s\"; return x;"));   // dyn really is dynamic
        // as an identifier the word is still free
        assertEquals(3, new Interpreter().eval("int var = 3; return var;"));
    }

    @Test
    void compiledTableLiteralsMatchTheInterpreter() {
        // a literal with statically typed values is built directly by Tier 1 (no boxes, no typeSlots):
        // the same shape, the same values, the same evaluation order and null semantics as Tier 0
        String src = """
            dyn order = "";
            dyn f(x) { order += x; return x; }
            dyn make(int i) { return { a: i % 7, b: i * 0.5, s: "str", ok: (i & 1) == 0, n: { z: 1 } }; }
            dyn t = make(3);
            dyn u = { p: f("1"), q: f("2") + 0, r: f("3") };
            dyn v = { x: 1, y: null };
            return "" + t.a + t.b + t.s + t.ok + t.n.z + order + u.p + u.q + u.r + (v.y == null) + len(v);
            """;
        assertEquals("31.5strfalse11231203true1", new Interpreter().eval(src));   // "2" + 0 is "20"
        // through a loop so the function is compiled, and many tables of one literal share a shape
        assertEquals(30, new Interpreter().eval("""
            dyn mk(int i) { return { a: i, b: i * 2.0 }; }
            dyn s = 0; for (int i = 0; i < 5; i++) { dyn t = mk(i); s += t.a + int(t.b); }
            return s;
            """));
        // int()/long()/double() over a dyn argument: numbers convert, strings parse or give null
        assertEquals(13, new Interpreter().eval("dyn s = \"12\"; return int(s) + 1;"));
        assertNull(new Interpreter().eval("dyn s = \"x\"; return int(s);"));
        assertEquals(2, new Interpreter().eval("dyn d = 2.9; return int(d);"));
        assertEquals(2L, new Interpreter().eval("dyn d = 2.9; return long(d);"));
        assertEquals(1, new Interpreter().eval("dyn b = true; return int(b);"));
        assertEquals(3.0, new Interpreter().eval("dyn t = { c: 3 }; dyn s = 0.0; for (int i = 0; i < 3; i++) s += double(t.c) / 3; return s;"));
    }

    @Test
    void tableLiteralsFromUntypedParametersGetALayoutClassAndTheRightPrimitiveSlots() {
        // `body(x, y, z)` with dyn (not int/double) parameters: the literal's values are boxed at the
        // call site, so the shape is typed from the actual values at run time (Shape.typedBy) rather
        // than known ahead of time - the path that once left `prims` null while the shape still said
        // DOUBLE (JTable.Plain used shape.hasPrims, always false for a plain shape; must be anyPrim).
        // A guarded region reading such a field the way nbody's inner loop does must see real numbers,
        // not a NullPointerException from an unallocated prims array, with or without a layout class.
        String src = """
            dyn body(x, y, vx, vy) { return { x: x, y: y, vx: vx, vy: vy }; }
            dyn bodies = [ body(1.0, 2.0, 0.1, 0.2), body(3.0, 4.0, 0.3, 0.4), body(5.0, 6.0, 0.5, 0.6) ];
            dyn s = 0.0;
            for (dyn i = 0; i < 3; i++) {
                dyn b = bodies[i];
                b.x += b.vx;
                b.y += b.vy;
                s += b.x + b.y;
            }
            return s;
            """;
        // b0: 1.1, 2.2; b1: 3.3, 4.4; b2: 5.5, 6.6 - sum after one update step
        assertEquals(23.1, (double) new Interpreter().eval(src), 1e-9);
        // this test runs once under whatever -Djmp.layout the JVM was started with; the flag-mode
        // gate (`jj iterate`) runs the whole suite again with -Djmp.layout=0 to cover the other side.
    }

    /**
     * `acc += <dynamic>` on a dual local takes a primitive shortcut only for boxes the slot's kind
     * absorbs exactly (Compiler.dualAccumulate); every other value must behave exactly like Ops.add
     * on the boxed accumulator - including changing the accumulator's type midway.
     */
    @Test
    void dualAccumulatorKeepsOpsSemantics() {
        java.util.function.Function<String, Object> run = body -> Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn id(v) { return v; }\n" + body));
        // long accumulator: Integer and Long stay long
        assertEquals(3000000006L, run.apply("dyn s = 0L; for (dyn i = 0; i < 3; i++) { s += id(1000000000L); s += id(2); } return s;"));
        // a Double arriving midway turns it into a double, and it stays one
        assertEquals(2.5, run.apply("dyn s = 0L; s += id(1); s += id(0.5); s += id(1L); return s;"));
        // a String turns it into a string
        assertEquals("1x2", run.apply("dyn s = 0L; s += id(1); s += id(\"x\"); s += id(2); return s;"));
        // int accumulator: int + int wraps; a Long promotes it to long
        assertEquals(Integer.MIN_VALUE, run.apply("dyn a = 2147483647; a += id(1); return a;"));
        assertEquals(2147483648L, run.apply("dyn a = 2147483647; a += id(1L); return a;"));
        // double accumulator absorbs Integer and Long
        assertEquals(4.5, run.apply("dyn d = 0.5; d += id(1); d += id(3L); return d;"));
        // -=, *= and the long form acc = acc + e
        assertEquals(-5L, run.apply("dyn s = 0L; s -= id(2); s -= id(3L); return s;"));
        assertEquals(24L, run.apply("dyn s = 1L; for (dyn i = 1; i <= 4; i++) { s *= id(i); } return s;"));
        assertEquals(7L, run.apply("dyn s = 0L; s = s + id(3); s = s + id(4L); return s;"));
        // the right-hand side writes the accumulator itself: evaluation order must be kept
        assertEquals(15L, run.apply("dyn s = 5L; s += (s = id(10L)); return s;"));
        // null is an error, not a silent zero
        assertThrows(JmpError.class, () -> run.apply("dyn s = 0L; s += id(null); return s;"));
    }

    /**
     * A field-read site in Tier 1 (GetSite) keeps a layer per receiver class and shape. It must read
     * exactly what the generic FieldCache path reads: a Plain and several layout classes at one site,
     * a key missing from a plain table (null, not an error), a slot that went back to a reference, a
     * dictionary-mode table, and typed sites that widen int into long/double.
     */
    @Test
    void polymorphicFieldReadsMatchTheGenericPath() {
        String src = """
            dyn mk(i) {
                if (i % 5 == 0) return { a: i, b: 1.5 };
                if (i % 5 == 1) return { b: 2.5, a: i * 2 };
                if (i % 5 == 2) return { c: "x" };
                if (i % 5 == 3) { dyn t = { a: 7 }; t.a = "s"; return t; }
                dyn d = {}; d.a = 1L; d.q = 2; return d;
            }
            dyn out = "";
            dyn sum = 0.0;
            for (dyn i = 0; i < 40; i++) {
                dyn t = mk(i);
                dyn v = t.a;
                out = out + v + ",";
                if (v != null && i % 5 != 3) sum += t.b == null ? v : v * t.b;
            }
            dyn ts = [ { a: 1, n: 2L }, { a: 2.5, n: 3 }, { n: 4, a: 3 } ];
            long s = 0L;
            double d = 0.0;
            for (int i = 0; i < 30; i++) {
                dyn r = ts[i % 3];
                s += r.n;
                d += r.a;
                d += ts[i & 1].n;
            }
            return out + sum + " " + s + " " + d;
            """;
        assertEquals("0,2,null,s,1,5,12,null,s,1,10,22,null,s,1,15,32,null,s,1,20,42,null,s,1,25,52,null,s,1,"
                + "30,62,null,s,1,35,72,null,s,1,958.0 90 140.0",
                Interpreter.runWithBigStack(() -> new Interpreter().eval(src)));
        // a dictionary-mode receiver (more keys than a shape holds) and a Java object at the same site
        String many = new StringBuilder("dyn big = {}; for (int k = 0; k < 100; k++) big[\"k\" + k] = k; big.a = 9;")
                .append(" dyn get(t) { return t.a; }")
                .append(" dyn acc = 0; for (int i = 0; i < 20; i++) { acc += get(i % 2 == 0 ? big : { a: i }); }")
                .append(" return acc;").toString();
        assertEquals(190, Interpreter.runWithBigStack(() -> new Interpreter().eval(many)));
    }

    /**
     * The int entry point (Compiler.decideSpec): a `dyn` parameter the code treats as an int gets a
     * bodyI(int). It must be invisible: other argument types go through body, a parameter that is
     * reassigned to something else gets no entry, int arithmetic wraps as dynamic int arithmetic does,
     * and a caller bound to bodyI through a top-level name still sees the host replace that name - with
     * a function returning anything at all.
     */
    @Test
    void intEntryPointIsInvisible() throws Exception {
        java.util.function.Function<String, Object> run = src -> Interpreter.runWithBigStack(() -> new Interpreter().eval(src));
        assertEquals(610, run.apply("dyn fib(n) { if (n < 2) return n; return fib(n - 1) + fib(n - 2); } return fib(15);"));
        // the same function called with a double and a string: the ordinary body
        assertEquals(2.5, run.apply("dyn half(n) { return n / 2; } half(8); return half(5.0);"));
        assertEquals("ab1", run.apply("dyn inc(n) { return n + 1; } inc(1); return inc(\"ab\");"));
        // wraparound like dyn int arithmetic
        assertEquals(1410065408, run.apply("dyn sq(n) { return n * n; } return sq(100000);"));
        // a parameter reassigned to a non-int: no int entry, still right
        assertEquals(1.5, run.apply("dyn f(n) { if (n > 0) n = n / 2.0; return n; } return f(3);"));
        assertEquals("x", run.apply("dyn g(n) { if (n > 0) { n = \"x\"; } return n; } dyn s = 0; for (dyn i = 1; i < 3; i++) s = g(i); return s;"));
        // engine: bound through the cell's switch point, then the host replaces the name
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.eval("""
                dyn step(x) { return x * 3 + 1; }
                dyn run(k) { dyn s = 0L; for (dyn i = 0; i < k; i++) { s += step(i); } return s; }
                """);
        javax.script.Invocable inv = (javax.script.Invocable) engine;
        for (int i = 0; i < 30; i++) assertEquals(14950L, inv.invokeFunction("run", 100));
        engine.eval("step = (x) -> \"s\";");
        assertEquals("0ssss", inv.invokeFunction("run", 4));
        engine.eval("step = (x) -> x * 0.5;");
        assertEquals(3.0, inv.invokeFunction("run", 4));
        // a table function field called with an int: the site links to the int entry; another function in
        // the same slot (one returning a string) is another layer, not a wrong unboxing
        assertEquals("30:ss", Interpreter.runWithBigStack(() -> new Interpreter().eval("""
                dyn t = { f: (x) -> x * 2 };
                dyn s = 0;
                for (dyn i = 0; i < 6; i++) { s += t.f(i); }
                t.f = (x) -> "s";
                dyn r = "";
                for (dyn i = 0; i < 2; i++) { r += t.f(i); }
                return s + ":" + r;
                """)));
    }

    /**
     * Inside a guarded region a script array element is read and written without re-testing the
     * array's kind (JArray.rdInt/wrInt): the logical bounds must still hold - an index equal to the
     * length is an error on read and an append on write, even though the buffer is larger.
     */
    @Test
    void regionArrayAccessKeepsLogicalBounds() {
        String src = """
            dyn fill(n) {
                dyn a = array(n, 0);
                for (dyn i = 0; i <= n; i++) { a[i] = i; }
                dyn s = 0;
                for (dyn i = 0; i <= n; i++) { s += a[i]; }
                return s;
            }
            dyn over(n) {
                dyn a = array(n, 0);
                for (dyn i = 0; i < n; i++) { a[i] = i * 2; }
                dyn s = 0;
                for (dyn i = 0; i <= n; i++) { s += a[i]; }
                return s;
            }
            """;
        assertEquals(15, Interpreter.runWithBigStack(() -> new Interpreter().eval(src + "return fill(5);")));
        // array(n, fill) skips the fill for a zero value only: -0.0 and non-zero values are written
        assertEquals("5 true -Infinity 0 false 0.0", new Interpreter().eval(
                "dyn a = array(3, 5); dyn b = array(3, true); dyn c = array(3, -0.0);"
                + " dyn d = array(3, 0); dyn e = array(3, false); dyn f = array(3, 0.0);"
                + " return a[2] + \" \" + b[2] + \" \" + (1.0 / c[2]) + \" \" + d[2] + \" \" + e[2] + \" \" + f[2];"));
        JmpError e = assertThrows(JmpError.class, () -> Interpreter.runWithBigStack(() -> new Interpreter().eval(src + "return over(5);")));
        assertTrue(e.getMessage().contains("out of bounds"), e.getMessage());
    }

    /**
     * A literal with untyped values in Tier 1 (LitSite) links one layer per pattern of the values'
     * kinds. Every pattern must give exactly the table build() gives: a null value creates no key,
     * primitive values get typed slots, and more than eight patterns fall back to build().
     */
    @Test
    void literalSitesMatchBuildForEveryValuePattern() {
        String src = """
            dyn mk(a, b) { return { x: a, y: b }; }
            dyn vals = [ null, 1, 2.5, 3L, true, "s", [1], { q: 1 } ];
            dyn out = "";
            for (dyn i = 0; i < 8; i++) {
                for (dyn j = 0; j < 8; j++) {
                    dyn t = mk(vals[i], vals[j]);
                    out = out + keys(t).size() + ":" + t.x + "," + t.y + ";";
                    t.z = i;              // the table stays an ordinary growable table
                    t.x = "w";            // a typed slot takes another kind
                    out = out + t.x + t.z + (t.y == null ? "n" : "v") + " ";
                }
            }
            return out;
            """;
        Object tier1 = Interpreter.runWithBigStack(() -> new Interpreter().eval(src));
        // the fingerprint of the interpreter's output (-Djmp.tier1=0: build() for every table); this test
        // runs in both modes, so a divergence of either one from it fails
        assertEquals(880, ((String) tier1).length(), (String) tier1);
        assertEquals(49421922, tier1.hashCode(), (String) tier1);
        assertTrue(((String) tier1).startsWith("0:null,null;w0n 1:null,1;w0v"), (String) tier1);
    }

    /** A chain of string `+` compiled as one StringConcatFactory site formats every operand as Jumper does. */
    @Test
    void stringConcatChainsFormatLikeJumper() {
        String src = """
            dyn f(i) {
                dyn d = i * 0.5;
                dyn n = null;
                long l = 10000000000L;
                int k = i;
                dyn t = { a: 1 };
                dyn s = "x" + i + "y" + d + n + true + l + k + 2.0 + "z" + (i + 1) + t.a + [1, 2] + ("p" + (k - 1));
                s += k;
                s += 1.5;
                return s;
            }
            return f(3) + " " + f(4);
            """;
        assertEquals("x3y1.5nulltrue1000000000032.0z41[1, 2]p231.5 x4y2.0nulltrue1000000000042.0z51[1, 2]p341.5",
                Interpreter.runWithBigStack(() -> new Interpreter().eval(src)));
    }

    /**
     * `new P(...)` behind a top-level name (engine mode) reads the class as a constant under the
     * cell's switch point (Indy.ValueSite); rebinding the name must still be seen by a warmed-up site.
     */
    @Test
    void newThroughATopLevelClassNameFollowsRebinding() throws Exception {
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.eval("""
                class P { int a; P(int a) { this.a = a; } int v() { return a; } }
                class Q { int a; Q(int a) { this.a = a * 10; } int v() { return a; } }
                long make(int n) { long s = 0; for (int i = 0; i < n; i++) { dyn p = new P(i); s += p.v(); } return s; }
                """);
        javax.script.Invocable inv = (javax.script.Invocable) engine;
        for (int i = 0; i < 30; i++) assertEquals(45L, inv.invokeFunction("make", 10));
        engine.eval("P = Q;");
        assertEquals(450L, inv.invokeFunction("make", 10));
        engine.put("P", null);
        assertThrows(javax.script.ScriptException.class, () -> inv.invokeFunction("make", 10));
    }

    /**
     * Element access on a script array of objects outside a region is inline (kind OBJ, index in
     * [0, size)); every other case - an append, out of range, another kind, a Java array - must be
     * exactly the old JArray.get/set.
     */
    @Test
    void inlineObjectArrayAccessKeepsJArraySemantics() {
        assertEquals("a,b,c,d|null", Interpreter.runWithBigStack(() -> new Interpreter().eval("""
                dyn r = array(3, null);
                dyn names = ["a", "b", "c"];
                for (int i = 0; i < 3; i++) { r[i] = names[i]; }
                r[3] = "d";                         // append at size
                dyn s = "";
                for (int i = 0; i < 4; i++) { s += r[i] + (i < 3 ? "," : ""); }
                dyn e = null;
                try { e = r[4]; } catch (x) { e = "err"; }
                return s + "|" + (e == "err" ? "null" : e);
                """)));
        assertThrows(JmpError.class, () -> Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn r = array(2, null); for (int i = 0; i < 2; i++) { r[i] = \"x\"; } return r[-1];")));
        // an int array stays an int array (not the OBJ path), and a Java array goes the generic way
        assertEquals(6, Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn r = array(3, 0); for (int i = 0; i < 3; i++) { r[i] = i + 1; } dyn s = 0; for (int i = 0; i < 3; i++) { s += r[i]; } return s;")));
        assertEquals("q", Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "dyn r = new Object[2]; for (int i = 0; i < 2; i++) { r[i] = \"q\"; } return r[1];")));
    }

    @Test
    void leanLayoutTablesGrowRetypeAndGoDictionary() {
        // A literal's table starts as a Lean layout object (fields only, no store): adding a key gives
        // it a private shape holding the overflow, the layout then switches literals to its Full class,
        // many keys turn it into a dictionary. Sites that saw every kind must keep reading right.
        String src = """
            dyn mk(int i) { return { a: i, b: i * 0.5, c: "c" + i }; }
            dyn mk2(x, y) { return { p: x, q: y }; }
            dyn sum(t) { return t.a + int(t.b); }
            dyn out = 0; dyn ks = "";
            for (int i = 0; i < 200; i++) {
                dyn t = mk(i);
                if (i % 3 == 0) { t.d = i; }
                if (i % 50 == 7) { for (int k = 0; k < 70; k++) { t["k" + k] = k; } }
                if (i % 5 == 1) { t.a = "x"; }
                out += (t.a == "x" ? 0 : sum(t)) + (t.d == null ? 0 : t.d) + len(t);
                dyn u = mk2(i, "s");
                if (i % 4 == 0) { u.r = 1; }
                out += u.p + len(u) + (u.r == null ? 0 : u.r);
                if (i == 3) { ks += str(keys(t)) + t.c; }
                if (i == 57) { ks += t.k69 + t.c + t.d; }
            }
            return "" + out + "|" + ks;
            """;
        long out = 0;
        for (int i = 0; i < 200; i++) {
            boolean d = i % 3 == 0, dict = i % 50 == 7, x = i % 5 == 1;
            out += (x ? 0 : i + (int) (i * 0.5)) + (d ? i : 0) + 3 + (d ? 1 : 0) + (dict ? 70 : 0);
            out += i + 2 + (i % 4 == 0 ? 2 : 0);
        }
        assertEquals(out + "|[a, b, c, d]c369c5757", Interpreter.runWithBigStack(() -> new Interpreter().eval(src)));
    }

    @Test
    void foldedMethodResultsMatchTheGenericPath() {
        // `o.m(x) OP k` needed as a primitive: the method site applies OP itself - in int for a target
        // returning int, through Ops.op + unbox for anything else (double, long, Java, table functions).
        String src = """
                class I { int m(int x) { return x * 1000003 + 7; } }
                class D { double m(int x) { return x / 4.0; } }
                class L { long m(int x) { return 3000000000L + x; } }
                class Y { dyn m(x) { return x - 5; } }
                dyn t = { m: (x) -> x * 2 };
                dyn objs = [new I(), new D(), new L(), new Y(), t, new I()];
                long sl = 0L; double sd = 0.0; int si = 0;
                for (int i = 0; i < 600; i++) {
                    dyn o = objs[i % 6];
                    sd += o.m(i) / 7;
                    if (i % 6 != 1) {
                        sl += o.m(i) % 1000;
                        long p = o.m(i) * 3;
                        sl += p - o.m(i) + 11;
                    }
                    if (i % 6 != 1 && i % 6 != 2) { si += o.m(i) - 2; }
                    double q = o.m(i);
                    sd += q;
                }
                dyn list = new java.util.ArrayList();
                list.add(1); list.add(2); list.add(3);
                int js = list.size() % 2;
                return "" + sl + "|" + sd + "|" + si + "|" + js;
                """;
        Object tier = Interpreter.runWithBigStack(() -> new Interpreter().eval(src));
        assertEquals("719800801600|4.113146376712857E11|-229272144|1", tier);   // = Tier 0 and -Djmp.foldcall=0
        // errors come from the same place: a string result, a zero divisor in the generic op
        JmpError e = assertThrows(JmpError.class, () -> Interpreter.runWithBigStack(() -> new Interpreter().eval(
                "class S { dyn m(x) { return \"s\"; } } dyn o = new S(); long r = 0L; for (int i = 0; i < 300; i++) { r += o.m(i) % 3; } return r;")));
        assertTrue(e.getMessage().contains("%"), e.getMessage());
    }

    @Test
    void configFilesAreDataWithBranchesOnly() {
        // *.jmc: values, variables, if/else, ternaries, literals - the returned value, or the top-level variables
        Object v = me.padej.jumper.interp.Config.parse("""
                dyn prod = false;
                dyn port = prod ? 25565 : 25566;
                dyn mode = "survival";
                if (!prod) { mode = "creative"; } else { mode = "hardcore"; }
                return { server: { port: port, mode: mode }, worlds: ["a", "b"], half: port / 2 };
                """);
        assertEquals("{server: {port: 25566, mode: creative}, worlds: [a, b], half: 12783}", me.padej.jumper.runtime.Ops.str(v));
        assertEquals("{port: 1, name: x}", me.padej.jumper.runtime.Ops.str(
                me.padej.jumper.interp.Config.parse("dyn port = 1; dyn name = \"x\";")));
        // reserved words are plain keys and member names where nothing else can stand
        assertEquals("1|3", me.padej.jumper.runtime.Ops.str(me.padej.jumper.interp.Config.parse(
                "dyn g = { default: 1, new: 3 }; return g.default + \"|\" + g.new;")));
        // no loops, functions, lambdas, classes, import, try - rejected at parse time, with the position
        for (String bad : new String[]{"while (true) {}", "for (int i = 0; i < 3; i++) {}", "dyn f(x) { return x; }",
                "dyn f = (x) -> x;", "class P {}", "import \"a.jmp\";", "try { } catch (e) { }", "throw 1;"}) {
            ParseError e = assertThrows(ParseError.class, () -> me.padej.jumper.interp.Config.parse(bad), bad);
            assertTrue(e.getMessage().contains("not allowed in a config"), e.getMessage());
        }
        // and no Java
        SecurityException e = assertThrows(SecurityException.class, () -> me.padej.jumper.interp.Config.parse("return java.lang.System.nanoTime();"));
        assertTrue(e.getMessage().contains("Access denied"), e.getMessage());
    }

    @Test
    void pureDataConfigsReadFastGiveExactlyTheFullPathValues() {
        String[] configs = {
                """
                // a comment first
                return {
                    server: { host: "0.0.0.0", port: 25565, motd: 'single "quoted"', online: true, },
                    nums: [0, -0, 007, 2147483647, -2147483648, 2147483648L, 0xFF, 0xFFFFFFFF, 0x1L, 1_000,
                           1.5, -0.0, 1e3, 1.5e-3, 12.345678901234567, 0.1, 3d, 5L, 123456789012345.5, 99999999999999.9, 0.000001],
                    esc: "a\\"b\\\\n\\u0041\\t",
                    groups: { default: { weight: 10 }, "quoted key": 1, new: [] },
                    empty: {}, nulls: [null, 1, null], gone: null,
                    /* block */ last: false
                };
                """,
                "return [1, 2, 3,];",
                "int a = 1;\nboolean isDev = false;\nint permLevel = isDev ? 10 : 0;\ndyn obj = {\"ye\", 123, isDev};\n"
                        + "if (!isDev && a < 2) { permLevel = 7; } else permLevel = 1;\nlong big = a * 3;\ndouble half = a / 2.0;\n"
                        + "String motd = \"lvl \" + permLevel;\ndyn server = { port: 25565 + a, dev: isDev || a == 1, tags: {\"x\", [1, 2]} };",
                "dyn port = 25565;\ndyn name = \"x\";\ndyn none = null;\ndyn list = [1.25, \"a\"];",
                "",
                "return { a: 1, a: 2 };",                // duplicate key: full path semantics
                "return { a: 1 + 2 };",                  // an expression: full path
                "dyn a = 1; return { b: a };",
                "return 5;",
                "return \"text\";",
        };
        for (String src : configs) {
            Object full = me.padej.jumper.interp.Config.parseFull(src);
            Object fast = me.padej.jumper.interp.Config.parse(src);
            assertDeepSame(full, fast, src);
        }
        // malformed: both ways end in the full path's error
        for (String bad : new String[]{"return 2147483648;", "return \"open;", "return { a: 1,, };", "return [1 2];", "return {a:1}; x"}) {
            assertThrows(RuntimeException.class, () -> me.padej.jumper.interp.Config.parse(bad), bad);
        }
    }

    private static void assertDeepSame(Object a, Object b, String where) {
        if (a instanceof me.padej.jumper.runtime.JTable ta) {
            assertTrue(b instanceof me.padej.jumper.runtime.JTable, where);
            me.padej.jumper.runtime.JTable tb = (me.padej.jumper.runtime.JTable) b;
            assertEquals(new java.util.ArrayList<>(ta.keys()), new java.util.ArrayList<>(tb.keys()), where);
            assertEquals(ta.shape == null, tb.shape == null, where);
            for (Object k : ta.keys()) assertDeepSame(ta.get(k), tb.get(k), where + " ." + k);
        } else if (a instanceof me.padej.jumper.runtime.JArray xa) {
            assertTrue(b instanceof me.padej.jumper.runtime.JArray, where);
            me.padej.jumper.runtime.JArray xb = (me.padej.jumper.runtime.JArray) b;
            assertEquals(xa.size(), xb.size(), where);
            for (int i = 0; i < xa.size(); i++) assertDeepSame(xa.get(i), xb.get(i), where + " [" + i + "]");
        } else {
            assertEquals(a, b, where);
            if (a != null) assertEquals(a.getClass(), b.getClass(), where);
            if (a instanceof Double d) assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits((Double) b), where);
        }
    }

    @Test
    void configFastPathAgreesWithTheFullPathOnRandomConfigs() {
        // Random configs in (and around) the fast path's subset: typed and dyn declarations, assignments,
        // if/else, ?:, || &&, comparisons, arithmetic, tables and arrays, references to earlier variables -
        // including the ill-typed ones. Either both paths give the same value, or both the same error.
        java.util.Random rnd = new java.util.Random(20260926);
        String[] types = {"dyn", "int", "long", "double", "boolean", "String"};
        int fast = 0;
        for (int n = 0; n < 3000; n++) {
            StringBuilder src = new StringBuilder();
            java.util.List<String> vars = new java.util.ArrayList<>();
            int stmts = 1 + rnd.nextInt(6);
            for (int k = 0; k < stmts; k++) {
                int kind = rnd.nextInt(10);
                if (kind < 6 || vars.isEmpty()) {
                    String name = "v" + vars.size();
                    src.append(types[rnd.nextInt(types.length)]).append(' ').append(name).append(" = ")
                            .append(randomConfigExpr(rnd, vars, 3)).append(";\n");
                    vars.add(name);
                } else if (kind < 8) {
                    src.append(vars.get(rnd.nextInt(vars.size()))).append(" = ").append(randomConfigExpr(rnd, vars, 2)).append(";\n");
                } else {
                    src.append("if (").append(randomConfigExpr(rnd, vars, 2)).append(") { ")
                            .append(vars.get(rnd.nextInt(vars.size()))).append(" = ").append(randomConfigExpr(rnd, vars, 2))
                            .append("; } else { ").append(vars.get(rnd.nextInt(vars.size()))).append(" = ")
                            .append(randomConfigExpr(rnd, vars, 2)).append("; }\n");
                }
            }
            if (rnd.nextInt(5) == 0 && !vars.isEmpty()) src.append("return ").append(randomConfigExpr(rnd, vars, 2)).append(";\n");
            String code = src.toString();
            Object full, got;
            String fullErr = null, gotErr = null;
            try { full = me.padej.jumper.interp.Config.parseFull(code); } catch (RuntimeException e) { full = null; fullErr = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            try { got = me.padej.jumper.interp.Config.parse(code); } catch (RuntimeException e) { got = null; gotErr = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            assertEquals(fullErr, gotErr, code);
            if (fullErr == null) {
                assertDeepSame(full, got, code);
                fast++;
            }
        }
        assertTrue(fast > 150, "too few configs evaluated without an error: " + fast);   // most random configs are ill-typed on purpose
    }

    private static String randomConfigExpr(java.util.Random r, java.util.List<String> vars, int depth) {
        int k = r.nextInt(depth <= 0 ? 6 : 14);
        switch (k) {
            case 0: return Integer.toString(r.nextInt(2000) - 1000);
            case 1: return (r.nextInt(400) - 200) / 8.0 + "";
            case 2: return r.nextBoolean() ? "true" : "false";
            case 3: return "\"s" + r.nextInt(10) + "\"";
            case 4: return vars.isEmpty() ? "1" : vars.get(r.nextInt(vars.size()));
            case 5: return r.nextInt(3) == 0 ? "null" : (r.nextInt(100) + "L");
            case 6: case 7: {
                String[] ops = {"+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=", "&&", "||"};
                return randomConfigExpr(r, vars, depth - 1) + " " + ops[r.nextInt(ops.length)] + " " + randomConfigExpr(r, vars, depth - 1);
            }
            case 8: return randomConfigExpr(r, vars, depth - 1) + " ? " + randomConfigExpr(r, vars, depth - 1) + " : " + randomConfigExpr(r, vars, depth - 1);
            case 9: return "(" + randomConfigExpr(r, vars, depth - 1) + ")";
            case 10: return (r.nextBoolean() ? "-" : "!") + randomConfigExpr(r, vars, depth - 1);
            case 11: return "{ a: " + randomConfigExpr(r, vars, depth - 1) + ", b: " + randomConfigExpr(r, vars, depth - 1) + " }";
            case 12: return "[" + randomConfigExpr(r, vars, depth - 1) + ", " + randomConfigExpr(r, vars, depth - 1) + "]";
            default: return "{" + randomConfigExpr(r, vars, depth - 1) + ", " + randomConfigExpr(r, vars, depth - 1) + "}";
        }
    }

    @Test
    void configReaderCachesNeverLeakOneFileIntoAnother() {
        // The fast path remembers the strings and names it saw at each position of the text. Files that
        // put different things at the same positions - a longer name, a quote inside, a name that is a
        // prefix of the old one, an escape - must read as themselves, alternately, many times.
        String[] files = {
                "dyn abc = \"say 'hi'\"; dyn b = 1;",
                "dyn abc = 'say \"hi\"'; dyn b = 2;",
                "dyn ab = \"say\"; dyn cd = \"x\";",
                "dyn abcd = \"say 'hi' there\"; dyn b = 3;",
                "dyn abc = \"s\\\"ay\"; dyn b = 4;",
                "dyn abc = {k: \"say 'hi'\", kk: 1};",
                "dyn abc = {kk: \"say\", k: 1};",
                "dyn a = [\"x\", \"xy\", \"xyz\"];",
                "dyn a = [\"xy\", \"x\", \"xyz\"];",
        };
        for (int round = 0; round < 50; round++) {
            for (String f : files) {
                assertDeepSame(me.padej.jumper.interp.Config.parseFull(f), me.padej.jumper.interp.Config.parse(f), f);
            }
        }
    }

    @Test
    void configTemplateCopiesAreIndependentOfWhatTheHostDidWithTheLastOne() {
        // An unchanged text is answered from a prototype: every parse must still hand out a fresh value -
        // whatever the host wrote into the previous result must not show up in the next one.
        String src = "int port = 25565; boolean isDev = false; int perm = isDev ? 10 : 0;\n"
                + "dyn obj = {\"ye\", 123, isDev}; dyn nums = [1, 2, 3]; dyn db = {host: \"localhost\", pool: [4, 5]};";
        Object expected = me.padej.jumper.interp.Config.parseFull(src);
        for (int round = 0; round < 20; round++) {
            Object got = me.padej.jumper.interp.Config.parse(src);
            assertDeepSame(expected, got, src);
            me.padej.jumper.runtime.JTable t = (me.padej.jumper.runtime.JTable) got;
            t.put("port", 1);
            t.put("added" + round, "x");
            ((me.padej.jumper.runtime.JArray) t.get("obj")).set(0, "changed");
            ((me.padej.jumper.runtime.JArray) t.get("nums")).set(1, 99);
            ((me.padej.jumper.runtime.JArray) t.get("nums")).set(2, "now a string");
            me.padej.jumper.runtime.JTable db = (me.padej.jumper.runtime.JTable) t.get("db");
            db.put("host", "elsewhere");
            db.put("extra", 7);
            ((me.padej.jumper.runtime.JArray) db.get("pool")).set(0, 0);
        }
        // different texts in turn, some sharing a template slot, never answered with each other's value
        for (int round = 0; round < 5; round++) {
            for (int k = 0; k < 200; k++) {
                String f = "int a = " + k + "; dyn s = \"" + "v".repeat(k % 7) + "\";";
                assertDeepSame(me.padej.jumper.interp.Config.parseFull(f), me.padej.jumper.interp.Config.parse(f), f);
            }
        }
    }

    @Test
    void longConfigStringsReadLikeTheLexerReadsThem() {
        // Past a few dozen characters the fast path finds a string's end by a vectorized search: every
        // length around the switch and the cache limit, with escapes, the other quote and a line break.
        java.util.List<String> files = new java.util.ArrayList<>();
        for (int len : new int[] {40, 47, 48, 49, 50, 100, 255, 256, 257, 300, 1000, 5000}) {
            String body = "abcdefghij".repeat(len / 10 + 1).substring(0, len);
            files.add("dyn s = \"" + body + "\"; dyn t = 1;");
            files.add("dyn s = '" + body + "\"q\"'; dyn t = 2;");
            files.add("dyn s = \"" + body + "\\n\\\"" + body + "\"; dyn t = 3;");
            files.add("dyn s = \"" + body.substring(0, len / 2) + "\\t" + body.substring(len / 2) + "\";");
            files.add("dyn s = \"" + body + "\u00e9\u4e16\"; dyn t = {k: \"" + body + "\"};");
            files.add("dyn s = \"" + body + "\ndyn t = 1;\"");   // a line break inside: the full path reports it
            files.add("dyn s = \"" + body);                        // never closed
            files.add("dyn s = \"" + body + "\\\";");            // closed only by an escaped quote
            files.add("// " + body + "\nint a = 1; /* " + body + " */ int b = 2; // tail");
            files.add("int a = 1; /*/ " + body + " */ int b = a; /**/ int c = 3;");
            files.add("int a = 1; /* " + body + " never closed");
            files.add("int a = 1; /* " + body + " * / */ int b = 2;");
        }
        for (int round = 0; round < 3; round++) {
            for (String f : files) {
                Object full, fast;
                try { full = me.padej.jumper.interp.Config.parseFull(f); } catch (RuntimeException e) { full = e.getClass(); }
                try { fast = me.padej.jumper.interp.Config.parse(f); } catch (RuntimeException e) { fast = e.getClass(); }
                if (full instanceof Class<?> || fast instanceof Class<?>) assertEquals(full, fast, f);
                else assertDeepSame(full, fast, f);
            }
        }
    }
}
