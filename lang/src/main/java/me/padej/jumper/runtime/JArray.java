package me.padej.jumper.runtime;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.RandomAccess;

/**
 * Jumper array: zero-based, grows on a write to index == length.
 *
 * Storage specializes on the first element: int[] / double[] / boolean[] / Object[].
 * While all elements share one primitive type there is no boxing at all; the first "foreign"
 * value converts the array to Object[] (permanently). Tier 1 reads and writes via getInt/setInt etc.
 */
public final class JArray extends AbstractList<Object> implements RandomAccess {
    static final int EMPTY = 0, INT = 1, DOUBLE = 2, BOOL = 3, OBJ = 4;

    public int kind = EMPTY;
    // public for Tier 1: under a region guard that proved the kind, Compiler.rawArrayRead/Write
    // access the storage inline (bounds against size, then the plain array); nothing else writes them
    public int[] ints;
    public double[] dbls;
    public boolean[] bools;
    public Object[] objs;
    public int size;
    private int capacity;

    public JArray() {
        this(8);
    }

    public JArray(int capacity) {
        this.capacity = Math.max(capacity, 4);
    }

    public JArray(Object[] initial) {
        this(initial, initial.length);
    }

    /** Takes ownership of the buffer; size is the number of used elements. */
    public JArray(Object[] buffer, int size) {
        this.capacity = Math.max(buffer.length, 4);
        this.size = size;
        int k = EMPTY;
        for (int i = 0; i < size; i++) {
            int vk = kindOf(buffer[i]);
            if (k == EMPTY) k = vk;
            else if (k != vk) { k = OBJ; break; }
        }
        if (size == 0) k = EMPTY;
        switch (k) {
            case INT -> { ints = new int[capacity]; for (int i = 0; i < size; i++) ints[i] = (Integer) buffer[i]; }
            case DOUBLE -> { dbls = new double[capacity]; for (int i = 0; i < size; i++) dbls[i] = (Double) buffer[i]; }
            case BOOL -> { bools = new boolean[capacity]; for (int i = 0; i < size; i++) bools[i] = (Boolean) buffer[i]; }
            case OBJ -> objs = buffer.length >= capacity ? buffer : Arrays.copyOf(buffer, capacity);
            default -> {}
        }
        kind = k;
    }

    /** A new array with the same elements in the same storage (the elements themselves are shared). */
    public JArray copyStorage() {
        JArray a = new JArray(capacity);
        a.kind = kind;
        a.size = size;
        if (ints != null) a.ints = ints.clone();
        if (dbls != null) a.dbls = dbls.clone();
        if (bools != null) a.bools = bools.clone();
        if (objs != null) a.objs = objs.clone();
        return a;
    }

    /** array(n, fill): n copies of the value with a matching storage kind. */
    public static JArray filled(int n, Object fill) {
        JArray a = new JArray(n);
        // A new primitive array is already zeroed: filling it with 0 / 0.0 / false again is a second pass
        // over the whole buffer (array(15000000, 0) in array_int_sum: 60 MB written twice). -0.0 is not +0.0.
        switch (kindOf(fill)) {
            case INT -> { a.ints = new int[a.capacity]; int v = (Integer) fill; if (v != 0) Arrays.fill(a.ints, 0, n, v); a.kind = INT; }
            case DOUBLE -> { a.dbls = new double[a.capacity]; double v = (Double) fill; if (Double.doubleToRawLongBits(v) != 0L) Arrays.fill(a.dbls, 0, n, v); a.kind = DOUBLE; }
            case BOOL -> { a.bools = new boolean[a.capacity]; if ((Boolean) fill) Arrays.fill(a.bools, 0, n, true); a.kind = BOOL; }
            default -> { a.objs = new Object[a.capacity]; Arrays.fill(a.objs, 0, n, fill); a.kind = OBJ; }
        }
        a.size = n;
        return a;
    }

    private static int kindOf(Object v) {
        if (v instanceof Integer) return INT;
        if (v instanceof Double) return DOUBLE;
        if (v instanceof Boolean) return BOOL;
        return OBJ;
    }

    public int kind() {
        return kind;
    }

    // ---------- bounds / growth ----------

    private void check(int i) {
        if (i < 0 || i >= size) throw new JmpError("Array index " + i + " out of bounds for length " + size);
    }

    private void grow() {
        int limit = Access.tableLimit();
        if (capacity >= limit) throw Access.tableLimitExceeded(limit);
        int nc = (int) Math.min((long) capacity * 2, limit);   // grow exactly up to the policy's bound
        switch (kind) {
            case INT -> ints = Arrays.copyOf(ints, nc);
            case DOUBLE -> dbls = Arrays.copyOf(dbls, nc);
            case BOOL -> bools = Arrays.copyOf(bools, nc);
            case OBJ -> objs = Arrays.copyOf(objs, nc);
            default -> {}
        }
        capacity = nc;
    }

