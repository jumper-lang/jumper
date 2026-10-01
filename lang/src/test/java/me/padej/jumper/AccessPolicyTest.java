package me.padej.jumper;

import me.padej.jumper.hostapi.Dog;
import me.padej.jumper.hostapi.Player;
import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.runtime.Access;
import me.padej.jumper.runtime.JFunction;
import me.padej.jumper.runtime.JmpError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sandbox tests for the access policy (*.jma). Every "escape" below is what a hostile script
 * would try; each must end in {@code JmpError("Access denied: ...")}, at parse time where a name
 * is resolved and at call time where a member is selected. The policies are written in the real
 * {@code .jma} format so the file path is covered too, not only the Java builder.
 *
 * <p>What the policy does NOT promise is tested as well (memory: only a per-container limit;
 * the real boundary of a sandbox is the JVM heap of the process that runs the scripts).
 */
class AccessPolicyTest {

    private static final String PLAYER = Player.class.getName();   // me.padej.jumper.hostapi.Player

    /** The policy of most tests: java.lang + java.util, the Player API without ban(). */
    private static Access policy() {
        return Access.parse("""
                Policy.allowPackage("java.lang");
                Policy.allowPackage("java.util");
                Policy.allowMethod("java.lang.System", "currentTimeMillis");
                Policy.allowClass("%s").denyMethod("ban");
                """.formatted(PLAYER));
    }

    private static Interpreter sandbox(Access policy) {
        return new Interpreter().access(policy).define("player", new Player());
    }

    private static void denied(Interpreter jj, String src) {
        Exception e = assertThrows(Exception.class, () -> jj.eval(src), src);
        assertTrue(String.valueOf(e.getMessage()).contains("Access denied"), src + " -> " + e);
    }

    private static void denied(Interpreter jj, String src, String what) {
        Exception e = assertThrows(Exception.class, () -> jj.eval(src), src);
        String m = String.valueOf(e.getMessage());
        assertTrue(m.contains("Access denied") && m.contains(what), src + " -> " + e);
    }

    // ------------------------------------------------------------ 1. static names: import / new / qualified / .class

    @Test
    void explicitUnlistedImportFails() {
        Interpreter jj = sandbox(policy());
        denied(jj, "import java.lang.Runtime; return 1;", "java.lang.Runtime");        // escape class
        denied(jj, "import java.nio.file.Files; return 1;", "java.nio.file.Files");    // package not listed
        denied(jj, "import " + Dog.class.getName() + "; return new Dog().bark();");    // host class not listed
        // and the same script parses fine without a policy
        assertEquals("woof", new Interpreter().eval("import " + Dog.class.getName() + "; return new Dog().bark();"));
    }

    @Test
    void fullyQualifiedNameFails() {
        Interpreter jj = sandbox(policy());
        denied(jj, "dyn r = java.lang.Runtime.getRuntime(); return r;", "java.lang.Runtime");
        denied(jj, "return new java.io.File(\".\").exists();", "java.io.File");
        denied(jj, "return new " + Dog.class.getName() + "().bark();");
        // the name is cut off at parse time: nothing before it runs
        int[] ran = {0};
        jj.define("mark", (JFunction) a -> { ran[0]++; return null; });
        denied(jj, "mark(); return java.lang.Runtime.getRuntime();");
        assertEquals(0, ran[0], "the script must not start when a name in it is denied");
    }

    @Test
    void classLiteralAccessFails() {
        Interpreter jj = sandbox(policy());
        denied(jj, "return java.lang.ProcessBuilder.class;", "java.lang.ProcessBuilder");
        denied(jj, "return " + Dog.class.getName() + ".class;");
        // an allowed class literal exists, but it is inert: Class itself is an escape class
        denied(jj, "return java.util.ArrayList.class.getMethods();", "java.lang.Class");
        denied(jj, "return java.util.ArrayList.class.getClassLoader();", "java.lang.Class");
    }

    // ------------------------------------------------------------ 2. reflection

    @Test
    void reflectionForNameEscapingFails() {
        Interpreter jj = sandbox(policy());
        denied(jj, "return java.lang.Class.forName(\"java.lang.System\");", "java.lang.Class");
        denied(jj, "return Class.forName(\"java.lang.System\");", "java.lang.Class");   // java.lang is allowed - Class is not
        // ClassLoader by name is just as closed
        denied(jj, "return ClassLoader.getSystemClassLoader();", "java.lang.ClassLoader");
        denied(jj, "return Thread.currentThread().getContextClassLoader();", "java.lang.Thread");
    }

