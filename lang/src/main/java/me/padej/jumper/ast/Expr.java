package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;
import me.padej.jumper.runtime.JmpError;
import me.padej.jumper.runtime.Ops;

/**
 * Expression: evaluated in a frame. AST nodes are executable - that is Tier 0.
 *
 * Every node has a static type (DYN = dynamic). Nodes with a primitive type
 * override evalInt/evalLong/evalDouble/evalBool and work without boxing;
 * their eval() is a wrapper needed only when the result goes into a dynamic context.
 * For dynamic nodes evalInt etc. unbox with a type check.
 */
public abstract class Expr {
    public final int line;
    /** Child nodes, computed once (jit/Captures.children): there are several compilation traversals, reflection runs once. */
    public Object[] kids;
    /** Static type of the result. DYN - unknown until execution. */
    public VarType type = VarType.DYN;
    /** Script class the value statically is an instance of (null - unknown). Only with type == DYN. */
    public Classes.ClassNode staticClass;
    /**
     * Element type, if the value is statically a Java array (null - unknown).
     *
     * <p>Needed for exactly the same reason Java needs a declared array type: as long as the exact type
     * is known only inside Ops, every element access is a type check in a foreign method,
     * and C2 can neither hoist the bounds check out of the loop nor vectorize the pass.
     * The difference was measured on the sieve: 97.6 ms versus 69.1 for the same algorithm.
     */
    public Class<?> arrayComp;

    protected Expr(int line) {
        this.line = line;
    }

    public abstract Object eval(Frame f);

    public boolean evalBool(Frame f) {
        Object v = eval(f);
        return v != null && v != Boolean.FALSE;
    }

    public int evalInt(Frame f) {
        Object v = eval(f);
        if (v instanceof Integer i) return i;
        throw new JmpError("Expected int, got " + Ops.typeName(v));
    }

    public long evalLong(Frame f) {
        Object v = eval(f);
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i;
        throw new JmpError("Expected long, got " + Ops.typeName(v));
    }

    public double evalDouble(Frame f) {
        Object v = eval(f);
        if (v instanceof Double d) return d;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        throw new JmpError("Expected double, got " + Ops.typeName(v));
    }

    public Expr typed(VarType t) {
        this.type = t;
        return this;
    }
}
