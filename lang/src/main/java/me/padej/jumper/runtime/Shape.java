package me.padej.jumper.runtime;

import me.padej.jumper.ast.VarType;

import java.util.HashMap;
import java.util.Map;

/**
 * Hidden class ("shape") of a table: an ordered set of keys -> slot indices in JTable.
 * Tables with the same key-insertion history share one shape, so the inline cache at an
 * access site `t.x` compares a single reference and reads the value by index.
 * Shapes form a tree: child(key) is the transition taken when a key is added.
 *
 * <h2>Slot representation</h2>
 *
 * A sealed shape (class instance) has its field types declared in the source, in {@link #types}.
 * A plain table declares nothing, and every slot used to be a reference: `{ x: 0.5 }` stored
 * a boxed Double, and `e.x += e.vx` unboxed two fields, added them and boxed the result back
 * on every step. On the suite this is the most expensive item of dynamic mode: `object_fields`
 * 26.8x, `game_tick` 38.8x, `nbody` 16.8x - all three are built on exactly this access pattern.
 *
 * <p>So a plain shape has a slot <b>representation</b>: {@link #reps}. A slot that received a
 * primitive while the literal was being built is declared primitive and lives in {@code JTable.prims}
 * unboxed. The {@link #retyped} transition is cached just like {@link #child}, so all tables
 * built from one literal converge to one shape and all inline caches keep hitting.
 *
 * <p>The representation is an assumption, not a declaration: storing a value of another kind
 * turns the slot back into a reference ({@code JTable.setSlot}). The transition is one-way,
 * primitive -> reference, so a shape cannot oscillate, and the semantics do not change: a slot
 * can still hold anything.
 *
 * <p>Disabled with {@code -Djmp.tableprims=0}.
 */
public final class Shape {
    /** Above this many keys the table switches to dictionary mode (HashMap). */
    public static final int MAX_KEYS = 64;

    /** Whether slots of plain tables get typed. */
    // Opts.on("tableprims") without loading Opts (its table of every option, descriptions included): Shape is
    // on the path of the first config read in a process, where every class loaded is time
    static final boolean TYPED = flag("jmp.tableprims", true);

    /** Opts.Opt.enabled for one property (the same values mean off); Opts.ALL lists it for the gate. */
    private static boolean flag(String property, boolean defaultOn) {
        String v = System.getProperty(property);
        if (v == null) return defaultOn;
        return !"0".equals(v) && !"off".equals(v) && !"false".equals(v);
    }

    private static final VarType[] NO_REPS = new VarType[0];

    public static final Shape ROOT = new Shape(new Object[0], NO_REPS, null, null);
    /** Sentinel for empty inline caches: never the shape of a real table. */
    public static final Shape NONE = new Shape(new Object[0], NO_REPS, null, null);

    final Shape parent;
    final Object key;
    final Object[] keys;   // all keys in insertion order; index = position
    final int size;
    /**
     * Sealed shape of a class instance: the field set is fixed, types[i] is the declared field type
     * (DYN/STRING live in values, primitives in prims). null for plain tables.
     */
    public final VarType[] types;
    /** Slot representations of a plain table (DYN = reference, primitive = raw bits). null for sealed shapes. */
    private final VarType[] reps;
    /** Whether there is at least one primitive slot - so prims is not allocated needlessly. */
    public final boolean anyPrim;
    /** Classes of class-typed fields (Classes.ClassNode), parallel to types; null when there are none. */
    public final Object[] classes;
    public final boolean hasPrims, hasRefs;
    /**
     * The storage of one JTable.Lean table that outgrew its fields: its overflow {@code Object[]}, or
     * its dictionary. Non-null only on a private shape ({@link #privateCopy}, {@link #dictShape}) -
     * a shape owned by a single table, which no inline cache links to.
     */
    public final Object store;
    private Map<Object, Integer> index; // lazy, for larger shapes
    private Map<Object, Shape> transitions;
    private Map<Integer, Shape> retypes;

    /** Plain shape: keys and representations given in full (parent/key are only for debugging the tree). */
    private Shape(Object[] keys, VarType[] reps, Shape parent, Object key) {
        this.parent = parent;
        this.key = key;
        this.store = null;
        this.keys = keys;
        this.size = keys.length;
        this.reps = reps;
        this.types = null;
        this.classes = null;
        this.hasPrims = false;
        this.hasRefs = true;
        boolean p = false;
        for (VarType t : reps) if (t != null && t.isPrimitive()) { p = true; break; }
        this.anyPrim = p;
    }

    private Shape(String[] names, VarType[] types, Object[] classes) {
        this.parent = null;
        this.key = null;
        this.store = null;
        this.keys = names.clone();
        this.size = names.length;
        this.reps = null;
        this.types = types.clone();
        boolean p = false, r = false, c = false;
        for (VarType t : types) { if (t.isPrimitive()) p = true; else r = true; }
        for (Object k : classes) if (k != null) c = true;
        this.classes = c ? classes.clone() : null;
        this.hasPrims = p;
        this.hasRefs = r;
        this.anyPrim = p;
    }

    /** A private copy of a plain shape: the same keys and representations, holding one table's store. */
    private Shape(Shape of, Object store) {
        this.parent = of.parent;
        this.key = of.key;
        this.store = store;
        this.keys = of.keys;
        this.size = of.size;
        this.reps = of.reps;
        this.types = of.types;
        this.classes = of.classes;
        this.hasPrims = of.hasPrims;
        this.hasRefs = of.hasRefs;
        this.anyPrim = of.anyPrim;
        this.layout = null;
    }

