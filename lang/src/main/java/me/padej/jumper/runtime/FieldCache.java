package me.padej.jumper.runtime;

import me.padej.jumper.ast.VarType;

/**
 * Inline cache for table field access at one code site (`obj.name`).
 * Monomorphic: remembers one shape. A miss is a regular lookup plus a cache update.
 * For the sealed shape of a class instance it also remembers the field type: primitives are read from prims.
 */
public final class FieldCache {
    public final String name;
    private Shape shape = Shape.NONE;   // shape for which idx is valid
    private int idx;
    private VarType ftype = VarType.DYN; // declared field type (DYN for plain tables)
    private Object fcls;                 // class of a class-typed field (Classes.ClassNode) or null
    private Shape addFrom = Shape.NONE; // transition when a new key is added: addFrom -> addTo
    private Shape addTo;

    // Java object property: receiver class -> direct MethodHandles
    private Class<?> jcls;
    private java.lang.invoke.MethodHandle jget, jset;

    public FieldCache(String name) {
        this.name = name;
    }

    /** obj.name for non-tables: a Java object property via a cached MethodHandle, otherwise the generic path. */
    public Object getJava(Object o) {
        if (o != null && o.getClass() == jcls) {
            java.lang.invoke.MethodHandle g = jget;
            if (g != null) {
                try {
                    return g.invokeExact(o);
                } catch (Throwable t) {
                    throw JmpError.rethrow(t);
                }
            }
        }
        Object r = Ops.member(o, name);
        if (cacheable(o)) {
            java.lang.invoke.MethodHandle g = Interop.propertyGetter(o.getClass(), name);
            if (g != null) { jset = null; jcls = o.getClass(); jget = g; }
        }
        return r;
    }

    public void setJava(Object o, Object v) {
        if (o != null && o.getClass() == jcls) {
            java.lang.invoke.MethodHandle st = jset;
            if (st != null) {
                try {
                    st.invokeExact(o, v);
                    return;
                } catch (Throwable t) {
                    throw JmpError.rethrow(t);
                }
            }
        }
        Ops.setMember(o, name, v);
        if (cacheable(o)) {
            java.lang.invoke.MethodHandle st = Interop.propertySetter(o.getClass(), name);
            if (st != null) { jget = null; jcls = o.getClass(); jset = st; }
        }
    }

    private static boolean cacheable(Object o) {
        return o != null && !(o instanceof JTable) && !(o instanceof JArray) && !(o instanceof JavaClass)
                && !(o instanceof String) && !(o instanceof Throwable) && !o.getClass().isArray();
    }

    // Negative results, so nobody tries them a second time (measured in the container,
    // object_fields / game_tick / nbody, dynamic mode):
    //
    //  * caching the box in the table itself (values keeps the box of a primitive slot, a write
    //    clears it): nbody 1493 -> 1312, but game_tick 1487 -> 2167. Clearing the box on every
    //    typed write costs more than the boxing it saves.
    //  * a "bits -> box" memo at the access site: game_tick 1487 -> 3142, nbody 1493 -> 2295.
    //    In a hot loop the value changes every step, the memo almost always misses, and two
    //    writes to cache fields per read turn a field read into a write.
    //
    // What remains is the honest price of typing a slot: a read in a dynamic context creates a box
    // where values used to hold a ready one. The only way to remove it is to type the expression
    // itself, i.e. extend Tier 1 speculation to reads (see jit/Compiler.specCompound).

    public Object get(JTable t) {
        Shape s = t.shape;
        if (s == shape) {
            VarType ft = ftype;
            return ft.isPrimitive() ? ft.fromBits(t.bitsAt(idx)) : t.refAt(idx);
        }
        if (t.isDict()) return t.get(name);
        int i = s.indexOf(name);
        if (i < 0) return t.cls() == null ? null : methodValue(t);
        shape = s;
        idx = i;
        ftype = s.typeAt(i);
        fcls = s.classAt(i);
        return t.at(i);
    }

    // Typed access: when the surrounding expression is primitive (`long s += o.x`) there is no
    // point boxing the field value. The fast path is the same shape check as in get(), but the
    // result is read from prims/values directly in the needed type. A miss goes to the generic get()/set().

