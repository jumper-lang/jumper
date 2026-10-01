package me.padej.jumper.ast;

import me.padej.jumper.interp.Frame;
import me.padej.jumper.runtime.FieldCache;
import me.padej.jumper.runtime.JClass;
import me.padej.jumper.runtime.JmpError;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Ops;
import me.padej.jumper.runtime.Shape;

/**
 * Access to a field of `this` inside class methods when the instance layout is known at parse time:
 * the field index and its type are fixed, the runtime check is a single shape comparison (cached per node),
 * primitive fields are read/written without boxing through JTable.bitsAt/setBits.
 *
 * Shape miss (the method was called on an instance of another class, a subclass shape, etc.) -
 * check "a field with this name and type lies at this index", otherwise the generic path through FieldCache.
 */
public final class Fields {
    private Fields() {}

    public static final class ThisField extends Expr {
        public final Expr self;
        public final String name;
        public final int idx;
        public final VarType ftype;
        /**
         * The class the receiver statically is. Needed by Tier 1: it locates the generated
         * instance class, and the field is read with an ordinary getfield instead of an
         * array access. Subclasses pass the same guard - the inheritance chain of the
         * generated classes mirrors the script chain.
         */
        public final Classes.ClassNode owner;
        /** Shape for which idx/ftype have been verified (read directly from Tier 1 bytecode). */
        public Shape cached = Shape.NONE;
        private final FieldCache generic;

        public ThisField(Expr self, String name, int idx, VarType ftype, Classes.ClassNode owner, int line) {
            super(line);
            this.owner = owner;
            this.self = self;
            this.name = name;
            this.idx = idx;
            this.ftype = ftype;
            this.generic = new FieldCache(name);
            this.type = ftype == VarType.VOID ? VarType.DYN : ftype;
        }

        /** Instance with a verified layout, or null. */
        public JTable target(Object o) {
            if (o instanceof JTable t) {
                if (t.shape == cached) return t;
                if (validate(t)) return t;
            }
            return null;
        }

        private boolean validate(JTable t) {
            Shape s = t.shape;
            if (s != null && s.sealed() && idx < s.size() && name.equals(s.keyAt(idx)) && s.typeAt(idx) == ftype) {
                cached = s;
                return true;
            }
            return false;
        }

        // ---- slow paths (also called from Tier 1) ----

        public Object slowGet(Object o) {
            JTable t = target(o);
            if (t != null) return ftype.isPrimitive() ? ftype.fromBits(t.bitsAt(idx)) : t.refAt(idx);
            if (o instanceof JTable tt) return generic.get(tt);
            return Ops.member(o, name);
        }

        public int slowInt(Object o) { JTable t = target(o); return t != null ? (int) t.bitsAt(idx) : (Integer) VarType.INT.coerce(slowGet(o)); }
        public long slowLong(Object o) { JTable t = target(o); return t != null ? t.bitsAt(idx) : (Long) VarType.LONG.coerce(slowGet(o)); }
        public double slowDouble(Object o) { JTable t = target(o); return t != null ? Double.longBitsToDouble(t.bitsAt(idx)) : (Double) VarType.DOUBLE.coerce(slowGet(o)); }
        public boolean slowBool(Object o) { JTable t = target(o); return t != null ? t.bitsAt(idx) != 0 : (Boolean) VarType.BOOLEAN.coerce(slowGet(o)); }

        public void slowSet(Object o, Object v) {
            JTable t = target(o);
            if (t != null) { t.setAt(idx, v); return; }
            if (o instanceof JTable tt) generic.set(tt, v);
            else Ops.setMember(o, name, v);
        }

        public void slowSetI(Object o, int v) { JTable t = target(o); if (t != null) t.setBits(idx, v); else slowSet(o, v); }
        public void slowSetJ(Object o, long v) { JTable t = target(o); if (t != null) t.setBits(idx, v); else slowSet(o, v); }
        public void slowSetD(Object o, double v) { JTable t = target(o); if (t != null) t.setBits(idx, Double.doubleToRawLongBits(v)); else slowSet(o, v); }
        public void slowSetZ(Object o, boolean v) { JTable t = target(o); if (t != null) t.setBits(idx, v ? 1L : 0L); else slowSet(o, v); }

