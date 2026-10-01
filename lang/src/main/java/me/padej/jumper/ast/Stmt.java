package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;

/** Statement. exec returns a completion code - no exceptions for break/continue/return. */
public abstract class Stmt {
    /** Child nodes, computed once (jit/Captures.children). */
    public Object[] kids;
    public static final int NORMAL = 0, BREAK = 1, CONTINUE = 2, RETURN = 3;

    public final int line;

    protected Stmt(int line) {
        this.line = line;
    }

    public abstract int exec(Frame f);
}