    /** This shape as a private one holding `store` (see {@link #store}). */
    public Shape privateCopy(Object store) {
        return new Shape(this, store);
    }

    /**
     * The shape of a JTable.Lean table in dictionary mode: no keys, sealed (so no cache ever links it),
     * holding the dictionary. A Plain in dictionary mode has a null shape instead; a Lean has no other
     * place to keep its dictionary.
     */
    public static Shape dictShape(java.util.LinkedHashMap<Object, Object> dict) {
        return new Shape(DICT_BASE, dict);
    }

    private static final Shape DICT_BASE = new Shape(new String[0], new VarType[0], new Object[0]);

    /** Shape of class instances: not part of the transition tree, no keys can be added. */
    public static Shape forClass(String[] names, VarType[] types, Object[] classes) {
        return new Shape(names, types, classes);
    }

    /** Field class (Classes.ClassNode) or null. */
    public Object classAt(int i) {
        return classes == null ? null : classes[i];
    }

    public boolean sealed() {
        return types != null;
    }

    /** Field type of a sealed shape, or slot representation of a plain table. */
    public VarType typeAt(int i) {
        VarType[] t = types;
        if (t != null) return t[i];
        VarType r = reps[i];
        return r == null ? VarType.DYN : r;
    }

    public int size() {
        return size;
    }

    public Object keyAt(int i) {
        return keys[i];
    }

    /** Key index or -1. Small shapes use a linear scan (faster than hashing), large ones a HashMap. */
    public int indexOf(Object k) {
        if (size <= 8) {
            Object[] ks = keys;
            for (int i = 0; i < ks.length; i++) {
                Object x = ks[i];
                if (x == k || x.equals(k)) return i;
            }
            return -1;
        }
        Map<Object, Integer> ix = index;
        if (ix == null) {
            ix = new HashMap<>(size * 2);
            for (int i = 0; i < size; i++) ix.put(keys[i], i);
            index = ix;
        }
        Integer i = ix.get(k);
        return i == null ? -1 : i;
    }

    /** Transition: the shape with one more key. Cached so that all tables with the same history share the shape. */
    public synchronized Shape child(Object k) {
        if (types != null) throw new IllegalStateException("sealed shape");
        Map<Object, Shape> tr = transitions;
        if (tr == null) transitions = tr = new HashMap<>(4);
        Shape s = tr.get(k);
        if (s == null) {
            Object[] nk = new Object[size + 1];
            System.arraycopy(keys, 0, nk, 0, size);
            nk[size] = k;
            VarType[] nr = new VarType[size + 1];
            System.arraycopy(reps, 0, nr, 0, size);
            s = new Shape(nk, nr, this, k);
            tr.put(k, s);
        }
        return s;
    }

    /**
     * Transition: the same shape but slot i has representation t. Cached just like child -
     * otherwise every table built from a literal would get its own shape and no inline cache
     * would ever hit twice, i.e. the cure would be worse than the disease.
     */
    synchronized Shape retyped(int i, VarType t) {
        if (types != null) throw new IllegalStateException("sealed shape");
        if (typeAt(i) == t) return this;
        Integer k = i * 16 + t.ordinal();
        Map<Integer, Shape> tr = retypes;
        if (tr == null) retypes = tr = new HashMap<>(4);
        Shape s = tr.get(k);
        if (s == null) {
            VarType[] nr = reps.clone();
            nr[i] = t;
            s = new Shape(keys, nr, parent, key);
            tr.put(k, s);
        }
        return s;
    }

    /**
     * The shape a literal's table ends up with when its values have these representations
     * (null = reference): the same transitions, in the same order, that {@code JTable.Plain.typeSlots}
     * applies at run time, so a table built by compiled code and one built by the interpreter
     * from the same literal share one shape object.
     */
    public Shape withReps(VarType[] reps) {
        if (!TYPED) return this;
        Shape s = this;
        for (int i = 0; i < reps.length; i++) if (reps[i] != null) s = s.retyped(i, reps[i]);
        return s;
    }

    /**
     * The shape of a literal's table given its values: every slot holding a primitive is declared
     * primitive (the same transitions, in the same order, as {@link #withReps}). Done once, while
     * the literal is built; a key added later stays a reference (see {@code JTable.Plain.typeSlots}).
     */
    public Shape typedBy(Object[] values) {
        Shape s = this;
        int n = size;
        for (int i = 0; i < n; i++) {
            VarType t = repOf(values[i]);
            if (t != null) s = s.retyped(i, t);
        }
        return s;
    }

    /**
     * Layout of this shape's tables (jit/LayoutGen.Layout), a sentinel for "none", or null while
     * undecided. Owned by LayoutGen; kept here so the lookup is one field read.
     */
    public volatile Object layout;

    /**
     * The script class whose instances (or statics table) have this sealed shape; null for every plain
     * shape. A class's shape is created per JClass, so this identifies the class: JTable.cls() reads it
     * here instead of every instance carrying its own reference.
     */
    public JClass owner;
    /** owner.layout, copied here so a method call's class guard is two loads (shape, this) - see Indy.classGuard. */
    public Object ownerLayout;
    /** owner.node (the class declaration), for the exact-class fast path of Ops.checkClass. */
    public Object ownerNode;

    /** Representation that can hold this value unboxed, or null. */
    static VarType repOf(Object v) {
        if (v instanceof Integer) return VarType.INT;
        if (v instanceof Double) return VarType.DOUBLE;
        if (v instanceof Long) return VarType.LONG;
        if (v instanceof Boolean) return VarType.BOOLEAN;
        return null;
    }

    @Override
    public String toString() {
        return "Shape" + java.util.Arrays.toString(keys);
    }
}
