package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;
import me.padej.jumper.jit.CompiledFunction;
import me.padej.jumper.jit.Jit;
import me.padej.jumper.runtime.JFunction;

import java.util.ArrayList;
import java.util.List;

/** Function description: parameters, slot count, body. The function value is created in ScriptFunction. */
public final class FunctionNode {
    public final String name;
    public final VarType[] paramTypes;
    /** Parameter index: in p[] for primitive types, in slots[] for the rest. */
    public final int[] paramIndex;
    public final int nparams;
    public VarType returnType = VarType.DYN;
    /** Return type is a script class (`Vec make()`): an annotation for the caller, returnType stays DYN. */
    public Classes.ClassNode returnClass;
    public int nslots, nprims;
    /** Type of each primitive p[] index (filled by the parser). */
    public final List<VarType> primTypes = new ArrayList<>();
    public boolean hasLoop;
    /** Keep all variables in the Frame and retain it after execution (top-level scripts for ScriptEngine/REPL). */
    public boolean forceFrame;
    /** Top-level names -> [slot, prim?1:0, VarType.ordinal] (filled by the parser for the main function of a script). */
    public java.util.Map<String, int[]> topLevel;
    public Stmt body;
    public final int line;

    // ---- Tier 1 ----
    /** Threshold: functions with loops are compiled on the first call, the rest on the third. */
    public static final int JIT_THRESHOLD = 3;
    public int calls;
    public volatile Class<?> jitClass;
    public volatile boolean jitFailed;
    public Object[] jitConsts;
    public java.lang.reflect.Constructor<?> jitCtor;
    /** Internal name of the JVM class, assigned on the first compilation attempt (for direct calls between functions). */
    public String jitClassName;
    /**
     * Tier 1 decision: the function body is compiled as a static method (it needs neither a frame,
     * nor a closure, nor constants through this). Then the caller does not need a
     * CompiledFunction instance - just an invokestatic, which C2 inlines like an ordinary method.
     * Set by the compiler before generation, read by callers.
     */
    public volatile boolean jitStaticBody;
    /**
     * Int entry point (jit/Compiler.decideSpec): which `dyn` parameters the static method {@code bodyI}
     * takes as {@code int}, or null when there is none. Decided before the body is generated, so that
     * self-calls in both bodies can already target it.
     */
    public boolean[] specInt;
    /** Return type of {@code bodyI}: INT when every return is provably an int under the int parameters, else as {@link #returnType}. */
    public VarType specReturn;
    /** Descriptor of {@code bodyI}. */
    public String specDesc;
    /** Interpreter loader in which the function class is defined (null - the shared one). Set by the parser. */
    public me.padej.jumper.jit.ScriptLoader loader;
    /** Child nodes for jit traversals (the body), computed once. */
    public Object[] kids;
    /** Bridges to Java functional interfaces (interface -> bridge constructor), see jit/BridgeGen. Lazy. */
    public java.util.Map<Class<?>, java.lang.reflect.Constructor<?>> bridges;

    public FunctionNode(String name, VarType[] paramTypes, int line) {
        this.name = name;
        this.paramTypes = paramTypes;
        this.paramIndex = new int[paramTypes.length];
        this.nparams = paramTypes.length;
        this.line = line;
    }

    /** Closure: a function plus the frame it was created in. */
    public static final class ScriptFunction implements JFunction {
        public final FunctionNode node;
        public final Frame closure;
        private CompiledFunction compiled;
        /** Frame of the last execution (only with node.forceFrame). */
        public Frame lastFrame;

        public ScriptFunction(FunctionNode node, Frame closure) {
            this.node = node;
            this.closure = closure;
        }

        /** Compiled instance for this closure (null if Tier 1 is unavailable). */
        public CompiledFunction compiled() {
            CompiledFunction c = compiled;
            if (c != null) return c;
            FunctionNode n = node;
            if (n.jitClass == null) return null;
            try {
                c = (CompiledFunction) n.jitCtor.newInstance(n, closure, n.jitConsts);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            compiled = c;
            return c;
        }

        @Override
        public Object call(Object[] args) {
            FunctionNode n = node;
            me.padej.jumper.jit.ScriptLoader l = n.loader;
            me.padej.jumper.runtime.Access a = l == null ? null : l.access;
            if (a == null) return call0(args);
            // entering a script under a policy: everything below on the stack (including direct calls between
            // compiled bodies) reaches Java through Interop with this policy in a ThreadLocal
            me.padej.jumper.runtime.Access prev = me.padej.jumper.runtime.Access.enter(a);
            try {
                return call0(args);
            } finally {
                me.padej.jumper.runtime.Access.exit(prev);
            }
        }

        private Object call0(Object[] args) {
            CompiledFunction c = compiled;
            FunctionNode n = node;
            me.padej.jumper.jit.ScriptLoader l = n.loader;
            // cancellation point at the entry (see Compiler.genBody): recursion and Java-driven callbacks
            if (l != null && l.cancelled && l.cancellable) throw me.padej.jumper.runtime.Ops.cancelled();
            if (c != null) {
                Object r = c.call(args);
                if (n.forceFrame) lastFrame = c.lastFrame;
                return r;
            }
            if (n.jitClass == null && !n.jitFailed && Jit.ENABLED) {
                if (++n.calls >= JIT_THRESHOLD || n.hasLoop || Jit.FORCE) Jit.compile(n);
            }
            if (n.jitClass != null) {
                c = compiled();
                if (c != null) {
                    Object r = c.call(args);
                    if (n.forceFrame) lastFrame = c.lastFrame;
                    return r;
                }
            }
            return interpret(args);
        }

        public Object interpret(Object[] args) {
            FunctionNode n = node;
            Frame f = new Frame(n.nslots, n.nprims, closure);
            if (n.forceFrame) lastFrame = f;
            int np = n.nparams;
            VarType[] types = n.paramTypes;
            int[] idx = n.paramIndex;
            for (int i = 0; i < np; i++) {
                VarType t = types[i];
                Object a = i < args.length ? args[i] : null;
                switch (t) {
                    case DYN -> f.slots[idx[i]] = a;
                    case INT -> f.p[idx[i]] = a instanceof Integer v ? v : a == null ? 0 : t.toBits(a);
                    case DOUBLE -> f.p[idx[i]] = Double.doubleToRawLongBits(a instanceof Double v ? v : a == null ? 0.0 : (Double) t.coerce(a));
                    case LONG -> f.p[idx[i]] = a instanceof Long v ? v : a == null ? 0L : t.toBits(a);
                    case BOOLEAN -> f.p[idx[i]] = a == null ? 0 : t.toBits(a);
                    default -> f.slots[idx[i]] = t.coerce(a);
                }
            }
            n.body.exec(f);
            Object r = f.ret;
            return n.returnType == VarType.DYN ? r : n.returnType.coerce(r);
        }

        @Override
        public String name() {
            return node.name;
        }

        @Override
        public String toString() {
            return "function " + node.name;
        }
    }
}
