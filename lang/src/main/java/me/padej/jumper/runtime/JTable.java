package me.padej.jumper.runtime;

import me.padej.jumper.ast.VarType;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Jumper table (the analog of a Lua table for string/arbitrary keys).
 *
 * Two modes:
 *  - shape mode: keys are described by a shape (hidden class), values sit in an array by index -
 *    a field access through an inline cache costs one reference comparison and one array read;
 *  - dictionary mode (dict != null): a LinkedHashMap - for dictionary-like tables with hundreds
 *    of keys and after a key removal. Insertion order is preserved in both modes.
 */
public class JTable {
    // ---------- representation ----------
    //
    // The base class is deliberately thin: it has only `shape` (the class is the sealed shape's owner). The field storage - the
    // values/prims arrays and the dictionary - lives in the Plain subclass (plain tables and
    // instances without a generated class), so a script class instance with real fields
    // (jit/InstanceGen, `Inst_P extends JTable`) does not drag three empty references along:
    // `P { int a, b }` was 40 bytes vs 24 for java, now 32. On object_alloc_churn/binary_trees
    // this is the whole difference - objects there live long, and what is paid is allocation
    // traffic rather than constructor time.
    //
    // Storage is accessed through four virtual methods bitsAt/setBits/refAt/setRef plus dict():
    // the shape guard (`t.shape == shape`) stays valid for every representation, and inline caches
    // know nothing about the representation. The Tier 1 hot path does not come here - it emits getfield directly.

    /**
     * Instance class (null for plain tables). Not a field: it is the owner of the sealed shape
     * (Shape.owner), so a class instance is its header, its shape and its fields - 4 bytes less, which
     * for a two-int instance is the difference between 32 bytes and Java's 24.
     */
    public final JClass cls() {
        Shape s = shape;
        return s == null ? null : s.owner;
    }
    /** null in dictionary mode. */
    public Shape shape = Shape.ROOT;

    protected JTable() {}

    /**
     * A script class instance whose field values live in the object itself - that is a
     * generated subclass (see jit/InstanceGen).
     *
     * <p>The second parameter only distinguishes the constructor from {@link Plain#Plain(JClass)};
     * its value is unused. The shape is the same as before, so all inline caches keep hitting.
     */
    protected JTable(JClass cls, boolean inlineFields) {
        this.shape = cls.shape;
    }

    // ---------- factories for plain tables ----------

    public static JTable plain() { return new Plain(); }
    public static JTable plain(int expected) { return new Plain(expected); }
    /**
     * For table literals: a ready shape and values (the array is taken over). The slots get typed
     * from the values, and when the typed shape has a layout class (jit/LayoutGen: the slots as
     * real fields of a generated class) the table is an instance of that class; otherwise a
     * {@link Plain} with arrays.
     */
    public static JTable literal(Shape shape, Object[] values) {
        Shape typed = Shape.TYPED ? shape.typedBy(values) : shape;
        Object layout = me.padej.jumper.jit.LayoutGen.of(typed);
        if (layout != null) return me.padej.jumper.jit.LayoutGen.create(layout, typed, values);
        return new Plain(typed, values);
    }
    /**
     * A table literal without a layout class: the same typed shape and slots as {@link #literal}, but
     * never a generated class - for data built once and read a few times (config files, interp.ConfigReader),
     * where generating and loading a class per distinct shape costs more than it could ever save.
     */
    public static JTable literalPlain(Shape shape, Object[] values) {
        return new Plain(literalShape(shape, values), values);
    }

    /** The shape a literal's table gets from its values (typed slots unless -Djmp.tableprims=0). */
    public static Shape literalShape(Shape shape, Object[] values) {
        return Shape.TYPED ? shape.typedBy(values) : shape;
    }

    /** A table literal on a shape already typed by {@link #literalShape} for exactly these values. */
    public static JTable plainTyped(Shape typed, Object[] values) {
        return new Plain(typed, values);
    }
    /** Array-backed class instance (when there is no generated class). */
    public static JTable instance(JClass cls) { return new Plain(cls); }
    /** Table of a class's static members: its own sealed shape. */
    public static JTable statics(Shape s, JClass owner) { return new Plain(s, owner); }

    // ---------- field storage (virtual) ----------