    public int getInt(JTable t) {
        if (t.shape == shape) {
            VarType ft = ftype;
            if (ft == VarType.INT) return (int) t.bitsAt(idx);
            if (ft == VarType.DYN) return Ops.unboxInt(t.refAt(idx));
        }
        return Ops.unboxInt(get(t));
    }

    public long getLong(JTable t) {
        if (t.shape == shape) {
            VarType ft = ftype;
            if (ft == VarType.LONG) return t.bitsAt(idx);
            if (ft == VarType.INT) return (int) t.bitsAt(idx);
            if (ft == VarType.DYN) return Ops.unboxLong(t.refAt(idx));
        }
        return Ops.unboxLong(get(t));
    }

    public double getDouble(JTable t) {
        if (t.shape == shape) {
            VarType ft = ftype;
            if (ft == VarType.DOUBLE) return Double.longBitsToDouble(t.bitsAt(idx));
            if (ft == VarType.INT) return (int) t.bitsAt(idx);
            if (ft == VarType.LONG) return t.bitsAt(idx);
            if (ft == VarType.DYN) return Ops.unboxDouble(t.refAt(idx));
        }
        return Ops.unboxDouble(get(t));
    }

    public boolean getBool(JTable t) {
        if (t.shape == shape) {
            VarType ft = ftype;
            if (ft == VarType.BOOLEAN) return t.bitsAt(idx) != 0;
            if (ft == VarType.DYN) return Ops.truthy(t.refAt(idx));
        }
        return Ops.truthy(get(t));
    }

    public void setI(JTable t, int v) {
        if (t.shape == shape && ftype == VarType.INT) { t.setBits(idx, v); return; }
        set(t, v);
    }

    public void setJ(JTable t, long v) {
        if (t.shape == shape && ftype == VarType.LONG) { t.setBits(idx, v); return; }
        set(t, v);
    }

    public void setD(JTable t, double v) {
        if (t.shape == shape && ftype == VarType.DOUBLE) { t.setBits(idx, Double.doubleToRawLongBits(v)); return; }
        set(t, v);
    }

    public void setZ(JTable t, boolean v) {
        if (t.shape == shape && ftype == VarType.BOOLEAN) { t.setBits(idx, v ? 1L : 0L); return; }
        set(t, v);
    }

    /** Field not found on a class instance: a method as a bound value, otherwise an error (the class is sealed). */
    private Object methodValue(JTable t) {
        JFunction m = t.cls().findMethod(name);
        if (m == null) throw t.noField(name);
        return JClass.bind(m, t);
    }

    public void set(JTable t, Object v) {
        Shape s = t.shape;
        if (s == shape) {
            VarType ft = ftype;
            if (ft == VarType.DYN) {
                Object k = fcls;
                if (k != null) v = Ops.checkClass(v, (me.padej.jumper.ast.Classes.ClassNode) k);
                if (v != null || t.cls() != null) { t.setRef(idx, v); return; }
            }
            // On a sealed instance the primitive field type is declared and the value is coerced to it.
            // On a plain table it is only the slot representation: a foreign value cannot be coerced,
            // the slot goes back to reference form - otherwise `t.x = "s"` after `t.x = 1.5` would
            // fail where it used to simply store the string.
            else if (ft.isPrimitive()) {
                if (t.cls() == null) t.setSlot(idx, v);
                else t.setBits(idx, ft.toBits(v));
                return;
            }
            else { t.setRef(idx, ft.coerce(v)); return; }
        }
        if (s == addFrom && v != null) { t.addNew(addTo, v); return; }
        if (t.cls() != null) { // sealed instance
            int i = s.indexOf(name);
            if (i < 0) throw t.noField(name);
            shape = s;
            idx = i;
            ftype = s.typeAt(i);
            fcls = s.classAt(i);
            t.setAt(i, v);
            return;
        }
        if (t.isDict() || v == null) { t.put(name, v); return; }
        int i = s.indexOf(name);
        if (i >= 0) {
            // ftype is taken from the shape, not set to DYN: a plain table's slot can be
            // primitive too, and a write ignoring the representation would disagree with reads.
            t.setSlot(i, v);
            shape = t.shape;
            idx = i;
            ftype = shape.typeAt(i);
            fcls = null;
            return;
        }
        if (s.size() >= Shape.MAX_KEYS) { t.put(name, v); return; }
        Shape next = s.child(name);
        addFrom = s;
        addTo = next;
        t.addNew(next, v);
    }
}
