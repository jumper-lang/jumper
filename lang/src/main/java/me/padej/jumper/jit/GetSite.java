package me.padej.jumper.jit;

import me.padej.jumper.ast.VarType;
import me.padej.jumper.runtime.FieldCache;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Ops;
import me.padej.jumper.runtime.Shape;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

/**
 * Polymorphic inline cache for a field read {@code obj.name} in Tier 1 outside a guarded region:
 * an {@code invokedynamic (Object)R} whose target is a chain of up to {@link #MAX_DEPTH} layers,
 * one per (receiver class, shape) actually seen, each a constant-index read of that class's slot.
 *
 * <p>What it replaces: {@code Ops.memberGet(obj, FieldCache)}, a <i>monomorphic</i> cache shared by
 * the interpreter, with two costs Tier 1 cannot afford:
 * <ul>
 * <li>A site that sees two shapes thrashes it. {@code binary_trees/dyn}: {@code n.left} alternates
 * between an inner node and a leaf (whose literal {@code { left: null, right: null }} has no keys at
 * all - null does not create a slot), so every other read was a miss: {@code Shape.indexOf} plus four
 * field writes into the cache, 15 million times per run. A leaf is now its own layer that answers
 * the constant {@code null} ("plain table, this shape has no such key") without touching the table.</li>
 * <li>The slot is read through {@code JTable.refAt/bitsAt}, one virtual method shared by every field
 * read in the program - megamorphic as soon as Plain and two layout classes are alive (the lesson of
 * stage 11, perf-plan-2). Here each layer reads through a getter bound to its own class: a
 * {@code getfield} of a layout class or {@code values[i]}/{@code prims[i]} of a Plain, with the
 * index a constant.</li>
 * </ul>
 *
 * <p>Typed sites ({@code (Object)I/J/D/Z}, when the surrounding expression is primitive) only link
 * layers whose conversion is exactly what {@code Ops.memberGetInt}/... would do (same kind, a widening,
 * or unboxing a reference slot through the same {@code Ops.unbox*}); anything else stays on the generic
 * path. Sealed shapes (script-class instances), dictionaries and Java objects are never linked: they
 * keep the old path, and a site that keeps seeing them goes generic for good.
 */
public final class GetSite {
    static final int MAX_DEPTH = 8;
    /** Misses on receivers that cannot be linked (Java objects, dictionaries, class instances) before giving up. */
    private static final int MAX_FOREIGN = 8;