    /** Bits of a primitive field (as in Frame.p). */
    public long bitsAt(int i) { throw new IllegalStateException("no primitive slots"); }

    public void setBits(int i, long bits) { throw new IllegalStateException("no primitive slots"); }

    /** Value of a reference field as is, without conversion. */
    public Object refAt(int i) { throw new IllegalStateException("no reference slots"); }

    public void setRef(int i, Object v) { throw new IllegalStateException("no reference slots"); }

    /** The dictionary in dictionary mode, otherwise null. Always null for a class instance. */
    protected LinkedHashMap<Object, Object> dict() { return null; }

    /** Switch to dictionary mode; a class instance is sealed and never goes there. */
    protected void toDict() { throw new IllegalStateException("sealed instance"); }

    /** Write to a plain table's slot honoring the slot representation. */
    public void setSlot(int i, Object v) { setAt(i, v); }

    /** Add a new key via an already computed transition (for the write inline cache). */
    public void addNew(Shape next, Object value) { throw new IllegalStateException("sealed instance"); }

    /** Field value by shape index (primitives boxed). */
    public Object at(int i) {
        VarType t = shape.typeAt(i);
        return t.isPrimitive() ? t.fromBits(bitsAt(i)) : refAt(i);
    }

    /** Field write by shape index (coerced to the declared type). */
    public void setAt(int i, Object v) {
        Shape s = shape;
        VarType t = s.typeAt(i);
        if (t.isPrimitive()) setBits(i, t.toBits(v));
        else if (t == VarType.DYN) {
            Object k = s.classAt(i);
            setRef(i, k == null ? v : Ops.checkClass(v, (me.padej.jumper.ast.Classes.ClassNode) k));
        } else setRef(i, t.coerce(v));
    }

    public JmpError noField(Object key) {
        return new JmpError("No field '" + key + "' in " + cls().name);
    }

    public boolean isDict() {
        return dict() != null;
    }

    /** Value by key; on a class instance an unknown field yields null (for has/keys/iteration). */
    public Object get(Object key) {
        LinkedHashMap<Object, Object> d = dict();
        if (d != null) return d.get(key);
        int i = shape.indexOf(key);
        if (i < 0) return null;
        return at(i);
    }

    /** Field read from a script (`t.x`, `t["x"]`): on a class instance a field, a bound method or an error. */
    public Object getField(Object key) {
        if (cls() == null) return get(key);
        int i = shape.indexOf(key);
        if (i >= 0) return at(i);
        JFunction m = key instanceof String n ? cls().findMethod(n) : null;
        if (m != null) return JClass.bind(m, this);
        throw noField(key);
    }

    /** Insert into the dictionary part under the policy's table limit (a new key past the bound is rejected). */
    private void dictPut(Object key, Object value) {
        LinkedHashMap<Object, Object> d = dict();
        if (d.put(key, value) == null) {
            int limit = Access.tableLimit();
            if (d.size() > limit) { d.remove(key); throw Access.tableLimitExceeded(limit); }
        }
    }

    public void put(Object key, Object value) {
        if (key == null) throw new JmpError("Table key must not be null");
        LinkedHashMap<Object, Object> d = dict();
        if (d != null) {
            if (value == null) d.remove(key);
            else dictPut(key, value);
            return;
        }
        Shape s = shape;
        int i = s.indexOf(key);
        if (cls() != null) {
            if (i < 0) throw noField(key);
            setAt(i, value);
            return;
        }
        if (i >= 0) {
            if (value == null) { toDict(); dict().remove(key); return; }
            setSlot(i, value);
            return;
        }
        if (value == null) return;
        if (s.size() >= Shape.MAX_KEYS) { toDict(); dictPut(key, value); return; }
        addNew(s.child(key), value);
    }

    public boolean has(Object key) {
        return get(key) != null;
    }

    public int size() {
        LinkedHashMap<Object, Object> d = dict();
        return d != null ? d.size() : shape.size();
    }

    public Set<Object> keys() {
        LinkedHashMap<Object, Object> d = dict();
        if (d != null) return d.keySet();
        return new AbstractSet<>() {
            @Override
            public Iterator<Object> iterator() {
                Shape s = shape;
                return new Iterator<>() {
                    int i = 0;
                    @Override public boolean hasNext() { return i < s.size(); }
                    @Override public Object next() {
                        if (i >= s.size()) throw new NoSuchElementException();
                        return s.keyAt(i++);
                    }
                };
            }

            @Override
            public int size() {
                return shape.size();
            }
        };
    }

