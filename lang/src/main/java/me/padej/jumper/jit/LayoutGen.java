package me.padej.jumper.jit;

import me.padej.jumper.ast.VarType;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Shape;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layout class for a literal shape: a generated {@code Lay<n> extends JTable.Fixed} whose slots
 * are real fields, the way {@link InstanceGen} does it for script classes.
 *
 * <p>Why. A {@code {}}-table built from a literal was a {@code Plain}: the object, a {@code long[]}
 * for the primitive slots and an {@code Object[]} for the rest - three allocations, and every
 * field access under a region guard was {@code prims[3]}: a dependent load plus a bounds check on
 * top of the {@code getfield} Java does. With the slots as fields the region's access sites
 * ({@link RegionSite}) link to plain field getters and setters, and a literal is one allocation.
 * Measured on the pattern of object_fields: arrays with constant indices 123 ms, fields 93 ms,
 * Java 62 ms.
 *
 * <p>The layout is a property of the typed shape a literal produces ({@link Shape#typedBy},
 * {@link Shape#withReps}) and is cached in it, so every literal with the same keys and value
 * representations shares one class - from the interpreter and from compiled code alike. Field
 * {@code p<i>} holds primitive slot i in its own JVM type ({@link #fdesc}), {@code o<i>} the
 * reference of slot i; a slot that later loses its primitive representation, and every key added
 * after the literal, lives in the overflow array of {@link JTable.Fixed}, so the class stays exact
 * for the literal and correct for whatever happens to the table afterwards.
 *
 * <p>The classes live in {@link LayoutLoader}, the parent of every {@link ScriptLoader}: shapes
 * are global, so a layout must be reachable from the compiled code of every interpreter. They are
 * never unloaded - neither are shapes.
 *
 * <p>Disabled with {@code -Djmp.layout=0}: literals are then Plain tables again.
 */
public final class LayoutGen {
    private LayoutGen() {}

    public static final boolean ENABLED = me.padej.jumper.runtime.Opts.on("layout");

    private static final String JTABLE = "me/padej/jumper/runtime/JTable";
    private static final String LEAN = "me/padej/jumper/runtime/JTable$Lean";
    private static final String FULL = "me/padej/jumper/runtime/JTable$Full";
    private static final String SHAPE = "me/padej/jumper/runtime/Shape";
    private static final String OBJ_D = "Ljava/lang/Object;";
    /** Shape.layout value for shapes that get no class. */
    private static final Object NONE = new Object();

    /**
     * What a generated layout knows about itself. A layout has up to two classes with the same slot
     * fields: the Lean one every literal starts with (no store field - JTable.Lean), and, once a Lean
     * table of this layout has outgrown its fields, a Full one (JTable.Full) that literals build from
     * then on. Compiled literals and LitSite reach the constructor through {@link #makeSite}, which is
     * relinked at that moment.
     */
    public static final class Layout {
        /** The class literals build now: Lean until a table grows, then Full. */
        public volatile Class<?> cls;
        final Shape typed;
        /** Slot representation by index: a primitive kind for {@code p<i>}, DYN for {@code o<i>}. */
        public final VarType[] reps;
        /** {@code <init>(Shape, <slot>...)} descriptor: the slots in order, bits as J, references as Object. */
        public final String ctorDesc;
        /**
         * {@code static make(Shape, <slot>...)JTable} - what compiled literals call. A factory rather
         * than new/dup/invokespecial in the caller: a value with a branch in it (`d: (i & 1) == 0`) would
         * leave an uninitialized object on the stack across the branch, which the stack maps do not describe.
         */
        public final String makeDesc;
        /** The current class's make: an invokedynamic in compiled literals binds to this site. */
        public final MutableCallSite makeSite;
        /** {@code (Shape, Object[])JTable} of the current class - the constructor from evaluated values, for the interpreter. */
        volatile MethodHandle create;
        private boolean full;

        Layout(Shape typed, Class<?> cls, VarType[] reps, String ctorDesc, MethodHandle make, MethodHandle create) {
            this.typed = typed;
            this.cls = cls;
            this.reps = reps;
            this.ctorDesc = ctorDesc;
            this.makeDesc = ctorDesc.substring(0, ctorDesc.length() - 1) + "L" + JTABLE + ";";
            this.makeSite = new MutableCallSite(make);
            this.create = create;
        }

        /** Name of the field holding slot i. */
        public String field(int i) {
            return (reps[i].isPrimitive() ? "p" : "o") + i;
        }

        public String fieldDesc(int i) {
            return fdesc(reps[i]);
        }

        /** A Lean table of this layout needed a store: from now on literals build the Full class. */
        synchronized void grow() {
            if (full) return;
            full = true;
            try {
                Class<?> c = generate(typed, reps, FULL, this);
                MethodHandle make = Jit.LOOKUP.findStatic(c, "make", makeSite.type());
                create = Jit.LOOKUP.findStatic(c, "create", MethodType.methodType(JTable.class, Shape.class, Object[].class));
                cls = c;
                makeSite.setTarget(make);
                if (Jit.DEBUG) System.err.println("[layout] " + typed + " grew: literals now build " + c.getName());
            } catch (Throwable t) {
                if (Jit.DEBUG) System.err.println("[layout] " + typed + ": no Full class: " + t);
            }
        }
    }

    static {
        JTable.Lean.grown = c -> {
            Layout l = LayoutGen.BY_CLASS.get(c);
            if (l != null) l.grow();
        };
    }

    /** invokedynamic bootstrap for a compiled literal: K[kIdx] is the typed shape, the site is its layout's makeSite. */
    public static java.lang.invoke.CallSite bootstrapMake(MethodHandles.Lookup lookup, String name, MethodType type, int kIdx) throws Throwable {
        Object[] k = (Object[]) lookup.findStaticGetter(lookup.lookupClass(), "K", Object[].class).invoke();
        Layout l = of((Shape) k[kIdx]);
        return l.makeSite;
    }

    private static final Map<Class<?>, Layout> BY_CLASS = new ConcurrentHashMap<>();
    private static int seq;

    /** The layout of a typed shape, generated on first request; null when the shape gets none. */
    public static Layout of(Shape typed) {
        if (!ENABLED || typed == null) return null;
        Object l = typed.layout;
        if (l == null) l = decide(typed);
        return l == NONE ? null : (Layout) l;
    }

    /** The layout of a generated class, or null for any other table class. */
    public static Layout forClass(Class<?> c) {
        return BY_CLASS.get(c);
    }

    private static synchronized Object decide(Shape typed) {
        Object l = typed.layout;
        if (l != null) return l;
        try {
            l = typed.sealed() || typed.size() == 0 || typed.size() > Shape.MAX_KEYS ? NONE : define(typed);
        } catch (Throwable t) {
            if (Jit.DEBUG) System.err.println("[layout] " + typed + " not generated: " + t);
            l = NONE;
        }
        typed.layout = l;
        return l;
    }

    /** A table of this layout from the literal's evaluated values (the shape is the typed one). */
    public static JTable create(Object layout, Shape typed, Object[] values) {
        try {
            return (JTable) ((Layout) layout).create.invokeExact(typed, values);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static Layout define(Shape typed) throws Throwable {
        int n = typed.size();
        VarType[] reps = new VarType[n];
        for (int i = 0; i < n; i++) {
            VarType t = typed.typeAt(i);
            reps[i] = t.isPrimitive() ? t : VarType.DYN;
        }
        Class<?> c = generate(typed, reps, LEAN, null);
        MethodHandle create = Jit.LOOKUP.findStatic(c, "create", MethodType.methodType(JTable.class, Shape.class, Object[].class));
        String ctorDesc = ctorDesc(reps);
        MethodHandle make = Jit.LOOKUP.findStatic(c, "make",
                MethodType.fromMethodDescriptorString(ctorDesc.substring(0, ctorDesc.length() - 1) + "L" + JTABLE + ";", c.getClassLoader()));
        Layout l = new Layout(typed, c, reps, ctorDesc, make, create);
        BY_CLASS.put(c, l);
        return l;
    }

    private static String ctorDesc(VarType[] reps) {
        StringBuilder cd = new StringBuilder("(L").append(SHAPE).append(';');
        for (VarType r : reps) cd.append(r.isPrimitive() ? "J" : OBJ_D);
        return cd.append(")V").toString();
    }

    /** One class of a layout: the slot fields over the given base (JTable.Lean or JTable.Full). */
    private static Class<?> generate(Shape typed, VarType[] reps, String sup, Layout owner) throws Throwable {
        int n = reps.length;
        String name = Jit.PKG + "Lay" + (seq++);
        ClassBuilder cb = new ClassBuilder(name, sup);
        for (int i = 0; i < n; i++) cb.addField(0x0001, (reps[i].isPrimitive() ? "p" : "o") + i, fdesc(reps[i]));
        String ctorDesc = ctorDesc(reps);

        genInit(cb, sup, name, reps, ctorDesc);
        genMake(cb, name, reps, ctorDesc);
        genCreate(cb, name, reps, ctorDesc);
        genBitsAt(cb, sup, name, reps);
        genSetBits(cb, sup, name, reps);
        genRefAt(cb, sup, name, reps);
        genSetRef(cb, sup, name, reps);
        genFieldAccessors(cb, name, reps);

        byte[] bytes = cb.toBytes();
        Jit.dump(name, bytes);
        Class<?> c = LayoutLoader.INSTANCE.define(name, bytes);
        if (owner != null) BY_CLASS.put(c, owner);
        if (Jit.DEBUG) System.err.println("[layout] " + typed + " -> " + c.getName() + " extends " + sup.substring(sup.lastIndexOf('$') + 1) + " (" + bytes.length + " bytes)");
        return c;
    }

    // ---------- methods ----------

    /** {@code <init>(Shape, slots...)}: the constructor compiled literals call, values in slot order. */
    private static void genInit(ClassBuilder cb, String sup, String name, VarType[] reps, String ctorDesc) {
        List<String> params = new java.util.ArrayList<>(List.of(name, SHAPE));
        for (VarType r : reps) params.add(r.isPrimitive() ? "J" : "java/lang/Object");
        Code k = new Code(cb.cp, params);
        k.aload(0);
        k.aload(1);
        k.invokespecial(sup, "<init>", "(L" + SHAPE + ";)V");
        int local = 2;
        for (int i = 0; i < reps.length; i++) {
            k.aload(0);
            if (reps[i].isPrimitive()) { k.lload(local); local += 2; bitsToField(k, reps[i]); }
            else { k.aload(local); local += 1; }
            k.putfield(name, (reps[i].isPrimitive() ? "p" : "o") + i, fdesc(reps[i]));
        }
        k.vreturn();
        cb.addMethod(0x0001, "<init>", ctorDesc, k.finish());
    }

    /** {@code static JTable make(Shape, slots...)}: the constructor behind a static call, for compiled literals. */
    private static void genMake(ClassBuilder cb, String name, VarType[] reps, String ctorDesc) {
        List<String> params = new java.util.ArrayList<>(List.of(SHAPE));
        for (VarType r : reps) params.add(r.isPrimitive() ? "J" : "java/lang/Object");
        Code k = new Code(cb.cp, params);
        k.newObj(name);
        k.dup();
        k.aload(0);
        int local = 1;
        for (VarType r : reps) {
            if (r.isPrimitive()) { k.lload(local); local += 2; }
            else { k.aload(local); local += 1; }
        }
        k.invokespecial(name, "<init>", ctorDesc);
        k.areturn();
        cb.addMethod(0x0009, "make", ctorDesc.substring(0, ctorDesc.length() - 1) + "L" + JTABLE + ";", k.finish());
    }

    /** {@code static JTable create(Shape, Object[])}: from boxed values, each cast to the kind its slot was typed by. */
    private static void genCreate(ClassBuilder cb, String name, VarType[] reps, String ctorDesc) {
        Code k = new Code(cb.cp, List.of(SHAPE, "[Ljava/lang/Object;"));
        k.newObj(name);
        k.dup();
        k.aload(0);
        for (int i = 0; i < reps.length; i++) {
            k.aload(1);
            k.iconst(i);
            k.aaload();
            switch (reps[i]) {
                case INT -> { k.checkcast("java/lang/Integer"); k.invokevirtual("java/lang/Integer", "intValue", "()I"); k.i2l(); }
                case LONG -> { k.checkcast("java/lang/Long"); k.invokevirtual("java/lang/Long", "longValue", "()J"); }
                case DOUBLE -> { k.checkcast("java/lang/Double"); k.invokevirtual("java/lang/Double", "doubleValue", "()D"); k.invokestatic("java/lang/Double", "doubleToRawLongBits", "(D)J"); }
                case BOOLEAN -> { k.checkcast("java/lang/Boolean"); k.invokevirtual("java/lang/Boolean", "booleanValue", "()Z"); k.i2l(); }
                default -> { }
            }
        }
        k.invokespecial(name, "<init>", ctorDesc);
        k.areturn();
        cb.addMethod(0x0009, "create", "(L" + SHAPE + ";[" + OBJ_D + ")L" + JTABLE + ";", k.finish());
    }

    private static void genBitsAt(ClassBuilder cb, String sup, String name, VarType[] reps) {
        Code k = new Code(cb.cp, List.of(name, "I"));
        for (int i = 0; i < reps.length; i++) {
            if (!reps[i].isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.getfield(name, "p" + i, fdesc(reps[i]));
            fieldToBits(k, reps[i]);
            k.lreturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.invokespecial(sup, "bitsAt", "(I)J");
        k.lreturn();
        cb.addMethod(0x0001, "bitsAt", "(I)J", k.finish());
    }

    private static void genSetBits(ClassBuilder cb, String sup, String name, VarType[] reps) {
        Code k = new Code(cb.cp, List.of(name, "I", "J"));
        for (int i = 0; i < reps.length; i++) {
            if (!reps[i].isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.lload(2);
            bitsToField(k, reps[i]);
            k.putfield(name, "p" + i, fdesc(reps[i]));
            k.vreturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.lload(2);
        k.invokespecial(sup, "setBits", "(IJ)V");
        k.vreturn();
        cb.addMethod(0x0001, "setBits", "(IJ)V", k.finish());
    }

    private static void genRefAt(ClassBuilder cb, String sup, String name, VarType[] reps) {
        Code k = new Code(cb.cp, List.of(name, "I"));
        for (int i = 0; i < reps.length; i++) {
            if (reps[i].isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.getfield(name, "o" + i, OBJ_D);
            k.areturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.invokespecial(sup, "refAt", "(I)" + OBJ_D);
        k.areturn();
        cb.addMethod(0x0001, "refAt", "(I)" + OBJ_D, k.finish());
    }

    private static void genSetRef(ClassBuilder cb, String sup, String name, VarType[] reps) {
        Code k = new Code(cb.cp, List.of(name, "I", "java/lang/Object"));
        for (int i = 0; i < reps.length; i++) {
            if (reps[i].isPrimitive()) continue;
            Code.Label next = k.label();
            k.iload(1);
            k.iconst(i);
            k.if_icmpne(next);
            k.aload(0);
            k.aload(2);
            k.putfield(name, "o" + i, OBJ_D);
            k.vreturn();
            k.mark(next);
        }
        k.aload(0);
        k.iload(1);
        k.aload(2);
        k.invokespecial(sup, "setRef", "(I" + OBJ_D + ")V");
        k.vreturn();
        cb.addMethod(0x0001, "setRef", "(I" + OBJ_D + ")V", k.finish());
    }

    /**
     * Typed {@code static <k> get<i>(Object)} / {@code static void set<i>(Object, <k>)} for every
     * primitive slot, generated directly in the layout class: a single {@code getfield} plus the
     * bits<->value conversion, nothing else. {@link RegionSite} looks these up with
     * {@code findStatic} and uses them as the region's access-site target as is.
     *
     * <p>Why not a getter {@link MethodHandle} composed from {@code Lookup.findGetter} and a
     * separate bits-conversion filter (the first version of this): two combinators per access
     * roughly doubled the {@link java.lang.invoke.LambdaForm} the JIT has to inline at the region's
     * call site, and a hot loop that touches several fields of two receivers (nbody's inner loop -
     * seven fields, two guards) blew C2's inlining budget for a good half of the sites, each then
     * falling back to a real call per iteration: nbody 105 ms with the guard alone, 2200+ ms once
     * this loop had it linked to a layout class. A single static method compiles exactly like the
     * corresponding {@code Plain} path ({@code RegionSite.getD/setD}), so it inlines the same way.
     */
    private static void genFieldAccessors(ClassBuilder cb, String name, VarType[] reps) {
        for (int i = 0; i < reps.length; i++) {
            VarType k = reps[i];
            if (!k.isPrimitive()) {
                // Reference slot: a plain static getfield, the counterpart of the primitive
                // get<i> below - see Indy.installTableFunction for why this exists (a shared
                // guard/target that reads a receiver's slot through JTable.refAt is a virtual
                // call whose inline cache is shared by every table-function site in the program;
                // once two different concrete classes (Plain, a layout class, or two layout
                // classes) are both live, it goes megamorphic everywhere, not just at sites
                // that are themselves polymorphic. A static method bound to this one concrete
                // class, exactly like get<i>, keeps it a plain field read no matter what else
                // is going on at other call sites.
                Code g = new Code(cb.cp, List.of("java/lang/Object"));
                g.aload(0);
                g.checkcast(name);
                g.getfield(name, "o" + i, OBJ_D);
                g.areturn();
                cb.addMethod(0x0009, "getRef" + i, "(" + OBJ_D + ")" + OBJ_D, g.finish());
                continue;
            }
            String d = Compiler.desc(k);

            Code g = new Code(cb.cp, List.of("java/lang/Object"));
            g.aload(0);
            g.checkcast(name);
            g.getfield(name, "p" + i, fdesc(k));
            ret(g, k);
            cb.addMethod(0x0009, "get" + i, "(" + OBJ_D + ")" + d, g.finish());

            Code s = new Code(cb.cp, List.of("java/lang/Object", jvmType(k)));
            s.aload(0);
            s.checkcast(name);
            s.load(1);
            s.putfield(name, "p" + i, fdesc(k));
            s.vreturn();
            cb.addMethod(0x0009, "set" + i, "(" + OBJ_D + d + ")V", s.finish());
        }
    }

    /**
     * Field type of a slot: a primitive slot is stored in its own JVM type, not as long bits - an int
     * slot is 4 bytes, not 8 (object_alloc_churn: `{ a: i % 1000, b: i % 7 }` was 48 bytes per table
     * against 32 for the typed class instance, and allocation is all that benchmark does). bitsAt/
     * setBits and the constructor keep the bits interface and convert at the field.
     */
    static String fdesc(VarType r) {
        return switch (r) { case INT -> "I"; case BOOLEAN -> "Z"; case LONG -> "J"; case DOUBLE -> "D"; default -> OBJ_D; };
    }

    private static void bitsToField(Code k, VarType t) {
        switch (t) {
            case INT, BOOLEAN -> k.l2i();
            case LONG -> { }
            case DOUBLE -> k.invokestatic("java/lang/Double", "longBitsToDouble", "(J)D");
            default -> throw new IllegalStateException();
        }
    }

    private static void fieldToBits(Code k, VarType t) {
        switch (t) {
            case INT, BOOLEAN -> k.i2l();
            case LONG -> { }
            case DOUBLE -> k.invokestatic("java/lang/Double", "doubleToRawLongBits", "(D)J");
            default -> throw new IllegalStateException();
        }
    }

    private static String jvmType(VarType k) {
        return switch (k) { case LONG -> "J"; case DOUBLE -> "D"; default -> "I"; };   // Code's constructor param type: int covers INT/BOOLEAN
    }

    private static void ret(Code k, VarType t) {
        switch (t) {
            case INT, BOOLEAN -> k.ireturn();
            case LONG -> k.lreturn();
            case DOUBLE -> k.dreturn();
            default -> throw new IllegalStateException();
        }
    }

    /** Method handles of the typed accessors {@link #genFieldAccessors} generated, or null for a non-primitive/out-of-range slot. */
    static MethodHandle getter(Class<?> c, int i) throws ReflectiveOperationException {
        Layout l = BY_CLASS.get(c);
        if (l == null || i >= l.reps.length || !l.reps[i].isPrimitive()) return null;
        VarType k = l.reps[i];
        Class<?> r = k == VarType.LONG ? long.class : k == VarType.DOUBLE ? double.class : k == VarType.BOOLEAN ? boolean.class : int.class;
        return Jit.LOOKUP.findStatic(c, "get" + i, MethodType.methodType(r, Object.class));
    }

    static MethodHandle setter(Class<?> c, int i) throws ReflectiveOperationException {
        Layout l = BY_CLASS.get(c);
        if (l == null || i >= l.reps.length || !l.reps[i].isPrimitive()) return null;
        VarType k = l.reps[i];
        Class<?> r = k == VarType.LONG ? long.class : k == VarType.DOUBLE ? double.class : k == VarType.BOOLEAN ? boolean.class : int.class;
        return Jit.LOOKUP.findStatic(c, "set" + i, MethodType.methodType(void.class, Object.class, r));
    }

    /** {@code static Object getRef<i>(Object)} for a reference slot - {@link #getter} for non-primitives. */
    static MethodHandle refGetter(Class<?> c, int i) throws ReflectiveOperationException {
        Layout l = BY_CLASS.get(c);
        if (l == null || i >= l.reps.length || l.reps[i].isPrimitive()) return null;
        return Jit.LOOKUP.findStatic(c, "getRef" + i, MethodType.methodType(Object.class, Object.class));
    }
}