        // ---- Tier 0 ----

        @Override
        public Object eval(Frame f) {
            return slowGet(self.eval(f));
        }

        @Override
        public int evalInt(Frame f) {
            return ftype == VarType.INT ? slowInt(self.eval(f)) : super.evalInt(f);
        }

        @Override
        public long evalLong(Frame f) {
            return ftype == VarType.LONG ? slowLong(self.eval(f)) : ftype == VarType.INT ? slowInt(self.eval(f)) : super.evalLong(f);
        }

        @Override
        public double evalDouble(Frame f) {
            return ftype == VarType.DOUBLE ? slowDouble(self.eval(f)) : super.evalDouble(f);
        }

        @Override
        public boolean evalBool(Frame f) {
            return ftype == VarType.BOOLEAN ? slowBool(self.eval(f)) : super.evalBool(f);
        }
    }

    /**
     * Static field or class method by bare name inside a class: the value lives in
     * JClass.statics of the declaring class (index fixed by the shape), primitives without boxing.
     */
    public static final class StaticField extends Expr {
        public final Classes.ClassNode cls;
        public final String name;
        public final int idx;
        public final VarType ftype;

        public StaticField(Classes.ClassNode cls, String name, int idx, VarType ftype, int line) {
            super(line);
            this.cls = cls;
            this.name = name;
            this.idx = idx;
            this.ftype = ftype;
            this.type = ftype;
            this.staticClass = cls.staticClass(name);
        }

        private JTable table() {
            JClass c = cls.runtime;
            if (c == null || c.statics == null) throw new JmpError("Class " + cls.name + " is not initialized");
            return c.statics;
        }

        public Object get() { return table().at(idx); }
        public int getInt() { return (int) table().bitsAt(idx); }
        public long getLong() { return table().bitsAt(idx); }
        public double getDouble() { return Double.longBitsToDouble(table().bitsAt(idx)); }
        public boolean getBool() { return table().bitsAt(idx) != 0; }

        public void set(Object v) { table().setAt(idx, v); }
        public void setI(int v) { table().setBits(idx, v); }
        public void setJ(long v) { table().setBits(idx, v); }
        public void setD(double v) { table().setBits(idx, Double.doubleToRawLongBits(v)); }
        public void setZ(boolean v) { table().setBits(idx, v ? 1L : 0L); }

        @Override public Object eval(Frame f) { return get(); }
        @Override public int evalInt(Frame f) { return ftype == VarType.INT ? getInt() : super.evalInt(f); }
        @Override public long evalLong(Frame f) { return ftype == VarType.LONG ? getLong() : ftype == VarType.INT ? getInt() : super.evalLong(f); }
        @Override public double evalDouble(Frame f) { return ftype == VarType.DOUBLE ? getDouble() : super.evalDouble(f); }
        @Override public boolean evalBool(Frame f) { return ftype == VarType.BOOLEAN ? getBool() : super.evalBool(f); }
    }

    /** Cls.x = value for a static field (the value type is checked by the parser). */
    public static final class StaticFieldSet extends Expr {
        public final StaticField target;
        public final Expr value;

        public StaticFieldSet(StaticField target, Expr value, int line) {
            super(line);
            this.target = target;
            this.value = value;
            this.type = target.type;
        }

        @Override
        public Object eval(Frame f) {
            switch (target.ftype) {
                case INT -> { return evalInt(f); }
                case LONG -> { return evalLong(f); }
                case DOUBLE -> { return evalDouble(f); }
                case BOOLEAN -> { return evalBool(f); }
                default -> {
                    Object v = value.eval(f);
                    target.set(v);
                    return v;
                }
            }
        }

        @Override public int evalInt(Frame f) { if (target.ftype != VarType.INT) return super.evalInt(f); int v = value.evalInt(f); target.setI(v); return v; }
        @Override public long evalLong(Frame f) { if (target.ftype != VarType.LONG) return super.evalLong(f); long v = value.evalLong(f); target.setJ(v); return v; }
        @Override public double evalDouble(Frame f) { if (target.ftype != VarType.DOUBLE) return super.evalDouble(f); double v = value.evalDouble(f); target.setD(v); return v; }
        @Override public boolean evalBool(Frame f) { if (target.ftype != VarType.BOOLEAN) return super.evalBool(f); boolean v = value.evalBool(f); target.setZ(v); return v; }
    }