    /** Live view as a java.util.Map (switches the table to dictionary mode); a class instance gives a copy. */
    public Map<Object, Object> asMap() {
        if (cls() != null) {
            LinkedHashMap<Object, Object> d = new LinkedHashMap<>();
            for (int i = 0; i < shape.size(); i++) d.put(shape.keyAt(i), at(i));
            return d;
        }
        if (dict() == null) toDict();
        return dict();
    }

    @Override
    public String toString() {
        if (cls() != null) {
            JFunction ts = cls().findMethod("toString");
            if (ts != null) return Ops.str(ts.call(new Object[]{this}));
        }
        StringBuilder sb = new StringBuilder(cls() != null ? cls().name + "{" : "{");
        boolean first = true;
        for (Object k : keys()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(k).append(": ").append(Ops.str(get(k)));
        }
        return sb.append('}').toString();
    }

    // ==================================================================================
    // Plain table: values in arrays by shape index, or a dictionary.
    // Public because generated bytecode (another class loader) creates it.
    // ==================================================================================
    public static final class Plain extends JTable {
        private static final Object[] EMPTY = new Object[0];

        public Object[] values = EMPTY;
        /** Primitive slots (bits as in Frame.p, index = shape index); null when there are no primitives. */
        public long[] prims;
        private LinkedHashMap<Object, Object> dict;

        public Plain() {}

        /** A new plain table with the same shape and slots (the values themselves are shared). */
        public Plain copyStorage() {
            Plain c = new Plain();
            c.shape = shape;
            c.values = values.length == 0 ? values : values.clone();
            if (prims != null) c.prims = prims.clone();
            if (dict != null) c.dict = new LinkedHashMap<>(dict);
            return c;
        }

        /** The dictionary of a table in dictionary mode (null otherwise) - for copying its values. */
        public LinkedHashMap<Object, Object> dictionary() {
            return dict;
        }

        public Plain(int expected) {
            if (expected > Shape.MAX_KEYS) { dict = new LinkedHashMap<>(expected * 2); shape = null; }
            else if (expected > 0) values = new Object[expected];
        }

        /** For table literals: a shape already typed by the values ({@link Shape#typedBy}) and the values (the array is taken over). */
        public Plain(Shape shape, Object[] values) {
            this.shape = shape;
            this.values = values;
            // anyPrim, not hasPrims: hasPrims/hasRefs describe sealed (class) shapes only and are
            // hardcoded false/true on a plain shape's constructor - the flag for "some slot here is
            // typed" is anyPrim, computed from reps. Using hasPrims here left `prims` null while the
            // shape still reported DOUBLE fields, and a guarded region reading them (RegionSite) NPEd
            // on `prims[i]` instead of falling back - found via nbody with -Djmp.layout=0.
            if (shape.anyPrim) typeSlots();
        }

        /**
         * For compiled table literals whose value representations are known statically: the typed
         * shape ({@link Shape#withReps}), the primitive slots as bits and the reference slots, all
         * built by the generated code - no Object[] of boxes, no typeSlots pass, two allocations
         * instead of N + 3.
         */
        public Plain(Shape shape, long[] prims, Object[] values) {
            this.shape = shape;
            this.prims = prims;
            this.values = values == null ? EMPTY : values;
        }

        /** Table of a class's static members: its own sealed shape. */
        public Plain(Shape s, JClass owner) {
            s.owner = owner;
            this.shape = s;
            this.values = s.hasRefs ? new Object[s.size()] : EMPTY;
            this.prims = s.hasPrims ? new long[s.size()] : null;
        }

        /** Array-backed class instance: sealed shape, fields by declared type. */
        public Plain(JClass cls) {
            Shape s = cls.shape;
            this.shape = s;
            this.values = s.hasRefs ? new Object[s.size()] : EMPTY;
            this.prims = s.hasPrims ? new long[s.size()] : null;
        }

