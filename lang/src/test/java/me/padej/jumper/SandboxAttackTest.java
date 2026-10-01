package me.padej.jumper;

import me.padej.jumper.hostapi.Dummy;
import me.padej.jumper.hostapi.Entity;
import me.padej.jumper.hostapi.Player;
import me.padej.jumper.hostapi.World;
import me.padej.jumper.hostapi.Zombie;
import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.runtime.Access;
import me.padej.jumper.runtime.JavaClass;
import me.padej.jumper.runtime.JmpCancelled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three attacks a hostile mod script makes on a sandbox (*.jma), each with the variants that
 * would get around a naive guard. Every script runs through the interpreter and through Tier 1
 * (tools/test.sh runs the suite with -Djmp.tier1=force and =0; the functions with loops below are
 * compiled at once and call Java through invokedynamic).
 *
 * <ol>
 *   <li>Reflection escape through {@code getClass()}: a Class that reaches a script becomes a
 *   {@link JavaClass}, and its loader, {@code forName} and reflection are closed whatever the rules.</li>
 *   <li>A forbidden method behind {@code dyn}: the call site is linked against the policy at its first
 *   call and at every new receiver class - {@link SecurityException}, before the method runs.</li>
 *   <li>A hang: {@code cancel()} sets a volatile flag checked on loop back-edges and function entries;
 *   the thread that runs the script (the server's) comes back at once, and the script cannot catch it.</li>
 * </ol>
 */
class SandboxAttackTest {

    private static final String PLAYER = Player.class.getName();
    private static final String ENTITY = Entity.class.getName();

    /** A typical mod policy: the JDK basics and the game API, with setHealth closed. */
    private static Access modPolicy() {
        return Access.parse("""
                Policy.allowPackage("java.lang");
                Policy.allowPackage("java.util");
                Policy.allowClass("%s");
                Policy.allowClass("%s").denyMethod("setHealth");
                Policy.allowClass("%s");
                Policy.allowClass("%s");
                Policy.allowClass("%s");
                """.formatted(PLAYER, ENTITY, Zombie.class.getName(), Dummy.class.getName(), World.class.getName()));
    }

    /**
     * The policy of a host that opened far too much on purpose: java.lang.Class, ClassLoader, Thread,
     * java.net and even the dangerous members by name. The hard rules must still hold.
     */
    private static Access recklessPolicy() {
        return Access.parse("""
                Policy.allowPackage("java.lang");
                Policy.allowPackage("java.util");
                Policy.allowPackage("java.net");
                Policy.allowClass("java.lang.Class");
                Policy.allowClass("java.lang.ClassLoader");
                Policy.allowClass("java.lang.Thread");
                Policy.allowClass("java.net.URLClassLoader");
                Policy.allowMethod("java.lang.Class", "getClassLoader");
                Policy.allowMethod("java.lang.Class", "forName");
                Policy.allowMethod("java.lang.Thread", "getContextClassLoader");
                Policy.allowClass("%s");
                """.formatted(PLAYER));
    }

    private static Interpreter sandbox(Access policy) {
        World world = new World();
        return new Interpreter().access(policy).define("player", new Player()).define("world", world);
    }

    /** The script must end in a SecurityException naming what it reached for - never run past it. */
    private static SecurityException blocked(Interpreter jj, String src, String what) {
        SecurityException e = assertThrows(SecurityException.class, () -> jj.eval(src), src);
        assertTrue(e.getMessage().contains("Access denied") && e.getMessage().contains(what), src + " -> " + e.getMessage());
        return e;
    }

    // ================================================================ 1. getClass(): reflection escape

    @Test
    void getClassIsClosedByDefault() {
        Interpreter jj = sandbox(modPolicy());
        blocked(jj, "return player.getClass();", "getClass");
        blocked(jj, "dyn p = player; return p.getClass().getClassLoader();", "getClass");
        // compiled: the call goes through invokedynamic, linked under the policy
        blocked(jj, """
                dyn probe(dyn o) { dyn c = null; for (int i = 0; i < 3; i++) c = o.getClass(); return c; }
                return probe(player);
                """, "getClass");
        // and a string, a list - any object
        blocked(jj, "return \"s\".getClass().getClassLoader();", "getClass");
    }

    @Test
    void aClassReachingTheScriptIsAJavaClassWithoutLoaderOrForName() {
        Interpreter jj = sandbox(recklessPolicy());
        // getClass() works (Class is allowed) - but what comes back is the script's class handle, not the Class
        Object c = jj.eval("return player.getClass();");
        assertTrue(c instanceof JavaClass, String.valueOf(c));
        assertEquals(Player.class, ((JavaClass) c).cls());
        // who it is: fine
        assertEquals(PLAYER, jj.eval("return player.getClass().getName();"));
        assertEquals("Player", jj.eval("dyn c = player.getClass(); return c.getSimpleName();"));
        assertEquals(true, jj.eval("return player.getClass().isInstance(player);"));
        assertEquals(true, jj.eval("return Object.class.isAssignableFrom(player.getClass());"));   // passed back to Java as a Class
        // the loader and forName: closed, although the policy names them explicitly
        blocked(jj, "return player.getClass().getClassLoader();", "java.lang.Class.getClassLoader");
        blocked(jj, "return player.getClass().getClassLoader().loadClass(\"java.lang.Runtime\");", "java.lang.Class.getClassLoader");
        blocked(jj, "return Thread.currentThread().contextClassLoader;", "getContextClassLoader");       // as a property
        blocked(jj, "return Class.forName(\"java.lang.Runtime\");", "java.lang.Class.forName");
        blocked(jj, "return player.getClass().forName(\"java.lang.Runtime\");", "java.lang.Class.forName");
        blocked(jj, "return String.class.forName(\"java.lang.Runtime\");", "java.lang.Class.forName");
        // and the rest of reflection: no Method / Field / Constructor objects, no module, no resources
        blocked(jj, "return player.getClass().getMethods();", "java.lang.Class.getMethods");
        blocked(jj, "return player.getClass().getDeclaredField(\"health\");", "java.lang.Class.getDeclaredField");
        blocked(jj, "return player.getClass().getModule();", "java.lang.Class.getModule");
        blocked(jj, "return player.getClass().getResourceAsStream(\"/x\");", "java.lang.Class.getResourceAsStream");
        blocked(jj, "return player.getClass().getProtectionDomain();", "java.lang.Class.getProtectionDomain");
        // the same through Tier 1: a compiled loop, the class held in a dyn local
        blocked(jj, """
                dyn loaderOf(dyn o) { dyn l = null; for (int i = 0; i < 3; i++) { dyn c = o.getClass(); l = c.getClassLoader(); } return l; }
                return loaderOf(player);
                """, "getClassLoader");
    }

    @Test
    void noRouteToAClassLoaderAtAll() {
        Interpreter jj = sandbox(recklessPolicy());
        blocked(jj, "return ClassLoader.getSystemClassLoader();", "java.lang.ClassLoader");
        blocked(jj, "return Thread.currentThread().getContextClassLoader();", "getContextClassLoader");
        blocked(jj, "return new java.net.URLClassLoader(new java.net.URL[0]);", "java.net.URLClassLoader");
        blocked(jj, "return java.util.ServiceLoader.load(Runnable.class);", "java.util.ServiceLoader");
        // a Class that came through a Java collection is wrapped all the same
        blocked(jj, "dyn l = new java.util.ArrayList(); l.add(player.getClass()); return l.get(0).getClassLoader();", "getClassLoader");
        // a raw Class stored in a Java array and read back by index is not wrapped - and still closed
        blocked(jj, "dyn a = new Object[1]; a[0] = player.getClass(); return a[0].getClassLoader();", "getClassLoader");
    }

    @Test
    void theScriptCannotCatchOrSwallowAViolation() {
        Interpreter jj = sandbox(recklessPolicy());
        blocked(jj, "try { return player.getClass().getClassLoader(); } catch (e) { return \"caught\"; }", "getClassLoader");
        blocked(jj, "dyn f() { try { return player.getClass().getClassLoader(); } finally { return \"swallowed\"; } } return f();", "getClassLoader");
        blocked(jj, """
                dyn f() { for (int i = 0; i < 3; i++) { try { player.getClass().getClassLoader(); } catch (e) { } } return "survived"; }
                return f();
                """, "getClassLoader");
    }

    // ================================================================ 2. dyn + invokedynamic: the forbidden method

    @Test
    void aForbiddenMethodBehindDynIsDeniedAtLinkage() {
        Interpreter jj = sandbox(modPolicy());
        World world = (World) jj.eval("return world;");
        assertEquals(20, jj.eval("dyn e = world.get(\"entity\"); return e.getHealth();"));
        SecurityException e = blocked(jj, "dyn e = world.get(\"entity\"); e.setHealth(0); return 1;", ENTITY + ".setHealth");
        assertTrue(e.getMessage().contains("line 1"), e.getMessage());
        assertEquals(20, world.entity.getHealth(), "the forbidden method must not have run");
        // property syntax is the same setter
        blocked(jj, "dyn e = world.get(\"entity\"); e.health = 0; return 1;", "setHealth");
        assertEquals(20, jj.eval("dyn e = world.get(\"entity\"); return e.health;"));
        assertEquals(20, world.entity.getHealth());
    }

    @Test
    void aCallSiteLinkedForAnAllowedClassIsRelinkedForTheForbiddenOne() {
        Interpreter jj = sandbox(modPolicy());
        World world = (World) jj.eval("return world;");
        // one call site, compiled at once (a loop): Dummy.setHealth is allowed and links the site first
        String hit = "void hit(dyn o, int h) { for (int i = 0; i < 2; i++) o.setHealth(h); }\n";
        assertNull(jj.eval(hit + "hit(world.get(\"dummy\"), 7); hit(world.get(\"dummy\"), 8);"));
        assertEquals(8, world.dummy.getHealth());
        // the same site, a new receiver class: the guard fails, the site asks the policy again
        blocked(jj, hit + "hit(world.get(\"dummy\"), 9); hit(world.get(\"entity\"), 0);", ENTITY + ".setHealth");
        assertEquals(20, world.entity.getHealth());
        // tables and other classes through the site first - still denied when the entity comes
        blocked(jj, """
                void hit(dyn o) { for (int i = 0; i < 2; i++) o.setHealth(1); }
                hit(world.get("dummy")); hit({ setHealth: (h) -> null }); hit(world.get("dummy"));
                hit({ setHealth: (h) -> null }); hit(world.get("entity"));
                """, "setHealth");
        assertEquals(20, world.entity.getHealth());
        // and the allowed class keeps working through the same site
        assertNull(jj.eval(hit + "hit(world.get(\"dummy\"), 3);"));
        assertEquals(3, world.dummy.getHealth());
    }

    @Test
    void anOverrideOrAnInterfaceImplementationIsTheSameMethod() {
        Interpreter jj = sandbox(modPolicy());
        World world = (World) jj.eval("return world;");
        // Zombie is allowed as a whole and overrides setHealth: the deny on Entity.setHealth still holds
        blocked(jj, "dyn z = world.get(\"zombie\"); z.setHealth(0); return 1;", "setHealth");
        blocked(jj, "dyn z = world.zombie; for (int i = 0; i < 2; i++) z.setHealth(0); return 1;", "setHealth");
        assertEquals(20, world.zombie.getHealth());
        // a rule on an interface reaches every implementation, whatever class declares the body
        Interpreter iface = sandbox(Access.parse("""
                Policy.allowClass("%s");
                Policy.allowClass("%s");
                Policy.allowClass("%s");
                Policy.denyMethod("me.padej.jumper.hostapi.Damageable", "damage");
                """.formatted(ENTITY, Zombie.class.getName(), World.class.getName())));
        World w2 = (World) iface.eval("return world;");
        assertEquals(20, iface.eval("dyn e = world.get(\"entity\"); return e.getHealth();"));
        blocked(iface, "dyn e = world.get(\"entity\"); e.damage(5); return 1;", "damage");
        blocked(iface, "dyn z = world.get(\"zombie\"); for (int i = 0; i < 2; i++) z.damage(5); return 1;", "damage");
        assertEquals(20, w2.entity.getHealth());
        assertEquals(20, w2.zombie.getHealth());
    }

    @Test
    void aDenialAtLinkageCannotBeCaughtInTheScript() {
        Interpreter jj = sandbox(modPolicy());
        World world = (World) jj.eval("return world;");
        blocked(jj, "dyn e = world.get(\"entity\"); try { e.setHealth(0); } catch (x) { return \"caught\"; } return 1;", "setHealth");
        blocked(jj, """
                dyn tryAll(dyn e) { for (int i = 0; i < 3; i++) { try { e.setHealth(0); } catch (x) { } } return "survived"; }
                return tryAll(world.get("entity"));
                """, "setHealth");
        assertEquals(20, world.entity.getHealth());
    }

    // ================================================================ 3. hang: cancel()

    /** Scripts that never end by themselves; each must stop on cancel() within the deadline. */
    private static final String[] HANGS = {
            "while (true) { }",
            "dyn n = 0; while (true) { n++; }",
            "int spin() { dyn n = 0; while (true) { n++; } } return spin();",                  // compiled body
            "do { } while (true);",
            "for (;;) { continue; }",
            "dyn n = 0; for (int i = 0; ; i++) { for (int j = 0; j < 1000; j++) n++; }",
            "for (dyn x : java.util.stream.Stream.iterate(0, (i) -> i + 1).iterator()) { }", // an endless Java iterator
            // the script tries to survive its cancellation
            "while (true) { try { while (true) { } } catch (e) { } }",
            "int f() { try { while (true) { } } finally { return 1; } } while (true) { f(); }",
            // no loop at all: recursion (2^64 calls) and a callback that Java calls forever
            "void f(int d) { if (d < 64) { f(d + 1); f(d + 1); } } f(0);",
            "java.util.stream.Stream.generate(() -> 1).forEach((x) -> { });",
    };

    @Test
    void cancelStopsEveryHangWithoutHangingTheServerThread() throws Exception {
        // the server's thread: scripts run on it one after another; it must come back after each
        // (8 MB: the stack of a main thread; a 512 MB script stack is the engine's - see the next test)
        ExecutorService server = Executors.newSingleThreadExecutor(r -> new Thread(null, r, "server-tick", 8L << 20));
        try {
            for (String src : HANGS) {
                Interpreter jj = sandbox(modPolicy()).cancellable(true);
                Future<Object> run = server.submit(() -> jj.eval(src));
                Thread.sleep(80);
                assertFalse(run.isDone(), "should still be running: " + src);
                long t0 = System.nanoTime();
                jj.cancel();                                                     // from the host's thread
                long cancelNs = System.nanoTime() - t0;
                assertTrue(cancelNs < 5_000_000L, "cancel() itself must not block: " + cancelNs / 1000 + " us");
                java.util.concurrent.ExecutionException ex =
                        assertThrows(java.util.concurrent.ExecutionException.class, () -> run.get(2, TimeUnit.SECONDS), src);
                assertTrue(ex.getCause() instanceof JmpCancelled, src + " -> " + ex.getCause());
                // the server thread is free: the next task runs on it at once
                assertEquals("tick", server.submit(() -> "tick").get(1, TimeUnit.SECONDS), src);
            }
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void cancelThroughTheScriptEngineReleasesTheServerThreadAtOnce() throws Exception {
        // the engine runs a script on its own 512 MB stack; the server thread waits for it - and must be
        // released by cancel() even when the script's thread still has a deep recursion to unwind
        // (the second one is a catch-and-recurse storm; in a sandbox it ends by itself - see the next test -
        // so it runs here without a policy, where a stack overflow is an ordinary catchable error)
        for (String src : new String[]{"dyn n = 0; while (true) { n++; }", "void f() { try { f(); } catch (e) { f(); } } f();"}) {
            me.padej.jumper.script.JmpScriptEngine engine = (me.padej.jumper.script.JmpScriptEngine)
                    new me.padej.jumper.script.JmpScriptEngineFactory().getScriptEngine();
            if (!src.contains("catch")) engine.access(modPolicy());
            engine.cancellable(true);
            Throwable[] out = new Throwable[1];
            Thread serverThread = new Thread(() -> {
                try {
                    engine.eval(src);
                } catch (Throwable e) {
                    out[0] = e;
                }
            }, "server-tick");
            serverThread.start();
            Thread.sleep(150);
            assertTrue(serverThread.isAlive(), src);
            long t0 = System.nanoTime();
            engine.cancel();
            serverThread.join(1000);
            assertFalse(serverThread.isAlive(), "the server thread must come back after cancel(): " + src);
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000, src);
            assertTrue(out[0] instanceof javax.script.ScriptException && out[0].getCause() instanceof JmpCancelled,
                    src + " -> " + out[0]);
        }
    }

    @Test
    void inASandboxAStackOverflowCannotBeCaughtAndRecursedFrom() throws Exception {
        // try { f(); } catch (e) { f(); } - every level catches the overflow of the one below and goes down
        // again: 2^depth calls, a thread that never comes back. In a sandbox the overflow is fatal.
        ExecutorService server = Executors.newSingleThreadExecutor(r -> new Thread(null, r, "server-tick", 8L << 20));
        try {
            for (String src : new String[]{
                    "void f() { try { f(); } catch (e) { f(); } } f();",
                    "void f() { try { f(); } finally { f(); } } f();",
                    "int depth = 0; void f() { depth++; f(); } try { f(); } catch (e) { return \"caught\"; }"}) {
                Interpreter jj = sandbox(modPolicy());
                Future<Object> run = server.submit(() -> jj.eval(src));
                java.util.concurrent.ExecutionException ex =
                        assertThrows(java.util.concurrent.ExecutionException.class, () -> run.get(10, TimeUnit.SECONDS), src);
                assertTrue(ex.getCause() instanceof me.padej.jumper.runtime.JmpStackOverflow, src + " -> " + ex.getCause());
            }
        } finally {
            server.shutdownNow();
        }
        // without a policy it stays an ordinary error a script may handle
        assertEquals("caught", new Interpreter().eval("void f() { f(); } try { f(); } catch (e) { return \"caught\"; }"));
    }

    @Test
    void withoutCancellableTheLoopsCarryNoCheck() {
        // cancel() on an interpreter that was not made cancellable does nothing - the flag is not compiled in
        Interpreter jj = sandbox(modPolicy());
        jj.cancel();
        assertEquals(1000, jj.eval("dyn n = 0; for (int i = 0; i < 1000; i++) n++; return n;"));
    }
}
