package me.padej.jumper.ast;

import me.padej.jumper.runtime.Ops;

/** Declared/static type. DYN is dynamic. INT/LONG/DOUBLE/BOOLEAN live in Frame.p[] without boxing. */
public enum VarType {
    DYN, INT, LONG, DOUBLE, BOOLEAN, STRING,
    /** Only as a return type: the function returns nothing (a call yields null). */
    VOID;

    public boolean isPrimitive() {
        return this == INT || this == LONG || this == DOUBLE || this == BOOLEAN;
    }

    public boolean isNumeric() {
        return this == INT || this == LONG || this == DOUBLE;
    }

    public Object coerce(Object v) {
        return switch (this) {
            case DYN -> v;
            case INT -> Ops.toIntSlot(v);
            case LONG -> Ops.toLongSlot(v);
            case DOUBLE -> Ops.toDoubleSlot(v);
            case BOOLEAN -> Ops.toBooleanSlot(v);
            case STRING -> Ops.toStringSlot(v);
            case VOID -> null;
        };
    }

    public Object defaultValue() {
        return switch (this) {
            case DYN, STRING, VOID -> null;
            case INT -> 0;
            case LONG -> 0L;
            case DOUBLE -> 0.0;
            case BOOLEAN -> Boolean.FALSE;
        };
    }

    /** Pack a dynamic value into bits for Frame.p[] (with check/widening). */
    public long toBits(Object v) {
        return switch (this) {
            case INT -> (Integer) Ops.toIntSlot(v);
            case LONG -> (Long) Ops.toLongSlot(v);
            case DOUBLE -> Double.doubleToRawLongBits((Double) Ops.toDoubleSlot(v));
            case BOOLEAN -> ((Boolean) Ops.toBooleanSlot(v)) ? 1L : 0L;
            default -> throw new IllegalStateException("not primitive: " + this);
        };
    }

    public Object fromBits(long bits) {
        return switch (this) {
            case INT -> (int) bits;
            case LONG -> bits;
            case DOUBLE -> Double.longBitsToDouble(bits);
            case BOOLEAN -> bits != 0;
            default -> throw new IllegalStateException("not primitive: " + this);
        };
    }

    /** Result type of binary arithmetic from the static types of the operands. */
    public static VarType arith(VarType a, VarType b) {
        if (!a.isNumeric() || !b.isNumeric()) return DYN;
        if (a == DOUBLE || b == DOUBLE) return DOUBLE;
        if (a == LONG || b == LONG) return LONG;
        return INT;
    }
}
