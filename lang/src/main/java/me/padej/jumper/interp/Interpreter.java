package me.padej.jumper.interp;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.parser.Parser;
import me.padej.jumper.runtime.Builtins;
import me.padej.jumper.runtime.JFunction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point: source -> execution. No build step: parsing and running is one call.
 * An instance holds its own global functions (Java can add more via define()).
 */
public final class Interpreter {
    private final Map<String, Object> globals = new HashMap<>(Builtins.globals());
    private final Modules modules = new Modules(globals);

    /** Modules loaded by this interpreter (one module instance per interpreter). */
    public Modules modules() {
        return modules;
    }

    /** Registers a Java object or function as a global script constant. */
    public Interpreter define(String name, Object value) {
        globals.put(name, value);
        return this;
    }

    public Interpreter define(String name, JFunction fn) {
        globals.put(name, fn);
        return this;
    }

    /**
     * Policy for script access to Java (see {@link me.padej.jumper.runtime.Access}). None by
     * default, the script sees everything. Set before the first parse: import and Foo.class are checked there already.
     */
    public Interpreter access(me.padej.jumper.runtime.Access a) {
        // The policy is part of what the parser and Tier 1 bake into a script: `import` and `Foo.class`
        // are checked at parse time, and the compiler resolves `Math.sqrt(x)` to a direct call after
        // asking the policy once. So it cannot change under already loaded scripts.
        if (parsed) throw new IllegalStateException("the access policy must be set before the first script is loaded");
        if (a != null && a != me.padej.jumper.runtime.Access.ALL) prepareFatal();
        modules.access(a);
        return this;
    }

    /** Set by the first parse: from then on the policy is frozen (see {@link #access(me.padej.jumper.runtime.Access)}). */
    private boolean parsed;

    public me.padej.jumper.runtime.Access access() {
        return modules.access();
    }

    /**
     * Enable cancellation: afterwards {@link #cancel()} stops any script loop with a
     * "Cancelled" error. Enable before parsing - the check is compiled into the loops.
     */
    public Interpreter cancellable(boolean on) {
        if (on) prepareFatal();
        modules.loader().cancellable = on;
        return this;
    }

    /**
     * Load, initialize and link everything the fatal paths touch (cancellation, a policy violation, a
     * stack overflow in a sandbox), now: they land at the bottom of a recursion that has no stack left,
     * where loading or initializing a class is itself a StackOverflowError - retried at every level on
     * the way up. With the classes ready, throwing the preallocated exceptions there costs no stack.
     */
    static void prepareFatal() {
        if (cancelPrepared) return;
        cancelPrepared = true;
        // Also the profile: the handlers of compiled scripts call Compiler.caught / throwIfFatal, and C2
        // inlines them with the branches their profile saw. A branch never seen is an uncommon trap - and a
        // cancellation that unwinds a deep recursion would deoptimize every frame on its way (30 us each:
        // seconds for a script thread's 512 MB stack). Seen here both ways, the rethrow is compiled code.
        Throwable fatal = me.padej.jumper.runtime.Ops.cancelled();
        me.padej.jumper.runtime.Ops.fatal(me.padej.jumper.runtime.JmpStackOverflow.INSTANCE);
        me.padej.jumper.runtime.Ops.fatal(new StackOverflowError());
        Throwable plain = new me.padej.jumper.runtime.JmpError("x");
        me.padej.jumper.runtime.Ops.fatal(new me.padej.jumper.runtime.ScriptSecurityException(""));
        for (int i = 0; i < 20_000; i++) {
            Throwable t = (i & 1) == 0 ? fatal : plain;
            try {
                me.padej.jumper.jit.Compiler.caught(t);
            } catch (me.padej.jumper.runtime.JmpCancelled expected) {
                // rethrown, as in a script's catch
            }
            try {
                me.padej.jumper.jit.Compiler.throwIfFatal(t);
            } catch (me.padej.jumper.runtime.JmpCancelled expected) {
                // rethrown, as in a script's finally
            }
        }
    }

    private static volatile boolean cancelPrepared;

    /** Cancel the running script (from another thread). Effective only if {@link #cancellable} is on. */
    public void cancel() {
        modules.loader().cancel();
    }

    /** Whether {@link #cancel()} was called. */
    public boolean cancelled() {
        return modules.loader().cancelled;
    }

    /** Take the cancel back: scripts of this interpreter may run again (a cancel stays in effect until then). */
    public void clearCancel() {
        modules.loader().clearCancel();
    }

