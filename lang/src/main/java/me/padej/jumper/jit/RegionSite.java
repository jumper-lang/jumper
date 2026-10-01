package me.padej.jumper.jit;

import me.padej.jumper.ast.VarType;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Shape;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

/**
 * One guarded region's view of one table receiver: which fields it touches and in what
 * representation, and the shape the region is currently linked against.
 *
 * <p>The predecessor ({@code TableSite.idxFor}) returned the field indices as an {@code int[]} and the
 * generated code read {@code prims[idx[i]]}: two array loads with bounds checks per field, and
 * nothing C2 could fold, because the indices were data. Here the region's guard and every field
 * access are {@code invokedynamic} sites whose targets are built <b>after</b> the shape is known:
 * the shape and the slot index are bound into the method handles as constants, so an access
 * compiles to {@code prims[3]} (or, for a layout class, to a plain {@code getfield}). C2 inlines a
 * {@link MutableCallSite} target and registers a dependency on it, so relinking is safe and free
 * on the hot path.
 *
 * <p>Protocol. The guard site {@code (Object)Z} starts on {@link #miss}: it resolves the receiver's
 * shape (all keys present, every slot in the expected representation), links the guard to
 * {@code shape == S} and the access sites to S's indices, and answers true. A later miss re-resolves
 * for the new shape - the region is monomorphic - and after {@link #GIVE_UP} misses the site goes
 * dead: the guard answers false forever and the region runs its generic copy.
 *
 * <p>Every access keeps its own {@code shape == S} test in front of the field load; C2 folds it
 * into the guard's test of the same object (no store in between touches {@code shape}), so it costs
 * nothing, and it turns the one thing that could go wrong - another thread relinking the region
 * between the guard and the access - into an exception instead of a read from a wrong slot.
 */
public final class RegionSite {
    /** How many misses before the site is declared hopeless. */
    private static final int GIVE_UP = 8;