    /** Conversion to Object[] (irreversible). */
    private void toObj() {
        Object[] o = new Object[capacity];
        switch (kind) {
            case INT -> { for (int i = 0; i < size; i++) o[i] = ints[i]; ints = null; }
            case DOUBLE -> { for (int i = 0; i < size; i++) o[i] = dbls[i]; dbls = null; }
            case BOOL -> { for (int i = 0; i < size; i++) o[i] = bools[i]; bools = null; }
            default -> {}
        }
        objs = o;
        kind = OBJ;
    }

    private void init(int k) {
        kind = k;
        switch (k) {
            case INT -> ints = new int[capacity];
            case DOUBLE -> dbls = new double[capacity];
            case BOOL -> bools = new boolean[capacity];
            default -> objs = new Object[capacity];
        }
    }

    // ---------- generic (boxed) access ----------

    @Override
    public Object get(int i) {
        check(i);
        return switch (kind) {
            case INT -> ints[i];
            case DOUBLE -> dbls[i];
            case BOOL -> bools[i];
            default -> objs[i];
        };
    }

    @Override
    public Object set(int i, Object v) {
        if (i == size) { add(v); return null; }
        check(i);
        switch (kind) {
            case INT -> { if (v instanceof Integer x) { ints[i] = x; return null; } }
            case DOUBLE -> { if (v instanceof Double x) { dbls[i] = x; return null; } }
            case BOOL -> { if (v instanceof Boolean x) { bools[i] = x; return null; } }
            case OBJ -> { objs[i] = v; return null; }
            default -> {}
        }
        toObj();
        objs[i] = v;
        return null;
    }

    @Override
    public boolean add(Object v) {
        if (size == capacity) grow();
        int vk = kindOf(v);
        if (kind == EMPTY) init(vk);
        else if (kind != vk && kind != OBJ) toObj();
        switch (kind) {
            case INT -> ints[size++] = (Integer) v;
            case DOUBLE -> dbls[size++] = (Double) v;
            case BOOL -> bools[size++] = (Boolean) v;
            default -> objs[size++] = v;
        }
        return true;
    }

    // ---------- typed access (Tier 1, no boxing) ----------

    public int getInt(int i) {
        if (kind == INT) { check(i); return ints[i]; }
        return Ops.unboxInt(get(i));
    }

    public double getDouble(int i) {
        if (kind == DOUBLE) { check(i); return dbls[i]; }
        return Ops.unboxDouble(get(i));
    }

    public boolean getBool(int i) {
        if (kind == BOOL) { check(i); return bools[i]; }
        return Ops.truthy(get(i));
    }

    /** The error of an out-of-range index (Tier 1's inline element access throws it). */
    public JmpError outOfBounds(int i) {
        return new JmpError("Array index " + i + " out of bounds for length " + size);
    }

    public void setInt(int i, int v) {
        if (kind == INT && i < size && i >= 0) { ints[i] = v; return; }
        if (kind == EMPTY && i == 0) { init(INT); ints[size++] = v; return; }
        if (kind == INT && i == size) { if (size == capacity) grow(); ints[size++] = v; return; }
        set(i, v);
    }

    public void setDouble(int i, double v) {
        if (kind == DOUBLE && i < size && i >= 0) { dbls[i] = v; return; }
        if (kind == EMPTY && i == 0) { init(DOUBLE); dbls[size++] = v; return; }
        if (kind == DOUBLE && i == size) { if (size == capacity) grow(); dbls[size++] = v; return; }
        set(i, v);
    }

    public void setBool(int i, boolean v) {
        if (kind == BOOL && i < size && i >= 0) { bools[i] = v; return; }
        if (kind == EMPTY && i == 0) { init(BOOL); bools[size++] = v; return; }
        if (kind == BOOL && i == size) { if (size == capacity) grow(); bools[size++] = v; return; }
        set(i, v);
    }

    // ---------- list ops ----------

    @Override
    public Object remove(int i) {
        Object old = get(i);
        int n = size - i - 1;
        switch (kind) {
            case INT -> System.arraycopy(ints, i + 1, ints, i, n);
            case DOUBLE -> System.arraycopy(dbls, i + 1, dbls, i, n);
            case BOOL -> System.arraycopy(bools, i + 1, bools, i, n);
            default -> { System.arraycopy(objs, i + 1, objs, i, n); objs[size - 1] = null; }
        }
        size--;
        return old;
    }

    public Object pop() {
        if (size == 0) throw new JmpError("pop from empty array");
        Object v = get(size - 1);
        size--;
        if (kind == OBJ) objs[size] = null;
        return v;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        if (kind == OBJ) Arrays.fill(objs, 0, size, null);
        size = 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Ops.str(get(i)));
        }
        return sb.append(']').toString();
    }
}