    /** ++/-- for an int static. */
    public static final class StaticFieldInc extends Prims.IntExpr {
        public final StaticField target;
        public final int delta;
        public final boolean prefix;

        public StaticFieldInc(StaticField target, int delta, boolean prefix, int line) {
            super(line);
            this.target = target;
            this.delta = delta;
            this.prefix = prefix;
        }

        @Override
        public int evalInt(Frame f) {
            int old = target.getInt();
            target.setI(old + delta);
            return prefix ? old + delta : old;
        }
    }

    /** Class check of a value written to a variable/field/parameter with a class type. */
    public static final class CheckClass extends Expr {
        public final Expr value;
        public final Classes.ClassNode cls;

        public CheckClass(Expr value, Classes.ClassNode cls, int line) {
            super(line);
            this.value = value;
            this.cls = cls;
            this.staticClass = cls;
        }

        @Override
        public Object eval(Frame f) {
            return Ops.checkClass(value.eval(f), cls);
        }
    }

    /** A value for a variable, parameter or return of a Java class type (`Random r`): null or an instance of it. */
    public static final class CheckJava extends Expr {
        public final Expr value;
        public final Class<?> cls;

        public CheckJava(Expr value, Class<?> cls, int line) {
            super(line);
            this.value = value;
            this.cls = cls;
        }

        @Override
        public Object eval(Frame f) {
            return Ops.checkJava(value.eval(f), cls);
        }
    }

    /** this.x = value (the type of value is already checked by the parser against the field type). */
    public static final class ThisFieldSet extends Expr {
        public final ThisField target;
        public final Expr value;

        public ThisFieldSet(ThisField target, Expr value, int line) {
            super(line);
            this.target = target;
            this.value = value;
            this.type = target.type;
        }

        @Override
        public Object eval(Frame f) {
            switch (target.ftype) {
                case INT -> { return evalInt(f); }
                case LONG -> { return evalLong(f); }
                case DOUBLE -> { return evalDouble(f); }
                case BOOLEAN -> { return evalBool(f); }
                default -> {
                    Object o = target.self.eval(f);
                    Object v = value.eval(f);
                    if (target.ftype == VarType.STRING) v = Ops.toStringSlot(v);
                    JTable t = target.target(o);
                    if (t != null) t.setRef(target.idx, v); else target.slowSet(o, v);
                    return v;
                }
            }
        }

        @Override
        public int evalInt(Frame f) {
            if (target.ftype != VarType.INT) return super.evalInt(f);
            Object o = target.self.eval(f);
            int v = value.evalInt(f);
            target.slowSetI(o, v);
            return v;
        }

        @Override
        public long evalLong(Frame f) {
            if (target.ftype != VarType.LONG) return super.evalLong(f);
            Object o = target.self.eval(f);
            long v = value.evalLong(f);
            target.slowSetJ(o, v);
            return v;
        }

        @Override
        public double evalDouble(Frame f) {
            if (target.ftype != VarType.DOUBLE) return super.evalDouble(f);
            Object o = target.self.eval(f);
            double v = value.evalDouble(f);
            target.slowSetD(o, v);
            return v;
        }

        @Override
        public boolean evalBool(Frame f) {
            if (target.ftype != VarType.BOOLEAN) return super.evalBool(f);
            Object o = target.self.eval(f);
            boolean v = value.evalBool(f);
            target.slowSetZ(o, v);
            return v;
        }
    }

    /** this.n++ / --n for an int field. */
    public static final class ThisFieldInc extends Prims.IntExpr {
        public final ThisField target;
        public final int delta;
        public final boolean prefix;

        public ThisFieldInc(ThisField target, int delta, boolean prefix, int line) {
            super(line);
            if (target.ftype != VarType.INT) throw new JmpError("Cannot increment " + target.ftype + " field");
            this.target = target;
            this.delta = delta;
            this.prefix = prefix;
        }

        @Override
        public int evalInt(Frame f) {
            Object o = target.self.eval(f);
            int old = target.slowInt(o);
            target.slowSetI(o, old + delta);
            return prefix ? old + delta : old;
        }
    }
}
