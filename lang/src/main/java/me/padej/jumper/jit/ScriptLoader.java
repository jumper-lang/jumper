package me.padej.jumper.jit;

/**
 * Class loader of one interpreter: all classes that Tier 1 generates for its scripts are
 * defined in it - functions ({@code Fn*}) and class instances ({@code Inst*}).
 *
 * <p>Previously everything was defined through {@code MethodHandles.Lookup.defineClass} in the loader of
 * Jumper itself, and a class lived until the end of the process: every repeated {@code eval} of the same text left
 * ~14 classes behind forever. A host that reloads configs or mods in a long-lived process
 * hit this before it hit speed. Now a class belongs to the interpreter's loader and
 * is unloaded together with it as soon as no references to the interpreter (the engine, its cells, parse nodes)
 * remain.
 *
 * <p>Why not {@code defineHiddenClass}: a hidden class cannot be named from foreign
 * bytecode, and the generated code refers to its neighbours exactly that way - {@code invokestatic
 * Fn3_step.body} between functions and {@code Inst_Leaf extends Inst_Mid} between instances.
 * Within one loader the names resolve as usual.
 *
 * <p>The classes go into the package {@code me.padej.jumper.jit}, but it is a different runtime package (a different
 * loader), so everything they touch must be public members of public classes.
 */
public final class ScriptLoader extends ClassLoader {
    static { registerAsParallelCapable(); }

    /** For parses without an interpreter (a bare {@code new Parser(src)}): shared, lives until the end of the process. */
    public static final ScriptLoader DEFAULT = new ScriptLoader();

    /** Access policy of the owning interpreter (null - everything allowed); set on script entry. */
    public volatile me.padej.jumper.runtime.Access access;
    /**
     * Cancellation request: checked on loop back edges, but only if the host enabled
     * {@link #cancellable} before parsing - otherwise there is not a single extra instruction in the loop.
     */
    public volatile boolean cancelled;
    public volatile boolean cancellable;
    /** Set together with {@link #cancelled}: what compiled code throws (see Compiler.cancelCheck). */
    public volatile me.padej.jumper.runtime.JmpError cancelError;

    /** Stop every script of this loader at its next loop back-edge or function entry (any thread). */
    public void cancel() {
        cancelError = me.padej.jumper.runtime.Ops.cancelled();
        cancelled = true;
    }

    /** Take a cancel back (e.g. it came just as the call finished): scripts of this loader may run again. */
    public void clearCancel() {
        cancelled = false;
        cancelError = null;
    }

    public ScriptLoader() {
        super(LayoutLoader.INSTANCE);   // layout classes of literal shapes are shared by all interpreters
    }

    /** Define a class by internal name ({@code me/padej/jumper/jit/Fn0_f}). */
    Class<?> define(String internalName, byte[] bytes) {
        return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
    }
}