    @Test
    void getClassGetMethodEscapingFails() {
        Interpreter jj = sandbox(policy());
        // getClass() is closed on every object until java.lang.Class is explicitly allowed
        denied(jj, "return player.getClass();", "getClass");
        denied(jj, "return \"s\".getClass();", "getClass");
        denied(jj, "return new java.util.ArrayList().getClass().getName();", "getClass");

        // even with Class opened on purpose, a class value answers only who it is (getName...): no
        // Method/Field/Constructor objects are handed out at all
        Interpreter open = sandbox(policy().allowClass("java.lang.Class"));
        assertEquals(PLAYER, open.eval("return player.getClass().getName();"));
        denied(open, "return player.getClass().getMethod(\"ban\").invoke(player);", "java.lang.Class.getMethod");
        denied(open, "dyn ms = player.getClass().getMethods(); return ms[0].getName();", "java.lang.Class.getMethods");
        denied(open, "return player.getClass().getDeclaredField(\"health\").get(player);", "java.lang.Class.getDeclaredField");
        denied(open, "return player.getClass().getConstructors()[0].newInstance();", "java.lang.Class.getConstructors");
    }

    @Test
    void classLoaderEscapingFails() {
        Interpreter open = sandbox(policy().allowClass("java.lang.Class"));
        // not even the ClassLoader object is handed out, whatever the rules say
        denied(open, "return player.getClass().getClassLoader().loadClass(\"java.lang.System\");", "getClassLoader");
        denied(open, "return player.getClass().getClassLoader().getParent();", "getClassLoader");
        denied(open, "return player.getClass().getModule().getClassLoader();", "java.lang.Class.getModule");
        // a package rule never opens it either
        Interpreter jj = sandbox(policy().allowPackage("java"));
        denied(jj, "return ClassLoader.getSystemClassLoader();", "java.lang.ClassLoader");
        denied(jj, "return new java.lang.ProcessBuilder(\"x\");", "java.lang.ProcessBuilder");
    }

    @Test
    void methodHandlesEscapingFails() {
        Interpreter jj = sandbox(policy().allowPackage("java"));       // "everything" - except the escapes
        denied(jj, "import java.lang.invoke.MethodHandles; return 1;", "java.lang.invoke.MethodHandles");
        denied(jj, "return java.lang.invoke.MethodHandles.lookup();", "java.lang.invoke.MethodHandles");
        denied(jj, "return java.lang.invoke.MethodType.methodType(java.lang.Object.class);", "java.lang.invoke.MethodType");
        denied(jj, "import java.lang.reflect.Proxy; return 1;", "java.lang.reflect.Proxy");
        denied(jj, "return java.lang.StackWalker.getInstance();", "java.lang.StackWalker");
        denied(jj, "return jdk.internal.misc.Unsafe.getUnsafe();", "jdk.internal.misc.Unsafe");
        denied(jj, "return sun.misc.Unsafe.class;", "sun.misc.Unsafe");
        // the interpreter itself is an escape class: a script cannot reach its own policy
        denied(jj, "return me.padej.jumper.runtime.Access.current();", "me.padej.jumper.runtime.Access");
        denied(jj, "return me.padej.jumper.interp.Interpreter.class;", "me.padej.jumper.interp.Interpreter");
    }

    // ------------------------------------------------------------ 3. rule precedence

    @Test
    void packageAllowMethodDenyHierarchy() {
        Interpreter jj = sandbox(policy());               // java.lang allowed as a package
        assertEquals(7, jj.eval("return Math.max(3, 7);"));
        assertEquals("AB", jj.eval("return \"ab\".toUpperCase();"));
        assertTrue((Long) jj.eval("return System.currentTimeMillis();") > 0);   // the one allowed member
        denied(jj, "System.exit(1); return 0;", "java.lang.System.exit");        // the rest of System is closed
        denied(jj, "return System.getProperty(\"user.dir\");", "java.lang.System.getProperty");
        denied(jj, "return System.getenv();", "java.lang.System.getenv");
        denied(jj, "return Runtime.getRuntime();", "java.lang.Runtime");          // escape: not opened by the package
        denied(jj, "return new Thread(() -> {});", "java.lang.Thread");

        // the longest package rule wins: java.util is open, java.util.regex is closed inside it
        Interpreter narrowed = sandbox(policy().denyPackage("java.util.regex"));
        assertEquals(2, narrowed.eval("dyn l = new java.util.ArrayList(); l.add(1); l.add(2); return l.size();"));
        denied(narrowed, "return java.util.regex.Pattern.compile(\"a\");", "java.util.regex.Pattern");

        // an explicit class rule beats the package rule in both directions
        Interpreter mixed = sandbox(Access.parse("""
                Policy.denyPackage("java.util");
                Policy.allowClass("java.util.ArrayList");
                Policy.allowPackage("java.lang");
                Policy.denyClass("java.lang.StringBuilder");
                """));
        assertEquals(1, mixed.eval("dyn l = new java.util.ArrayList(); l.add(1); return l.size();"));
        denied(mixed, "return new java.util.HashMap();", "java.util.HashMap");
        denied(mixed, "return new StringBuilder();", "java.lang.StringBuilder");
        // deny wins over allow on the same target
        Interpreter both = sandbox(Access.parse("Policy.allowClass(\"java.util.HashMap\"); Policy.denyClass(\"java.util.HashMap\");"));
        denied(both, "return new java.util.HashMap();", "java.util.HashMap");
    }