    private static final MethodHandle MISS, IS, TRUE, FALSE, DEOPT;
    private static final MethodHandle GET_I, GET_L, GET_D, GET_Z, SET_I, SET_L, SET_D, SET_Z;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            MISS = l.findVirtual(RegionSite.class, "miss", MethodType.methodType(boolean.class, Object.class));
            IS = l.findStatic(RegionSite.class, "is", MethodType.methodType(boolean.class, Class.class, Shape.class, Object.class));
            TRUE = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, Object.class);
            FALSE = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, Object.class);
            DEOPT = l.findStatic(RegionSite.class, "deopt", MethodType.methodType(void.class));
            GET_I = l.findStatic(RegionSite.class, "getI", MethodType.methodType(int.class, int.class, Object.class));
            GET_L = l.findStatic(RegionSite.class, "getL", MethodType.methodType(long.class, int.class, Object.class));
            GET_D = l.findStatic(RegionSite.class, "getD", MethodType.methodType(double.class, int.class, Object.class));
            GET_Z = l.findStatic(RegionSite.class, "getZ", MethodType.methodType(boolean.class, int.class, Object.class));
            SET_I = l.findStatic(RegionSite.class, "setI", MethodType.methodType(void.class, int.class, Object.class, int.class));
            SET_L = l.findStatic(RegionSite.class, "setL", MethodType.methodType(void.class, int.class, Object.class, long.class));
            SET_D = l.findStatic(RegionSite.class, "setD", MethodType.methodType(void.class, int.class, Object.class, double.class));
            SET_Z = l.findStatic(RegionSite.class, "setZ", MethodType.methodType(void.class, int.class, Object.class, boolean.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String[] keys;
    /** Expected slot representation of every key; the compiler guesses it from the code around the access. */
    private final VarType[] kinds;
    /** Shape the region is linked against; Shape.NONE = not determined yet (or dead). */
    private Shape shape = Shape.NONE;
    /** Class of the receivers the region is linked against: Plain (slots in arrays) or a layout class (slots as fields). */
    private Class<?> cls;
    private int[] idx;
    private int misses;
    private boolean dead;

    private final MutableCallSite guard = new MutableCallSite(MethodType.methodType(boolean.class, Object.class));
    /** Access sites by field number, created when the generated code first reaches them. */
    private final MutableCallSite[] gets, sets;

    public RegionSite(String[] keys, VarType[] kinds) {
        this.keys = keys;
        this.kinds = kinds;
        this.gets = new MutableCallSite[keys.length];
        this.sets = new MutableCallSite[keys.length];
        guard.setTarget(MISS.bindTo(this));
    }

    // ---------- bootstraps: the site object sits in the caller's K under kIdx ----------

    private static RegionSite of(MethodHandles.Lookup lookup, int kIdx) throws Throwable {
        Object[] k = (Object[]) lookup.findStaticGetter(lookup.lookupClass(), "K", Object[].class).invoke();
        return (RegionSite) k[kIdx];
    }

    public static CallSite bootstrapGuard(MethodHandles.Lookup lookup, String name, MethodType type, int kIdx) throws Throwable {
        return of(lookup, kIdx).guard;
    }

    public static CallSite bootstrapGet(MethodHandles.Lookup lookup, String name, MethodType type, int kIdx, int field) throws Throwable {
        return of(lookup, kIdx).access(field, type, false);
    }

    public static CallSite bootstrapSet(MethodHandles.Lookup lookup, String name, MethodType type, int kIdx, int field) throws Throwable {
        return of(lookup, kIdx).access(field, type, true);
    }

    private synchronized CallSite access(int field, MethodType type, boolean set) {
        MutableCallSite[] arr = set ? sets : gets;
        MutableCallSite s = arr[field];
        if (s == null) {
            s = new MutableCallSite(type);
            s.setTarget(target(field, set, type));
            arr[field] = s;
        }
        return s;
    }

    /**
     * Target of an access site for the current shape: its own class-and-shape test, then the slot -
     * {@code prims[i]} of a Plain with a constant index, or a layout class's own {@code get<i>}/
     * {@code set<i>} ({@link LayoutGen#genFieldAccessors}) - a single generated static method either
     * way, never a method handle composed from two, so both paths inline the same way.
     */
    private MethodHandle target(int field, boolean set, MethodType type) {
        if (shape == Shape.NONE) return deoptOf(type);
        int i = idx[field];
        VarType k = kinds[field];
        MethodHandle body;
        try {
            body = cls == JTable.Plain.class ? MethodHandles.insertArguments(set ? plainSetter(k) : plainGetter(k), 0, i)
                    : set ? LayoutGen.setter(cls, i) : LayoutGen.getter(cls, i);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        MethodHandle test = MethodHandles.insertArguments(IS, 0, cls, shape);
        if (set) test = MethodHandles.dropArguments(test, 1, type.parameterType(1));
        return MethodHandles.guardWithTest(test, body.asType(type), deoptOf(type));
    }

    private static MethodHandle plainGetter(VarType k) {
        return switch (k) { case INT -> GET_I; case LONG -> GET_L; case DOUBLE -> GET_D; case BOOLEAN -> GET_Z; default -> throw new IllegalStateException(k.toString()); };
    }

    private static MethodHandle plainSetter(VarType k) {
        return switch (k) { case INT -> SET_I; case LONG -> SET_L; case DOUBLE -> SET_D; case BOOLEAN -> SET_Z; default -> throw new IllegalStateException(k.toString()); };
    }

    /** A handle of the given type that throws: the access ran without its guard having passed for this shape. */
    private static MethodHandle deoptOf(MethodType type) {
        MethodHandle h = DEOPT;
        Class<?> r = type.returnType();
        if (r != void.class) h = MethodHandles.filterReturnValue(h, MethodHandles.constant(r, r == double.class ? 0.0 : r == long.class ? 0L : r == boolean.class ? false : 0)
                .asType(MethodType.methodType(r)));
        return MethodHandles.dropArguments(h, 0, type.parameterList());
    }

    // ---------- linking ----------

    /** The guard's shape test failed (or nothing is linked yet): try to link the region against this receiver. */
    boolean miss(Object o) {
        synchronized (this) {
            if (Jit.DEBUG) System.err.println("[region] miss " + java.util.Arrays.toString(keys) + " on "
                    + (o == null ? "null" : o.getClass().getSimpleName() + " " + (o instanceof JTable t ? t.shape : "")) + " (miss " + (misses + 1) + ")");
            if (dead) return false;
            // Every miss counts; after GIVE_UP the site goes silent forever.
            if (++misses > GIVE_UP) {
                dead = true;
                shape = Shape.NONE;
                idx = null;
                relink();
                return false;
            }
            // a Plain (slots in arrays) or a layout class (slots as fields); class instances have their own path
            if (!(o instanceof JTable.Plain || o instanceof JTable.Fixed)) return false;
            JTable t = (JTable) o;
            Shape s = t.shape;
            // A dictionary (shape == null) and a sealed class instance do not belong here:
            // the first has no shape at all, the second has its own path - ThisField.
            if (s == null || s.sealed() || s.store != null) return false;
            int[] a = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                int k = s.indexOf(keys[i]);
                if (k < 0 || s.typeAt(k) != kinds[i]) return false;
                a[i] = k;
            }
            shape = s;
            cls = o.getClass();
            idx = a;
            relink();
            return true;
        }
    }

    private void relink() {
        guard.setTarget(dead ? FALSE : MethodHandles.guardWithTest(MethodHandles.insertArguments(IS, 0, cls, shape), TRUE, MISS.bindTo(this)));
        for (int f = 0; f < keys.length; f++) {
            if (gets[f] != null) gets[f].setTarget(target(f, false, gets[f].type()));
            if (sets[f] != null) sets[f].setTarget(target(f, true, sets[f].type()));
        }
    }

    // ---------- the pieces the handles are built from ----------

    /**
     * The receiver is of the linked class and shape. The class is checked too because a shape does
     * not determine the class: a Plain and a layout table can share a shape (a table grown from a
     * smaller literal, a literal built before its layout existed).
     */
    static boolean is(Class<?> c, Shape s, Object o) {
        return o != null && o.getClass() == c && ((JTable) o).shape == s;
    }

    static void deopt() {
        throw new IllegalStateException("table shape changed between a region guard and a field access: an Interpreter must not be shared between threads");
    }

    static int getI(int i, Object o) { return (int) ((JTable.Plain) o).prims[i]; }
    static long getL(int i, Object o) { return ((JTable.Plain) o).prims[i]; }
    static double getD(int i, Object o) { return Double.longBitsToDouble(((JTable.Plain) o).prims[i]); }
    static boolean getZ(int i, Object o) { return ((JTable.Plain) o).prims[i] != 0; }
    static void setI(int i, Object o, int v) { ((JTable.Plain) o).prims[i] = v; }
    static void setL(int i, Object o, long v) { ((JTable.Plain) o).prims[i] = v; }
    static void setD(int i, Object o, double v) { ((JTable.Plain) o).prims[i] = Double.doubleToRawLongBits(v); }
    static void setZ(int i, Object o, boolean v) { ((JTable.Plain) o).prims[i] = v ? 1 : 0; }
}
