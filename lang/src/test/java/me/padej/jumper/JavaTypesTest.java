package me.padej.jumper;

import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.parser.ParseError;
import me.padej.jumper.runtime.JmpError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Java class as a type: `Random rand = new Random();`. The variable (parameter, return, field, for-each
 * variable, lambda parameter) holds null or an instance of that class - checked like a script class type:
 * statically where the value's type is known, at run time otherwise. Runs in every tier mode of the test script.
 */
class JavaTypesTest {
    private static Object run(String src) {
        return Interpreter.runWithBigStack(() -> new Interpreter().eval(src));
    }

    private static void parseError(String src, String fragment) {
        ParseError e = assertThrows(ParseError.class, () -> run(src), src);
        assertTrue(e.getMessage().contains(fragment), () -> src + " -> " + e.getMessage());
    }

    private static void runtimeError(String src, String fragment) {
        JmpError e = assertThrows(JmpError.class, () -> run(src), src);
        assertTrue(e.getMessage().contains(fragment), () -> src + " -> " + e.getMessage());
    }

    @Test
    void importedAndJavaLangClassesAreTypes() {
        assertEquals(3, run("import java.util.Random;\nRandom rand = new Random(7);\nint n = rand.nextInt(10);\nreturn n >= 0 ? 3 : 0;"));
        assertEquals("ab", run("StringBuilder sb = new StringBuilder(\"a\"); sb.append(\"b\"); return sb.toString();"));
        // an interface: whatever implements it - a script array is a java.util.List
        assertEquals(2, run("import java.util.List; import java.util.ArrayList;\nList a = new ArrayList(); a.add(1);\nList b = [1, 2];\nreturn b.size();"));
        // boxed values fit their classes
        assertEquals(5, run("Integer i = 5; Number n = i; return n;"));
        assertNull(run("import java.util.Random; Random r = null; return r;"));
        // fields, methods and statics of a class (its body is prescanned before the block's statements - the
        // import still counts: types are looked up in the file's imports)
        assertEquals(1, run("import java.util.Random;\nclass Box { Random r = new Random(1); static Random seed; Random get() { return r; } }\n"
                + "return new Box().get() != null ? 1 : 0;"));
    }

    @Test
    void theTypeIsCheckedWhereTheValueEnters() {
        // statically known: a parse error
        parseError("import java.util.Random; Random r = \"s\";", "Cannot assign string to Random");
        parseError("import java.util.Random; Random r = new Random(); r = 5;", "Cannot assign int to Random");
        parseError("import java.util.Random; Random g() { return \"s\"; }", "Cannot assign string to Random");
        parseError("import java.util.Random; class P {} Random r = new P();", "Cannot assign P to Random");
        // known only at run time: the check on entry
        runtimeError("import java.util.Random; dyn s = \"x\"; Random r = s;", "Expected Random, got string");
        runtimeError("import java.util.Random; Random r = null; dyn s = 1; r = s;", "Expected Random, got int");
        runtimeError("import java.util.Random; int f(Random r) { return 1; } dyn s = \"x\"; f(s);", "Expected Random, got string");
        runtimeError("import java.util.Random; Random g(dyn v) { return v; } g(1);", "Expected Random, got int");
        runtimeError("import java.util.Random; for (Random r : [new Random(), 1]) { }", "Expected Random, got int");
        runtimeError("import java.util.Random; dyn f = (Random r) -> 1; f(\"x\");", "Expected Random, got string");
        runtimeError("import java.util.Random; dyn one() { return 1; } class B { Random r = one(); } new B();", "Expected Random, got int");
        runtimeError("dyn s = 1; Integer i = null; dyn l = 5L; i = l;", "Expected Integer, got long");
    }

    @Test
    void anEngineTopLevelVariableKeepsItsJavaType() throws Exception {
        javax.script.ScriptEngine engine = new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
        engine.eval("import java.util.Random; Random r = new Random(1); dyn x = 5;");
        assertTrue(engine.get("r") instanceof java.util.Random);
        // within one eval (across evals the name's type is not known - as for `int n`, see IssueFixesTest.s7)
        assertThrows(javax.script.ScriptException.class, () -> engine.eval("import java.util.Random; Random q = new Random(1); dyn y = 5; q = y;"));
    }

    @Test
    void notATypeIsStillAnError() {
        // two names in a row where the first is not a class: the usual syntax error
        parseError("dyn random = 1; random rand = 2;", "Expected ';'");
        parseError("Nope rand = 2;", "Undefined variable 'Nope'");
    }

    @Test
    void aClassClosedByThePolicyCannotBeAType() {
        me.padej.jumper.runtime.Access policy = me.padej.jumper.runtime.Access.parse("Policy.allowPackage(\"java.util\");");
        Exception e = assertThrows(SecurityException.class, () -> Interpreter.runWithBigStack(
                () -> new Interpreter().access(policy).eval("Thread t = null;")));
        assertTrue(e.getMessage().contains("Access denied: java.lang.Thread"), e.getMessage());
    }
}
