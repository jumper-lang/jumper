package me.padej.jumper.security;

import me.padej.jumper.runtime.Access;

/**
 * Vocabulary of the {@code *.jma} policy file (JuMper Access). A policy file is an ordinary Jumper
 * script in which the single name {@code Policy} is available; no format of its own, no parser of
 * its own - the language is the config language for the JVM:
 *
 * <pre>
 * // mods.jma
 * Policy.allowPackage("net.server.api");        // code that is safe to call
 * Policy.allowClass("net.server.world.Entity")
 *       .denyMethod("setHealth");
 * Policy.allowPackage("java.util");
 * Policy.allowPackage("java.lang");             // String, Math... - but not Class/Runtime/System/Thread
 * Policy.allowMethod("java.lang.System", "currentTimeMillis");
 * Policy.allowModules();                        // import "file.jmp"; closed under a policy without this
 * </pre>
 *
 * <p>The policy file itself runs under a policy that allows only {@code Policy}: it cannot reach
 * a single Java class. It lives where the host put it ({@link Access#load}), and protecting it
 * with filesystem permissions is the host's job: whoever can write {@code mods.jma} has everything.
 */
public final class Policy {
    private Policy() {}

    /** The policy currently being built (while the file executes). */
    static final ThreadLocal<Access> BUILDING = new ThreadLocal<>();

    private static Access a() {
        Access a = BUILDING.get();
        if (a == null) throw new IllegalStateException("Policy may only be called from a policy file (*.jma)");
        return a;
    }

    /** The whole package, except sandbox escape routes (those only via allowClass by name). */
    public static void allowPackage(String pkg) { a().allowPackage(pkg); }
    public static void denyPackage(String pkg) { a().denyPackage(pkg); }

    /** The whole class; individual members can be closed on the result. */
    public static ClassRule allowClass(String name) { a().allowClass(name); return new ClassRule(name); }
    /** The class is closed entirely (swallows members), even if its package is allowed. */
    public static ClassRule denyClass(String name) { a().denyClass(name); return new ClassRule(name); }

    /** One method of a class; the class itself becomes visible by name, everything else in it stays closed. */
    public static void allowMethod(String cls, String name) { a().allowMember(cls, name); }
    public static void denyMethod(String cls, String name) { a().denyMember(cls, name); }
    public static void allowField(String cls, String name) { a().allowMember(cls, name); }
    public static void denyField(String cls, String name) { a().denyMember(cls, name); }

    /** Allow `import "file.jmp"` (modules are closed under a policy by default). */
    public static void allowModules() { a().allowModules(true); }

    /** Upper bound on elements per script table/array (see {@link Access#maxTableSize(int)}). */
    public static void maxTableSize(int n) { a().maxTableSize(n); }

    /** Member rules for the class just mentioned - for the `.denyMethod(...)` chain. */
    public static final class ClassRule {
        private final String cls;

        ClassRule(String cls) { this.cls = cls; }

        public ClassRule allowMethod(String name) { a().allowMember(cls, name); return this; }
        public ClassRule denyMethod(String name) { a().denyMember(cls, name); return this; }
        public ClassRule allowField(String name) { a().allowMember(cls, name); return this; }
        public ClassRule denyField(String name) { a().denyMember(cls, name); return this; }

        @Override
        public String toString() { return "Policy(" + cls + ")"; }
    }

    /**
     * The interpreter a *.jma file runs in: it sees only Policy and ClassRule. Also what a checker parses a
     * policy file with ({@code jmp --check scripts.jma}), so the check and the real load see the same names.
     */
    public static me.padej.jumper.interp.Interpreter interpreter() {
        Access self = Access.none().allowClass(Policy.class).allowClass(ClassRule.class);
        me.padej.jumper.interp.Interpreter jj = new me.padej.jumper.interp.Interpreter().access(self);
        jj.define("Policy", new me.padej.jumper.runtime.JavaClass(Policy.class));
        return jj;
    }

    /** Build a policy from the text of a *.jma file. Used by {@link Access#load}. */
    public static Access build(String source, String fileName) {
        Access built = Access.none();
        Access prev = BUILDING.get();
        BUILDING.set(built);
        try {
            me.padej.jumper.interp.Interpreter jj = interpreter();
            jj.eval(source);
        } catch (me.padej.jumper.parser.ParseError | me.padej.jumper.runtime.JmpError | me.padej.jumper.runtime.ScriptSecurityException e) {
            throw new IllegalArgumentException("access policy " + fileName + ": " + e.getMessage(), e);
        } finally {
            if (prev == null) BUILDING.remove(); else BUILDING.set(prev);
        }
        return built;
    }
}
