package me.padej.jumper.interp;

/**
 * Function call frame: local variables in arrays (no HashMap),
 * a reference to the closure frame, a slot for the return value.
 *
 * slots holds dynamic (dyn) and String variables; p holds primitive int/long/double/boolean
 * as bits (double via doubleToRawLongBits). No boxing in typed code.
 */
public final class Frame {
    private static final long[] NO_PRIMS = new long[0];
    private static final Object[] NO_SLOTS = new Object[0];

    public final Object[] slots;
    public final long[] p;
    public final Frame parent;
    public Object ret;

    public Frame(int nslots, int nprims, Frame parent) {
        this.slots = nslots == 0 ? NO_SLOTS : new Object[nslots];
        this.p = nprims == 0 ? NO_PRIMS : new long[nprims];
        this.parent = parent;
    }

    public Frame up(int depth) {
        Frame f = this;
        for (int i = 0; i < depth; i++) f = f.parent;
        return f;
    }
}
