package me.padej.jumper.script;

import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.parser.Parser;
import me.padej.jumper.parser.ParseError;
import me.padej.jumper.runtime.Builtins;
import me.padej.jumper.runtime.JFunction;
import me.padej.jumper.runtime.JmpError;
import me.padej.jumper.runtime.JTable;

import javax.script.*;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;

/**
 * javax.script for Jumper.
 *
 * Bindings (engine + global scope) are visible to the script as global names (constants as of eval);
 * top-level variables declared by the script land in the ENGINE_SCOPE bindings after eval,
 * so engine.get("x") and subsequent evals see them. Script functions can be called via
 * Invocable.invokeFunction, and any Java interface implemented by a table/class via getInterface.
 */
public final class JmpScriptEngine extends AbstractScriptEngine implements Compilable, Invocable {
    private final JmpScriptEngineFactory factory;

    JmpScriptEngine(JmpScriptEngineFactory factory) {
        super(new CellBindings());
        this.factory = factory;
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    @Override
    public Bindings createBindings() {
        return new CellBindings();
    }

    // ---------- eval ----------

    @Override
    public Object eval(String script, ScriptContext ctx) throws ScriptException {
        return run(compileScript(script, ctx), ctx);
    }

    @Override
    public Object eval(Reader reader, ScriptContext ctx) throws ScriptException {
        try {
            return eval(readAll(reader), ctx);
        } catch (IOException e) {
            throw new ScriptException(e);
        }
    }

    /**
     * Script constants: builtin functions, GLOBAL_SCOPE, print/println into the context writer.
     * ENGINE_SCOPE holds the mutable variables: top-level declarations write straight into the bindings,
     * assignments from closures and later evals are visible via engine.get().
     */
    /** Access policy for all scripts of this engine (null = everything allowed). */
    private volatile me.padej.jumper.runtime.Access access;
    private volatile boolean cancellable;
    /**
     * The loaders of this engine's interpreters, weakly: a script that runs keeps its loader reachable
     * (its code holds it), a finished one lets it go - an engine that evaluates on every event does not
     * accumulate them.
     */
    private final java.util.Set<me.padej.jumper.jit.ScriptLoader> live =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** Set the access policy (see {@link me.padej.jumper.runtime.Access}); applies to evals after the call. */
    public JmpScriptEngine access(me.padej.jumper.runtime.Access a) {
        this.access = a;
        return this;
    }

    /** Enable {@link #cancel()}: the check is compiled into loops of scripts parsed after the call. */
    public JmpScriptEngine cancellable(boolean on) {
        this.cancellable = on;
        return this;
    }

    /** Cancel whatever this engine is executing right now (from another thread). */
    public void cancel() {
        me.padej.jumper.jit.ScriptLoader[] ls;
        synchronized (live) {
            ls = live.toArray(new me.padej.jumper.jit.ScriptLoader[0]);
        }
        for (me.padej.jumper.jit.ScriptLoader l : ls) l.cancel();
    }

    private Interpreter interpreter(ScriptContext ctx) {
        Interpreter jj = new Interpreter();
        if (access != null) jj.access(access);
        if (cancellable) { jj.cancellable(true); live.add(jj.modules().loader()); }
        Bindings global = ctx.getBindings(ScriptContext.GLOBAL_SCOPE);
        if (global != null) for (Map.Entry<String, Object> e : global.entrySet()) jj.define(e.getKey(), e.getValue());
        Object out = ctx.getWriter();
        if (out != null) jj.define("println", printer(ctx, true)).define("print", printer(ctx, false));
        return jj;
    }

    private static JFunction printer(ScriptContext ctx, boolean newline) {
        return JFunction.of(newline ? "println" : "print", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(me.padej.jumper.runtime.Ops.str(args[i]));
            }
            if (newline) sb.append(System.lineSeparator());
            try {
                ctx.getWriter().write(sb.toString());
                ctx.getWriter().flush();
            } catch (IOException e) {
                throw new JmpError("write failed: " + e.getMessage(), e);
            }
            return null;
        });
    }

    private JFunction compileScript(String src, ScriptContext ctx) throws ScriptException {
        try {
            Interpreter jj = interpreter(ctx);
            Bindings vars = ctx.getBindings(ScriptContext.ENGINE_SCOPE);
            return new FunctionNode.ScriptFunction(jj.parse(() -> new Parser(src, jj.globals()).repl(vars).source(null, jj.modules()).parseProgram()), null);
        } catch (ParseError e) {
            throw new ScriptException(e.getMessage(), (String) get(ScriptEngine.FILENAME), e.line, e.col);
        } catch (JmpError e) {   // the access policy already fires at parse time (import, Foo.class)
            throw wrap(e);
        }
    }

    private Object run(JFunction s, ScriptContext ctx) throws ScriptException {
        try {
            me.padej.jumper.jit.ScriptLoader l = s instanceof FunctionNode.ScriptFunction sf ? sf.node.loader : null;
            // the caller (a server thread) comes back at once on cancel(), whatever the script's thread is doing
            return Interpreter.runWithBigStack(() -> s.call(new Object[0]), l == null ? null : () -> l.cancelled);
        } catch (JmpError e) {
            ScriptException se = new ScriptException(e.message(), (String) get(ScriptEngine.FILENAME), e.line());
            se.initCause(e);
            throw se;
        }
    }

    // ---------- Compilable ----------

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        JFunction s = compileScript(script, getContext()); // variables are the context bindings as of compile
        return new CompiledScript() {
            @Override
            public Object eval(ScriptContext ctx) throws ScriptException {
                return run(s, ctx);
            }

            @Override
            public ScriptEngine getEngine() {
                return JmpScriptEngine.this;
            }
        };
    }

    @Override
    public CompiledScript compile(Reader reader) throws ScriptException {
        try {
            return compile(readAll(reader));
        } catch (IOException e) {
            throw new ScriptException(e);
        }
    }

    // ---------- Invocable ----------

    @Override
    public Object invokeFunction(String name, Object... args) throws ScriptException, NoSuchMethodException {
        Object f = get(name);
        if (!(f instanceof JFunction jf)) throw new NoSuchMethodException(name);
        try {
            return jf.call(args == null ? new Object[0] : args);
        } catch (JmpError e) {
            throw wrap(e);
        }
    }

    @Override
    public Object invokeMethod(Object thiz, String name, Object... args) throws ScriptException, NoSuchMethodException {
        Object[] a = args == null ? new Object[0] : args;
        me.padej.jumper.runtime.Access prev = me.padej.jumper.runtime.Access.enter(access);
        try {
            if (thiz instanceof JTable t) return me.padej.jumper.ast.Exprs.MethodCall.invokeDynamic(t, name, new me.padej.jumper.runtime.FieldCache(name), a);
            return me.padej.jumper.runtime.Interop.invoke(thiz, name, a);
        } catch (JmpError e) {
            if (e.getMessage().contains("No method") || e.getMessage().contains("Attempt to call")) throw new NoSuchMethodException(name);
            throw wrap(e);
        } finally {
            if (access != null) me.padej.jumper.runtime.Access.exit(prev);
        }
    }

    @Override
    public <T> T getInterface(Class<T> clasz) {
        return getInterface(JTable.plain(), clasz);
    }

    /** Table/class instance as an interface implementation: methods are looked up by name among function fields and methods. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getInterface(Object thiz, Class<T> clasz) {
        if (clasz == null || !clasz.isInterface()) throw new IllegalArgumentException("interface expected");
        Object target = thiz;
        return (T) java.lang.reflect.Proxy.newProxyInstance(clasz.getClassLoader(), new Class<?>[]{clasz}, (proxy, method, margs) -> {
            if (method.getDeclaringClass() == Object.class) return method.getName().equals("toString") ? "jmp proxy" : method.invoke(this, margs);
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, margs);
            Object[] a = margs == null ? new Object[0] : margs;
            Object fn = target instanceof JTable t ? me.padej.jumper.runtime.Ops.member(t, method.getName()) : get(method.getName());
            if (!(fn instanceof JFunction jf)) throw new UnsupportedOperationException("script does not define " + method.getName());
            Object r = jf.call(a);
            Class<?> rt = method.getReturnType();
            return rt == void.class ? null : me.padej.jumper.runtime.Interop.convert(rt, r);
        });
    }

    private static String readAll(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    private static ScriptException wrap(JmpError e) {
        ScriptException se = new ScriptException(e.message(), null, e.line());
        se.initCause(e);
        return se;
    }
}