        /**
         * Declare slot representations from what was put in them: a slot holding a primitive
         * moves to prims and stops storing a box.
         *
         * <p>Done only here, while building a literal, and only once. A key added later stays a
         * reference on purpose: a shape transition in the middle of a table's life would reset all
         * inline caches on it, and the gain for one key is not worth that. All tables of one
         * literal walk the same transition path, so they share the shape - otherwise no cache
         * would ever hit twice and the cure would be worse than the disease.
         */
        private void typeSlots() {
            Shape s = shape;
            int n = s.size();
            long[] p = new long[n];
            for (int i = 0; i < n; i++) {
                VarType t = s.typeAt(i);
                if (t.isPrimitive()) {
                    p[i] = t.toBits(values[i]);
                    values[i] = null;
                }
            }
            prims = p;
        }

        /**
         * Write to a plain table's slot honoring the representation.
         *
         * <p>The representation is an assumption: if the value is of another kind, the slot goes
         * back to a reference. The transition is one-way (primitive -> reference), so the shape does
         * not oscillate, and inline caches that referred to the old shape simply miss and re-read the slot.
         */
        @Override
        public void setSlot(int i, Object v) {
            Shape s = shape;
            VarType t = s.typeAt(i);
            if (t.isPrimitive()) {
                if (Shape.repOf(v) == t) { setBits(i, t.toBits(v)); return; }
                shape = s = s.retyped(i, VarType.DYN);
            }
            Object[] vs = values;
            if (i >= vs.length) values = vs = Arrays.copyOf(vs, Math.max(4, s.size()));
            vs[i] = v;
        }

        @Override public long bitsAt(int i) { return prims[i]; }
        @Override public void setBits(int i, long bits) { prims[i] = bits; }
        @Override public Object refAt(int i) { return values[i]; }
        @Override public void setRef(int i, Object v) { values[i] = v; }
        @Override protected LinkedHashMap<Object, Object> dict() { return dict; }

        @Override
        public void addNew(Shape next, Object value) {
            int i = next.size() - 1;
            Object[] v = values;
            if (i >= v.length) values = v = Arrays.copyOf(v, Math.max(4, v.length * 2));
            v[i] = value;
            shape = next;
        }

        @Override
        protected void toDict() {
            if (cls() != null) throw new IllegalStateException("sealed instance");
            LinkedHashMap<Object, Object> d = new LinkedHashMap<>(Math.max(16, shape.size() * 2));
            Shape s = shape;
            for (int i = 0; i < s.size(); i++) d.put(s.keyAt(i), at(i));
            dict = d;
            shape = null; // dictionary mode: no inline cache will hit
            values = EMPTY;
            prims = null;
        }
    }

    // ==================================================================================
    // Fixed-layout table: the base of the classes jit/LayoutGen generates for literal shapes.
    // The slots of the literal are real fields of the generated subclass (`p3` for a primitive
    // slot holding bits, `o1` for a reference slot); everything the fields do not cover - keys
    // added later and slots that lost their primitive representation - lives in the overflow
    // array, by shape index, exactly as in Plain.values. The generated bitsAt/setBits/refAt/setRef
    // dispatch on the index and fall back to the methods here.
    // ==================================================================================
    /**
     * A table with no slots of its own: what a literal whose values are all null builds
     * (`{ left: null, right: null }` - null creates no key). A Plain for it was the object, an empty
     * values array reference, a prims reference and a dict reference - 32 bytes; this is the header,
     * the shape and Full's one storage field - 24, the size of a Java object with two references.
     * It is a Full, not a Lean: such tables are usually filled in later, and a Full grows through
     * shared shapes, as fast as a Plain.
     */
    public static final class Bare extends Full {
        public Bare(Shape s) { super(s); }
    }

    /**
     * Base of the tables whose slots are fields of a generated class (jit/LayoutGen) plus {@link Bare}.
     * Everything the fields do not hold - keys added later, slots that lost their primitive
     * representation - is the "store": the overflow {@code Object[]} (by shape index, as in
     * Plain.values) or, after {@link #toDict}, the dictionary. Where the store lives is up to the
     * subclass: {@link Full} has a field for it, {@link Lean} does not.
     */
    public abstract static class Fixed extends JTable {
        static final Object[] EMPTY = new Object[0];

        protected Fixed(Shape shape) {
            this.shape = shape;
        }

        /** The overflow array or the dictionary; null when neither exists yet. */
        protected abstract Object store();

        /** Set the store (the overflow array, never a dictionary - see {@link #toDict}). */
        protected abstract void store(Object[] overflow);

