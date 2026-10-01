package me.padej.jumper.jit;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.interp.Frame;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.Set;

/**
 * Tier 1: compilation of a script function into an in-memory JVM class. Classes are defined by the
 * interpreter's {@link ScriptLoader} (a ClassLoader of its own, so they unload with it) - no files on disk.
 * Any compilation error -> the function stays in the interpreter (Tier 0) forever.
 *
 * JMP_TIER1=0 (or off / false) - disable; JMP_TIER1=force - compile everything at once; JMP_DEBUG=1 - print the reasons for refusal
 * (the same via -Djmp.tier1=... and -Djmp.debug; a property wins over the variable, 0/off/false mean off); JMP_DUMP=dir - save class files for javap.
 */
public final class Jit {
    private Jit() {}

    private static final String MODE = System.getProperty("jmp.tier1", System.getenv().getOrDefault("JMP_TIER1", "on"));
    // The value is read directly (there is also the force mode here, which the others lack), but the
    // optimization itself is declared in runtime/Opts - the correctness gate and benchAB take it from there.
    public static final boolean ENABLED = me.padej.jumper.runtime.Opts.on("tier1") && !me.padej.jumper.runtime.Opts.offValue(MODE);
    /** JMP_TIER1=force (or -Djmp.tier1=force) - compile everything on the first call (for Tier 1 tests). */
    public static final boolean FORCE = "force".equals(MODE);
    static final boolean DEBUG = me.padej.jumper.runtime.Opts.flag("jmp.debug", "JMP_DEBUG");
    static final String PKG = "me/padej/jumper/jit/";

    /** JMP_DUMP=dir - save the class file for javap. Shared by function classes and instance classes. */
    static void dump(String internalName, byte[] bytes) {
        String dir = System.getProperty("jmp.dump", System.getenv("JMP_DUMP"));
        if (dir == null) return;
        try {
            java.nio.file.Path d = java.nio.file.Path.of(dir);
            java.nio.file.Files.createDirectories(d);
            java.nio.file.Files.write(d.resolve(internalName.substring(PKG.length()) + ".class"), bytes);
        } catch (java.io.IOException e) {
            if (DEBUG) System.err.println("[jit] dump failed: " + e);
        }
    }
    static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** Loader for the generated class: the interpreter's, or the shared one. */
    static ScriptLoader loaderOf(ScriptLoader l) {
        return l != null ? l : ScriptLoader.DEFAULT;
    }
    private static int seq;
    private static final Set<FunctionNode> IN_PROGRESS = new HashSet<>();

    /** Internal name of the function class (assigned once, before generation - for mutual direct calls). */
    static synchronized String className(FunctionNode fn) {
        if (fn.jitClassName == null) {
            StringBuilder sb = new StringBuilder(PKG).append("Fn").append(seq++).append('_');
            for (char ch : fn.name.toCharArray()) sb.append(Character.isJavaIdentifierPart(ch) ? ch : '_');
            fn.jitClassName = sb.toString();
        }
        return fn.jitClassName;
    }

    /** Whether this function is being compiled right now (mutual recursion). */
    static synchronized boolean inProgress(FunctionNode fn) {
        return IN_PROGRESS.contains(fn);
    }

    public static synchronized void compile(FunctionNode fn) {
        if (fn.jitClass != null || fn.jitFailed || IN_PROGRESS.contains(fn)) return;
        IN_PROGRESS.add(fn);
        try {
            className(fn);
            Compiler c = new Compiler(fn);
            byte[] bytes = c.generate();
            dump(fn.jitClassName, bytes);
            Class<?> cls = loaderOf(fn.loader).define(fn.jitClassName, bytes);
            fn.jitConsts = c.consts.toArray();
            // The constants live in a static field of the class, not in an instance field: otherwise the body
            // could not be static (konst() would have to read them through this).
            // Set before fn.jitClass is published - that is how the others learn the class is ready.
            LOOKUP.findStaticVarHandle(cls, "K", Object[].class).set(fn.jitConsts);
            fn.jitCtor = cls.getDeclaredConstructor(FunctionNode.class, Frame.class, Object[].class);
            fn.jitClass = cls;
            if (DEBUG) System.err.println("[jit] compiled " + fn.name + " -> " + cls.getName() + " (" + bytes.length + " bytes)");
        } catch (Throwable t) {
            fn.jitFailed = true;
            if (DEBUG) {
                System.err.println("[jit] failed " + fn.name + ": " + t);
                t.printStackTrace();
            }
        } finally {
            IN_PROGRESS.remove(fn);
        }
    }

    /**
     * Compile the function and everything nested in it (lambdas, class methods, local functions)
     * right away, without waiting for the first calls. The usual path is lazy: Tier 1 kicks in on
     * the {@code JIT_THRESHOLD}-th call or on the first entry into a function with a loop. This method is needed
     * where the whole "source -> bytecode" cost matters: measuring compilation speed
     * (bench.harness.CompileBench) and, in the future, a script precompilation mode.
     *
     * @return how many functions got a class (0 if Tier 1 is disabled)
     */
    public static int compileAll(FunctionNode root) {
        if (!ENABLED) return 0;
        return compileAll(root, 0);
    }

    private static int compileAll(Object node, int depth) {
        if (node == null || depth > 256) return 0;
        int n = 0;
        if (node instanceof FunctionNode fn) {
            compile(fn);
            if (fn.jitClass != null) n++;
            for (Object child : Captures.children(fn)) n += compileAll(child, depth + 1);
            return n;
        }
        for (Object child : Captures.children(node)) n += compileAll(child, depth + 1);
        return n;
    }
}