    @Test
    void classAllowMethodDeny() {
        Interpreter jj = sandbox(policy());
        assertEquals(20, jj.eval("return player.health();"));
        assertEquals(25, jj.eval("player.heal(5); return player.health();"));
        denied(jj, "player.ban(); return 1;", PLAYER + ".ban");
        // the same through Tier 1: a function with a loop is compiled at once, the call goes through invokedynamic
        assertEquals(30, jj.eval("""
                int run(int n) { dyn s = 0; for (int i = 0; i < n; i++) { player.heal(1); s = player.health(); } return s; }
                return run(5);
                """));
        denied(jj, """
                void attack(int n) { for (int i = 0; i < n; i++) player.ban(); }
                attack(3); return 1;
                """, PLAYER + ".ban");
        // a method allowed by name on a closed class
        Interpreter narrow = sandbox(Access.parse("Policy.allowMethod(\"" + PLAYER + "\", \"health\");"));
        assertEquals(20, narrow.eval("return player.health();"));
        denied(narrow, "player.heal(1); return 1;", PLAYER + ".heal");
        // a field: allowField / denyField
        Interpreter fields = new Interpreter().access(Access.parse("""
                Policy.allowClass("java.awt.Point").denyField("y");
                """));
        assertEquals(3, fields.eval("dyn p = new java.awt.Point(3, 4); return p.x;"));
        denied(fields, "dyn p = new java.awt.Point(3, 4); return p.y;", "java.awt.Point.y");
    }

    @Test
    void hostObjectsNeedTheirClassInThePolicy() {
        // Objects the host defines are no exception: to call a method on them, their class must be allowed.
        // Deliberate - the host knows what it puts in and adds one line; otherwise every define() would
        // be a hole the policy file cannot see.
        Interpreter jj = new Interpreter().access(Access.parse("Policy.allowPackage(\"java.lang\");"))
                .define("game", new Dog());
        denied(jj, "return game.bark();", Dog.class.getName());
        Interpreter allowed = new Interpreter().access(Access.parse("Policy.allowClass(\"" + Dog.class.getName() + "\");"))
                .define("game", new Dog());
        assertEquals("woof", allowed.eval("return game.bark();"));
        // host *functions* (JFunction) are the host's own code, not Java access: always callable
        Interpreter fn = new Interpreter().access(Access.none()).define("twice", (JFunction) a -> ((Number) a[0]).intValue() * 2);
        assertEquals(42, fn.eval("return twice(21);"));
        // a callback the script hands to Java runs under the same policy: no escape through it
        Interpreter cb = sandbox(policy());
        assertEquals(java.util.List.of(1, 2, 3), cb.eval("""
                dyn l = new java.util.ArrayList(); l.add(3); l.add(1); l.add(2);
                l.sort((a, b) -> a - b); return l;
                """));
        denied(cb, """
                dyn l = new java.util.ArrayList(); l.add(3); l.add(1);
                l.sort((a, b) -> { Runtime.getRuntime(); return 0; }); return l;
                """, "java.lang.Runtime");
    }

    // ------------------------------------------------------------ 4. denial of service