    private static final MethodHandle LINK, IS, GET_REF_PLAIN, GET_OVERFLOW,
            PLAIN_I, PLAIN_L, PLAIN_D, PLAIN_Z,
            MEMBER_GET, MEMBER_GET_I, MEMBER_GET_L, MEMBER_GET_D, MEMBER_GET_Z,
            UNBOX_I, UNBOX_L, UNBOX_D, TRUTHY;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            LINK = l.findVirtual(GetSite.class, "link", MethodType.methodType(void.class, Object.class));
            IS = l.findStatic(GetSite.class, "is", MethodType.methodType(boolean.class, Class.class, Shape.class, Object.class));
            GET_REF_PLAIN = l.findStatic(GetSite.class, "refPlain", MethodType.methodType(Object.class, int.class, Object.class));
            GET_OVERFLOW = l.findStatic(GetSite.class, "overflow", MethodType.methodType(Object.class, int.class, Object.class));
            PLAIN_I = l.findStatic(RegionSite.class, "getI", MethodType.methodType(int.class, int.class, Object.class));
            PLAIN_L = l.findStatic(RegionSite.class, "getL", MethodType.methodType(long.class, int.class, Object.class));
            PLAIN_D = l.findStatic(RegionSite.class, "getD", MethodType.methodType(double.class, int.class, Object.class));
            PLAIN_Z = l.findStatic(RegionSite.class, "getZ", MethodType.methodType(boolean.class, int.class, Object.class));
            MethodType fc = MethodType.methodType(Object.class, Object.class, FieldCache.class);
            MEMBER_GET = l.findStatic(Ops.class, "memberGet", fc);
            MEMBER_GET_I = l.findStatic(Ops.class, "memberGetInt", fc.changeReturnType(int.class));
            MEMBER_GET_L = l.findStatic(Ops.class, "memberGetLong", fc.changeReturnType(long.class));
            MEMBER_GET_D = l.findStatic(Ops.class, "memberGetDouble", fc.changeReturnType(double.class));
            MEMBER_GET_Z = l.findStatic(Ops.class, "memberGetBool", fc.changeReturnType(boolean.class));
            UNBOX_I = l.findStatic(Ops.class, "unboxInt", MethodType.methodType(int.class, Object.class));
            UNBOX_L = l.findStatic(Ops.class, "unboxLong", MethodType.methodType(long.class, Object.class));
            UNBOX_D = l.findStatic(Ops.class, "unboxDouble", MethodType.methodType(double.class, Object.class));
            TRUTHY = l.findStatic(Ops.class, "truthy", MethodType.methodType(boolean.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final FieldCache cache;
    private final MutableCallSite site;
    private final MethodType type;
    /** The old path, {@code Ops.memberGet*(obj, cache)}, in the site's type. */
    private final MethodHandle generic;
    private int depth, foreign;
    private boolean done;

    private GetSite(FieldCache cache, MethodType type) {
        this.cache = cache;
        this.type = type;
        Class<?> r = type.returnType();
        MethodHandle g = r == int.class ? MEMBER_GET_I : r == long.class ? MEMBER_GET_L : r == double.class ? MEMBER_GET_D
                : r == boolean.class ? MEMBER_GET_Z : MEMBER_GET;
        this.generic = MethodHandles.insertArguments(g, 1, cache).asType(type);
        this.site = new MutableCallSite(type);
        // bottom of the chain: try to add a layer for this receiver, then answer through the old path
        site.setTarget(MethodHandles.foldArguments(generic, LINK.bindTo(this)));
    }

    /** Bootstrap: the FieldCache of the Member node sits in the caller's K under kIdx. */
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type, int kIdx) throws Throwable {
        Object[] k = (Object[]) lookup.findStaticGetter(lookup.lookupClass(), "K", Object[].class).invoke();
        return new GetSite((FieldCache) k[kIdx], type).site;
    }

    /** No layer answered for this receiver: add one if it can be linked, or give up on the site. */
    synchronized void link(Object o) {
        if (done) return;
        if (!(o instanceof JTable.Plain || o instanceof JTable.Fixed) || ((JTable) o).cls() != null) {
            if (++foreign > MAX_FOREIGN) goGeneric("foreign receivers");
            return;
        }
        JTable t = (JTable) o;
        Shape s = t.shape;
        if (s == null || s.sealed() || s.store != null) {   // dictionary, class instance, or a private (grown Lean) shape
            if (++foreign > MAX_FOREIGN) goGeneric("dictionary or sealed receivers");
            return;
        }
        if (depth >= MAX_DEPTH) { goGeneric("megamorphic"); return; }
        MethodHandle read;
        try {
            read = layer(o.getClass(), s);
        } catch (ReflectiveOperationException e) {
            read = null;
        }
        if (read == null) {   // a slot this site type cannot read exactly - that shape stays on the old path
            if (++foreign > MAX_FOREIGN) goGeneric("unlinkable shapes");
            return;
        }
        MethodHandle test = MethodHandles.insertArguments(IS, 0, o.getClass(), s);
        depth++;
        site.setTarget(MethodHandles.guardWithTest(test, read.asType(type), site.getTarget()));
        if (Jit.DEBUG) System.err.println("[get] ." + cache.name + " -> " + o.getClass().getSimpleName() + " " + s + " (depth " + depth + ")");
    }

    private void goGeneric(String why) {
        done = true;
        site.setTarget(generic);
        if (Jit.DEBUG) System.err.println("[get] ." + cache.name + " -> generic (" + why + ")");
    }

    /** The read for receivers of class c and shape s, returning the site's type, or null. */
    private MethodHandle layer(Class<?> c, Shape s) throws ReflectiveOperationException {
        Class<?> r = type.returnType();
        int i = s.indexOf(cache.name);
        if (i < 0) {
            // a plain table without the key reads as null; a typed site would have to unbox null - leave it
            return r == Object.class ? MethodHandles.dropArguments(MethodHandles.constant(Object.class, null), 0, Object.class) : null;
        }
        VarType rep = s.typeAt(i);
        MethodHandle g;
        if (c == JTable.Plain.class) {
            g = switch (rep) {
                case INT -> PLAIN_I; case LONG -> PLAIN_L; case DOUBLE -> PLAIN_D; case BOOLEAN -> PLAIN_Z;
                default -> GET_REF_PLAIN;
            };
            g = MethodHandles.insertArguments(g, 0, i);
        } else if (rep.isPrimitive()) {
            g = LayoutGen.getter(c, i);
            if (g == null || g.type().returnType() != jvm(rep)) return null;
        } else {
            g = LayoutGen.refGetter(c, i);
            // no reference field of its own: beyond the layout, or a primitive field whose slot has
            // gone back to a reference - both live in the overflow array
            if (g == null) g = MethodHandles.insertArguments(GET_OVERFLOW, 0, i);
        }
        return convert(g, rep, r);
    }

    /** Adapt a slot read of representation rep to the site's return type, only where Ops.memberGet* agrees exactly. */
    private static MethodHandle convert(MethodHandle g, VarType rep, Class<?> r) {
        if (r == Object.class) return g;   // asType boxes exactly as VarType.fromBits: Integer, Long, Double, Boolean
        if (!rep.isPrimitive()) {
            MethodHandle u = r == int.class ? UNBOX_I : r == long.class ? UNBOX_L : r == double.class ? UNBOX_D : TRUTHY;
            return MethodHandles.filterReturnValue(g, u);
        }
        boolean ok = switch (rep) {
            case INT -> r == int.class || r == long.class || r == double.class;
            case LONG -> r == long.class || r == double.class;
            case DOUBLE -> r == double.class;
            case BOOLEAN -> r == boolean.class;
            default -> false;
        };
        return ok ? g : null;
    }

    private static Class<?> jvm(VarType k) {
        return switch (k) { case INT -> int.class; case LONG -> long.class; case DOUBLE -> double.class; case BOOLEAN -> boolean.class; default -> Object.class; };
    }

    /** Same test as RegionSite.is, but its own method: a shared static method shares its profile with every region guard. */
    static boolean is(Class<?> c, Shape s, Object o) {
        return o != null && o.getClass() == c && ((JTable) o).shape == s;
    }

    static Object refPlain(int i, Object o) { return ((JTable.Plain) o).values[i]; }

    static Object overflow(int i, Object o) {
        Object[] vs = ((JTable.Fixed) o).overflow();
        return i < vs.length ? vs[i] : null;
    }
}