        /** Move to shape `next`, keeping the store. */
        protected abstract void reshape(Shape next);

        /** The overflow array (empty when there is none). */
        public final Object[] overflow() {
            return store() instanceof Object[] a ? a : EMPTY;
        }

        /** Same rule as {@link Plain#setSlot}: a value of another kind sends the slot back to a reference (one-way). */
        @Override
        public void setSlot(int i, Object v) {
            Shape s = shape;
            VarType t = s.typeAt(i);
            if (t.isPrimitive()) {
                if (Shape.repOf(v) == t) { setBits(i, t.toBits(v)); return; }
                reshape(s.retyped(i, VarType.DYN));
            }
            setRef(i, v);
        }

        @Override public long bitsAt(int i) { throw new IllegalStateException("slot " + i + " has no primitive field"); }
        @Override public void setBits(int i, long bits) { throw new IllegalStateException("slot " + i + " has no primitive field"); }

        @Override
        public Object refAt(int i) {
            return store() instanceof Object[] vs && i < vs.length ? vs[i] : null;
        }

        @Override
        public void setRef(int i, Object v) {
            Object[] vs = overflow();
            if (i >= vs.length) {
                vs = Arrays.copyOf(vs, Math.max(4, Math.max(i + 1, vs.length * 2)));
                store(vs);
            }
            vs[i] = v;
        }

        @SuppressWarnings("unchecked")
        @Override protected LinkedHashMap<Object, Object> dict() {
            return store() instanceof LinkedHashMap<?, ?> d ? (LinkedHashMap<Object, Object>) d : null;
        }

        @Override
        public void addNew(Shape next, Object value) {
            setRef(next.size() - 1, value);
            reshape(next);
        }

        /** The table's content as a dictionary, key order kept. */
        protected final LinkedHashMap<Object, Object> contentAsDict() {
            LinkedHashMap<Object, Object> d = new LinkedHashMap<>(Math.max(16, shape.size() * 2));
            Shape s = shape;
            for (int i = 0; i < s.size(); i++) d.put(s.keyAt(i), at(i));
            return d;
        }
    }

    /** A Fixed with a field for its store: 4 bytes per table, and growing is as cheap as for a Plain. */
    public abstract static class Full extends Fixed {
        /** null, the overflow array, or the dictionary after toDict. */
        private Object extra;

        protected Full(Shape shape) {
            super(shape);
        }

        @Override protected final Object store() { return extra; }
        @Override protected final void store(Object[] overflow) { extra = overflow; }
        @Override protected final void reshape(Shape next) { shape = next; }

        @Override
        protected void toDict() {
            extra = contentAsDict();   // the overflow goes with it: dictionary mode, the fields are dead from here on
            shape = null;
        }
    }

    /**
     * A Fixed without a store field: a layout table is its header, its shape and its slot fields -
     * `{ left: l, right: r }` 24 bytes, as Java's `new Node(l, r)`, not 32. The rare table that outgrows
     * its fields keeps its store in a private copy of its shape ({@link Shape#privateCopy}); such a
     * shape is its own and no inline cache links to it, so a grown Lean table runs the generic path.
     * That is also the signal ({@link #grown}): the layout switches future tables of this literal to a
     * Full class (jit/LayoutGen), so a literal whose tables do grow pays this once, not per table.
     * Dictionary mode keeps the dictionary in a private shape too ({@link Shape#dictShape}).
     */
    public abstract static class Lean extends Fixed {
        /** Set by jit/LayoutGen: called with this table's class when a Lean table first needs a store. */
        public static volatile java.util.function.Consumer<Class<?>> grown;

        protected Lean(Shape shape) {
            super(shape);
        }

        @Override protected final Object store() { return shape.store; }

        @Override
        protected final void store(Object[] overflow) {
            if (shape.store == null) {
                java.util.function.Consumer<Class<?>> g = grown;
                if (g != null) g.accept(getClass());
            }
            shape = shape.privateCopy(overflow);
        }

        @Override
        protected final void reshape(Shape next) {
            Object st = shape.store;
            shape = st == null ? next : next.privateCopy(st);
        }

        @Override
        protected void toDict() {
            shape = Shape.dictShape(contentAsDict());   // not null: the dictionary lives in it
        }
    }
}