    @Test
    void infiniteLoopInterruption() throws Exception {
        Interpreter jj = sandbox(policy()).cancellable(true);
        JFunction f = jj.compile("dyn n = 0; while (true) { n++; } return n;");
        Thread stopper = new Thread(() -> { try { Thread.sleep(100); } catch (InterruptedException ignored) {} jj.cancel(); });
        long t0 = System.nanoTime();
        stopper.start();
        JmpError e = assertThrows(JmpError.class, () -> f.call(new Object[0]));
        stopper.join();
        assertTrue(e.getMessage().contains("Cancelled"), e.getMessage());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "cancel must land at the next back-edge, not much later");
        // nested loops inside a compiled function are cancelled the same way
        Interpreter jj2 = sandbox(policy()).cancellable(true);
        JFunction g = jj2.compile("int spin() { dyn n = 0; for (;;) { for (int i = 0; i < 1000; i++) n++; } } return spin();");
        new Thread(() -> { try { Thread.sleep(100); } catch (InterruptedException ignored) {} jj2.cancel(); }).start();
        assertTrue(assertThrows(JmpError.class, () -> g.call(new Object[0])).getMessage().contains("Cancelled"));
    }

    @Test
    void tableLimitStopsRunawayGrowth() {
        Interpreter jj = sandbox(Access.parse("""
                Policy.allowPackage("java.lang");
                Policy.maxTableSize(100000);
                """));
        // arrays
        JmpError a = assertThrows(JmpError.class, () -> jj.eval("dyn t = []; while (true) { t.add(\"garbage\"); }"));
        assertTrue(a.getMessage().contains("Table limit exceeded"), a.getMessage());
        // tables with string keys (the dictionary part)
        JmpError t = assertThrows(JmpError.class, () -> jj.eval("dyn t = {}; dyn i = 0; while (true) { t[\"k\" + i] = i; i++; }"));
        assertTrue(t.getMessage().contains("Table limit exceeded"), t.getMessage());
        // exactly the bound is still fine, one more is not
        assertEquals(100000, jj.eval("dyn t = []; for (int i = 0; i < 100000; i++) t.add(i); return t.size();"));
        assertEquals(100000, jj.eval("dyn t = {}; for (int i = 0; i < 100000; i++) t[\"k\" + i] = i; return len(t);"));
        assertThrows(JmpError.class, () -> jj.eval("dyn t = []; for (int i = 0; i <= 100000; i++) t.add(i); return t.size();"));
        // no limit without the line, and no limit without a policy
        Interpreter free = sandbox(policy());
        assertEquals(300000, free.eval("dyn t = []; for (int i = 0; i < 300000; i++) t.add(i); return t.size();"));
        assertEquals(300000, new Interpreter().eval("dyn t = []; for (int i = 0; i < 300000; i++) t.add(i); return t.size();"));
    }

    @Test
    void threadSpawnDeny() {
        Interpreter jj = sandbox(policy());     // java.lang and java.util are allowed - thread spawners inside them are not
        denied(jj, "return new Thread(() -> {});", "java.lang.Thread");
        denied(jj, "return new java.util.Timer();", "java.util.Timer");
        denied(jj, "return java.util.concurrent.Executors.newSingleThreadExecutor();", "java.util.concurrent.Executors");
        denied(jj, "return java.util.concurrent.CompletableFuture.runAsync(() -> {});", "java.util.concurrent.CompletableFuture");
        denied(jj, "return java.util.concurrent.ForkJoinPool.commonPool();", "java.util.concurrent.ForkJoinPool");
        denied(jj, "return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, null, null);", "java.util.concurrent.ThreadPoolExecutor");
        // the rest of java.util.concurrent is ordinary library code and stays open
        assertEquals(1, jj.eval("dyn m = new java.util.concurrent.ConcurrentHashMap(); m.put(\"a\", 1); return m.size();"));
        assertEquals(5, jj.eval("dyn c = new java.util.concurrent.atomic.AtomicInteger(5); return c.get();"));
        // a host that really wants a pool opens it by class, visibly in the policy file
        Interpreter pool = sandbox(policy().allowClass("java.util.concurrent.ForkJoinPool"));
        assertNotNull(pool.eval("return java.util.concurrent.ForkJoinPool.commonPool();"));
    }

    @Test
    void policyIsFrozenAfterTheFirstParseAndMathIsStillPolicedInTier1() {
        // Tier 1 resolves Math.sqrt(x) to a direct call after asking the policy once, so the policy
        // must not change under a loaded script: setting it afterwards is refused
        Interpreter jj = new Interpreter();
        assertEquals(3.0, jj.eval("return Math.sqrt(9.0);"));
        assertThrows(IllegalStateException.class, () -> jj.access(policy()));
        // a policy that closes Math keeps it closed in the compiled loop as well (the direct call is not emitted)
        Interpreter noMath = sandbox(Access.parse("Policy.allowPackage(\"java.util\"); Policy.denyClass(\"java.lang.Math\");"));
        denied(noMath, "dyn s = 0.0; for (dyn i = 0; i < 1000; i++) { s += Math.sqrt(i * 1.0); } return s;", "java.lang.Math");
        // and an allowed Math is computed in primitives inside a guarded region with the same answer
        Interpreter yes = sandbox(policy());
        assertEquals(new Interpreter().eval("dyn e = { vx: 3.0, vy: 4.0 }; dyn s = 0.0; for (dyn i = 0; i < 1000; i++) { s += Math.sqrt(e.vx * e.vx + e.vy * e.vy); } return s;"),
                yes.eval("dyn e = { vx: 3.0, vy: 4.0 }; dyn s = 0.0; for (dyn i = 0; i < 1000; i++) { s += Math.sqrt(e.vx * e.vx + e.vy * e.vy); } return s;"));
    }
}