    /** Parses a script into a callable function (may be called repeatedly without re-parsing). */
    public JFunction compile(String source) {
        return compile(source, null);
    }

    /** Script from file (relative `import "..."` resolve against its directory). */
    public JFunction compile(String source, Path file) {
        Path dir = file == null ? null : file.toAbsolutePath().getParent();
        FunctionNode main = parse(() -> new Parser(source, globals).source(dir, modules).parseProgram());
        return new FunctionNode.ScriptFunction(main, null);
    }

    /**
     * Checks a script without running it: a tolerant parse under this interpreter's policy, every error
     * of the file (syntax, static types, policy at parse time: import / new / Foo.class). A `.jmc` file is
     * checked as a config. Nothing runs: neither the script nor the modules it imports - `import "module.jmp"`
     * only parses the module (its functions and classes are known from their declarations; see
     * {@link Modules#checking}), and an error in it is reported on the import.
     */
    public me.padej.jumper.parser.Parser.Result check(String source, Path file) {
        Path dir = file == null ? null : file.toAbsolutePath().getParent();
        boolean config = file != null && file.getFileName().toString().endsWith(".jmc");
        me.padej.jumper.parser.Parser.Result[] out = new me.padej.jumper.parser.Parser.Result[1];
        parse(() -> {
            Parser ps = Parser.tolerant(source, globals).source(dir, modules);
            if (config) ps.config();
            // imported modules are parsed, not run: checking never executes anything
            out[0] = modules.checking(ps::parseTolerant);
            return null;
        });
        return out[0];
    }

    /** Script that keeps its top level (variables are accessible after run()). */
    public Script script(String source) {
        return new Script(parse(() -> new Parser(source, globals).source(null, modules).parseProgram()));
    }

    /** Parse under the interpreter's policy: import and Foo.class are checked at this stage already. */
    public FunctionNode parse(java.util.function.Supplier<FunctionNode> parser) {
        parsed = true;
        me.padej.jumper.runtime.Access a = modules.access();
        if (a == null) return parser.get();
        me.padej.jumper.runtime.Access prev = me.padej.jumper.runtime.Access.enter(a);
        try {
            return parser.get();
        } finally {
            me.padej.jumper.runtime.Access.exit(prev);
        }
    }

    public Map<String, Object> globals() {
        return globals;
    }

    public Object eval(String source) {
        return compile(source).call(new Object[0]);
    }

    public Object evalFile(Path path) throws IOException {
        return compile(Files.readString(path, StandardCharsets.UTF_8), path).call(new Object[0]);
    }

    /** Thread stack size for scripts: deep recursion in Tier 0 costs several Java frames per call. */
    public static final long SCRIPT_STACK_BYTES = 512L * 1024 * 1024;

    /**
     * Runs a task on a separate thread with a big stack (memory is reserved lazily,
     * so this is almost free). Used by the CLI and the tests.
     */
    public static <T> T runWithBigStack(java.util.concurrent.Callable<T> task) {
        return runWithBigStack(task, null);
    }

    /**
     * The same, and the calling thread (a server's) is released as soon as {@code cancelled} says so,
     * with the cancellation: the script's thread unwinds its stack in the background. A cancel lands on
     * the next back-edge at once, but unwinding a deep recursion on a 512 MB stack through compiled
     * frames that deoptimize one by one can take seconds - that must not be the host's seconds.
     */
    public static <T> T runWithBigStack(java.util.concurrent.Callable<T> task, java.util.function.BooleanSupplier cancelled) {
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                result[0] = task.call();
            } catch (Throwable e) {
                error[0] = e;
            }
        }, "jmp-main", SCRIPT_STACK_BYTES);
        t.setDaemon(true);   // an abandoned, still unwinding script thread must not keep the JVM alive
        t.start();
        try {
            if (cancelled == null) t.join();
            else {
                while (t.isAlive()) {
                    t.join(10);
                    if (t.isAlive() && cancelled.getAsBoolean()) {
                        t.join(50);   // the usual case: already out
                        if (t.isAlive()) throw me.padej.jumper.runtime.Ops.cancelled();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (error[0] != null) {
            if (error[0] instanceof RuntimeException re) throw re;
            if (error[0] instanceof Error er) throw er;
            throw new RuntimeException(error[0]);
        }
        @SuppressWarnings("unchecked") T r = (T) result[0];
        return r;
    }
}
