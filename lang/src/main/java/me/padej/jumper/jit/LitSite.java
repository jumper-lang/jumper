package me.padej.jumper.jit;

import me.padej.jumper.ast.Exprs;
import me.padej.jumper.ast.VarType;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Shape;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayList;
import java.util.List;

/**
 * A table literal whose values have no static type ({@code { left: left, right: right }} with
 * {@code dyn} parameters) in Tier 1: an {@code invokedynamic (Object...)Object} with one layer per
 * <i>pattern</i> of the values' kinds (null / Integer / Double / Long / Boolean / anything else).
 *
 * <p>The old path, {@code TableLit.build(Object[])}, was the whole cost of {@code binary_trees/dyn}
 * apart from the recursion itself: an {@code Object[]} for the values, {@code Shape.typedBy} per table,
 * and the layout class's constructor reached through {@code LayoutGen.create} - an {@code invokeExact}
 * on a method handle that is not a constant, which C2 does not inline (JFR: {@code LayoutGen.create}
 * and {@code Invokers.checkCustomized} 37 % of the samples, the Object[] 41 % of the bytes).
 *
 * <p>A pattern fixes everything {@code build} would compute: the typed shape
 * ({@link Shape#withReps}, the same transitions as {@code typedBy}, so the shape object is the one the
 * interpreter gets) and the layout class, so the layer is the class's own {@code make(Shape, slots...)}
 * with the shape bound as a constant. A pattern with nulls is what {@code build} does for it: a Plain
 * built by {@code put} in literal order - null creates no key - so its shape is fixed too.
 * More than {@link #MAX_DEPTH} patterns at one site: the old {@code build} for good.
 */
public final class LitSite {
    static final int MAX_DEPTH = 8;
    private static final int NUL = 0, INT = 1, DBL = 2, LNG = 3, BOOL = 4, REF = 5;

    private static final MethodHandle LINK, BUILD, NULLS, NULLS0, NULLS1, NULLS2, PLAIN, IS_NULL, IS_INT, IS_DBL, IS_LNG, IS_BOOL, IS_REF,
            BITS_I, BITS_D, BITS_L, BITS_Z;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            LINK = l.findVirtual(LitSite.class, "link", MethodType.methodType(void.class, Object[].class));
            BUILD = l.findVirtual(Exprs.TableLit.class, "build", MethodType.methodType(Object.class, Object[].class));
            NULLS = l.findStatic(LitSite.class, "withNulls", MethodType.methodType(Object.class, Shape.class, int.class, Object[].class));
            NULLS0 = l.findStatic(LitSite.class, "withNulls0", MethodType.methodType(Object.class, Shape.class));
            NULLS1 = l.findStatic(LitSite.class, "withNulls1", MethodType.methodType(Object.class, Shape.class, int.class, Object.class));
            NULLS2 = l.findStatic(LitSite.class, "withNulls2", MethodType.methodType(Object.class, Shape.class, int.class, Object.class, Object.class));
            PLAIN = l.findStatic(LitSite.class, "plain", MethodType.methodType(Object.class, Shape.class, Object[].class));
            MethodType p = MethodType.methodType(boolean.class, Object.class);
            IS_NULL = l.findStatic(LitSite.class, "isNull", p);
            IS_INT = l.findStatic(LitSite.class, "isInt", p);
            IS_DBL = l.findStatic(LitSite.class, "isDbl", p);
            IS_LNG = l.findStatic(LitSite.class, "isLng", p);
            IS_BOOL = l.findStatic(LitSite.class, "isBool", p);
            IS_REF = l.findStatic(LitSite.class, "isRef", p);
            MethodType b = MethodType.methodType(long.class, Object.class);
            BITS_I = l.findStatic(LitSite.class, "bitsI", b);
            BITS_D = l.findStatic(LitSite.class, "bitsD", b);
            BITS_L = l.findStatic(LitSite.class, "bitsL", b);
            BITS_Z = l.findStatic(LitSite.class, "bitsZ", b);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Exprs.TableLit lit;
    private final MutableCallSite site;
    private final MethodType type;
    private final MethodHandle generic;
    private final List<int[]> seen = new ArrayList<>();
    private boolean done;

    private LitSite(Exprs.TableLit lit, MethodType type) {
        this.lit = lit;
        this.type = type;
        int n = type.parameterCount();
        // build() wants an array of at least 4 (it keeps it as the table's storage)
        MethodHandle g = BUILD.bindTo(lit).asCollector(Object[].class, n);
        if (n < 4) g = padded(n);
        this.generic = g.asType(type);
        this.site = new MutableCallSite(type);
        MethodHandle link = LINK.bindTo(this).asCollector(Object[].class, n).asType(type.changeReturnType(void.class));
        site.setTarget(MethodHandles.foldArguments(generic, link));
    }

    /** build(Object[max(4, n)]) for n < 4 values. */
    private MethodHandle padded(int n) {
        try {
            MethodHandle pad = MethodHandles.lookup().findStatic(LitSite.class, "pad4", MethodType.methodType(Object[].class, Object[].class));
            return MethodHandles.filterArguments(BUILD.bindTo(lit), 0, pad).asCollector(Object[].class, n);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object[] pad4(Object[] a) { return a.length >= 4 ? a : java.util.Arrays.copyOf(a, 4); }

    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type, int kIdx) throws Throwable {
        Object[] k = (Object[]) lookup.findStaticGetter(lookup.lookupClass(), "K", Object[].class).invoke();
        return new LitSite((Exprs.TableLit) k[kIdx], type).site;
    }

    private static int code(Object v) {
        if (v == null) return NUL;
        if (v instanceof Integer) return INT;
        if (v instanceof Double) return DBL;
        if (v instanceof Long) return LNG;
        if (v instanceof Boolean) return BOOL;
        return REF;
    }

    /** No layer took these values: add one for their pattern, or give up on the site. */
    synchronized void link(Object[] vals) {
        if (done) return;
        int n = vals.length;
        int[] pat = new int[n];
        for (int i = 0; i < n; i++) pat[i] = code(vals[i]);
        for (int[] s : seen) if (java.util.Arrays.equals(s, pat)) return;   // already linked (a race) - nothing to add
        if (seen.size() >= MAX_DEPTH) {
            done = true;
            site.setTarget(generic);
            if (Jit.DEBUG) System.err.println("[lit] " + lit.shape() + " -> generic (megamorphic)");
            return;
        }
        MethodHandle target;
        try {
            target = target(pat);
        } catch (ReflectiveOperationException e) {
            done = true;
            site.setTarget(generic);
            return;
        }
        seen.add(pat);
        site.setTarget(MethodHandles.guardWithTest(test(pat), target.asType(type), site.getTarget()));
        if (Jit.DEBUG) System.err.println("[lit] " + lit.shape() + " pattern " + java.util.Arrays.toString(pat) + " (depth " + seen.size() + ")");
    }

    /** All positions match the pattern: a chain of per-position tests, cheapest failure first. */
    private MethodHandle test(int[] pat) {
        int n = pat.length;
        MethodHandle t = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, type.parameterList());
        MethodHandle f = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, type.parameterList());
        for (int i = n - 1; i >= 0; i--) {
            MethodHandle one = switch (pat[i]) {
                case NUL -> IS_NULL; case INT -> IS_INT; case DBL -> IS_DBL; case LNG -> IS_LNG; case BOOL -> IS_BOOL; default -> IS_REF;
            };
            // (v0..vn-1) -> one(vi)
            MethodHandle at = MethodHandles.permuteArguments(one, type.changeReturnType(boolean.class), i);
            t = MethodHandles.guardWithTest(at, t, f);
        }
        return t;
    }

    /** What build() produces for values of this pattern, as a direct construction. */
    private MethodHandle target(int[] pat) throws ReflectiveOperationException {
        int n = pat.length;
        Shape shape = lit.shape();
        boolean nulls = false;
        for (int c : pat) if (c == NUL) nulls = true;
        if (nulls) {
            // build(): JTable.plain(n) + put(key, value) in order; a null value creates no key
            Shape s = Shape.ROOT;
            for (int i = 0; i < n; i++) if (pat[i] != NUL) s = s.child(shape.keyAt(i));
            // drop the null positions: withNulls gets the non-null values in literal order
            int m = 0;
            for (int c : pat) if (c != NUL) m++;
            // fixed arities without a collector array (asCollector goes through Array.newInstance)
            MethodHandle h = switch (m) {
                case 0 -> MethodHandles.insertArguments(NULLS0, 0, s);
                case 1 -> MethodHandles.insertArguments(NULLS1, 0, s, n);
                case 2 -> MethodHandles.insertArguments(NULLS2, 0, s, n);
                default -> MethodHandles.insertArguments(NULLS, 0, s, n).asCollector(Object[].class, m);
            };
            int[] keep = new int[m];
            int j = 0;
            for (int i = 0; i < n; i++) if (pat[i] != NUL) keep[j++] = i;
            MethodType mt = type.changeReturnType(Object.class);
            return MethodHandles.permuteArguments(h, mt, keep);
        }
        VarType[] reps = new VarType[n];
        for (int i = 0; i < n; i++) {
            reps[i] = switch (pat[i]) { case INT -> VarType.INT; case DBL -> VarType.DOUBLE; case LNG -> VarType.LONG; case BOOL -> VarType.BOOLEAN; default -> null; };
        }
        Shape typed = shape.withReps(reps);
        LayoutGen.Layout lay = LayoutGen.of(typed);
        if (lay == null) {
            return MethodHandles.insertArguments(PLAIN, 0, typed).asCollector(Object[].class, n);
        }
        MethodHandle make = lay.makeSite.dynamicInvoker();
        make = MethodHandles.insertArguments(make, 0, typed);
        // each slot: bits of the box for a primitive rep of the layout, the value itself for a reference
        MethodHandle[] filters = new MethodHandle[n];
        for (int i = 0; i < n; i++) {
            VarType r = lay.reps[i];
            if (!r.isPrimitive()) continue;
            filters[i] = switch (r) { case INT -> BITS_I; case DOUBLE -> BITS_D; case LONG -> BITS_L; default -> BITS_Z; };
        }
        return MethodHandles.filterArguments(make, 0, filters);
    }

    // ---------- pieces ----------

    static boolean isNull(Object v) { return v == null; }
    static boolean isInt(Object v) { return v instanceof Integer; }
    static boolean isDbl(Object v) { return v instanceof Double; }
    static boolean isLng(Object v) { return v instanceof Long; }
    static boolean isBool(Object v) { return v instanceof Boolean; }
    static boolean isRef(Object v) { return v != null && !(v instanceof Integer || v instanceof Double || v instanceof Long || v instanceof Boolean); }

    static long bitsI(Object v) { return (Integer) v; }
    static long bitsD(Object v) { return Double.doubleToRawLongBits((Double) v); }
    static long bitsL(Object v) { return (Long) v; }
    static long bitsZ(Object v) { return ((Boolean) v) ? 1L : 0L; }

    /** build() with nulls among the values: a Plain of n slots holding the non-null ones, in literal order. */
    static Object withNulls(Shape s, int n, Object[] nonNull) {
        JTable.Plain t = new JTable.Plain(n);
        Object[] vs = t.values;
        System.arraycopy(nonNull, 0, vs, 0, nonNull.length);
        t.shape = s;
        return t;
    }

    /** All values null: an empty table - a JTable.Bare (24 bytes), which grows through the overflow like a layout table. */
    static Object withNulls0(Shape s) {
        return new JTable.Bare(s);
    }

    static Object withNulls1(Shape s, int n, Object a) {
        JTable.Plain t = new JTable.Plain(n);
        t.values[0] = a;
        t.shape = s;
        return t;
    }

    static Object withNulls2(Shape s, int n, Object a, Object b) {
        JTable.Plain t = new JTable.Plain(n);
        Object[] vs = t.values;
        vs[0] = a;
        vs[1] = b;
        t.shape = s;
        return t;
    }

    /** build() without a layout class: a Plain typed by the values (the same array handling as JTable.literal). */
    static Object plain(Shape typed, Object[] vals) {
        return new JTable.Plain(typed, vals.length >= 4 ? vals : java.util.Arrays.copyOf(vals, 4));
    }
}
