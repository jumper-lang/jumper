package me.padej.jumper.runtime;

import java.util.Collection;
import java.util.Map;

/** Operations on Jumper values with fast paths for Integer/Double. */
public final class Ops {
    private Ops() {}

    // ---------- truthiness ----------
    public static boolean truthy(Object v) {
        if (v == null || v == Boolean.FALSE) return false; // fast path: null and the canonical Boolean.FALSE
        if (v instanceof Boolean b) return b;              // a Boolean not from the valueOf cache (reflection, foreign libraries)
        return true;
    }

    // ---------- arithmetic ----------
    public static Object add(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x + y;
        if (a instanceof Double x && b instanceof Double y) return x + y;
        if (a instanceof String || b instanceof String) return str(a) + str(b);
        if (a instanceof Number x && b instanceof Number y) {
            if (a instanceof Double || b instanceof Double) return x.doubleValue() + y.doubleValue();
            return x.longValue() + y.longValue();
        }
        throw typeError("+", a, b);
    }

    public static Object sub(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x - y;
        if (a instanceof Double x && b instanceof Double y) return x - y;
        if (a instanceof Number x && b instanceof Number y) {
            if (a instanceof Double || b instanceof Double) return x.doubleValue() - y.doubleValue();
            return x.longValue() - y.longValue();
        }
        throw typeError("-", a, b);
    }

    public static Object mul(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x * y;
        if (a instanceof Double x && b instanceof Double y) return x * y;
        if (a instanceof Number x && b instanceof Number y) {
            if (a instanceof Double || b instanceof Double) return x.doubleValue() * y.doubleValue();
            return x.longValue() * y.longValue();
        }
        throw typeError("*", a, b);
    }

    public static Object div(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) {
            if (y == 0) throw new JmpError("Division by zero");
            return x / y;
        }
        if (a instanceof Double x && b instanceof Double y) return x / y;
        if (a instanceof Number x && b instanceof Number y) {
            if (a instanceof Double || b instanceof Double) return x.doubleValue() / y.doubleValue();
            if (y.longValue() == 0) throw new JmpError("Division by zero");
            return x.longValue() / y.longValue();
        }
        throw typeError("/", a, b);
    }


    // ---------- mixed variants: dynamic left operand, primitive right operand (no boxing of the right) ----------
    public static Object add(Object a, double b) {
        if (a instanceof Double x) return x + b;
        if (a instanceof Integer x) return x + b;
        if (a instanceof Long x) return x + b;
        if (a instanceof String s) return s + str(b);
        throw typeError("+", a, b);
    }
    public static Object add(Object a, int b) {
        if (a instanceof Integer x) return x + b;
        if (a instanceof Double x) return x + b;
        if (a instanceof Long x) return x + b;
        if (a instanceof String s) return s + b;
        throw typeError("+", a, b);
    }
    public static Object add(Object a, long b) {
        if (a instanceof Integer x) return x + b;
        if (a instanceof Long x) return x + b;
        if (a instanceof Double x) return x + b;
        if (a instanceof String s) return s + b;
        throw typeError("+", a, b);
    }
    public static Object sub(Object a, double b) {
        if (a instanceof Double x) return x - b;
        if (a instanceof Integer x) return x - b;
        if (a instanceof Long x) return x - b;
        throw typeError("-", a, b);
    }
    public static Object sub(Object a, int b) {
        if (a instanceof Integer x) return x - b;
        if (a instanceof Double x) return x - b;
        if (a instanceof Long x) return x - b;
        throw typeError("-", a, b);
    }
    public static Object sub(Object a, long b) {
        if (a instanceof Integer x) return x - b;
        if (a instanceof Long x) return x - b;
        if (a instanceof Double x) return x - b;
        throw typeError("-", a, b);
    }
    public static Object mul(Object a, double b) {
        if (a instanceof Double x) return x * b;
        if (a instanceof Integer x) return x * b;
        if (a instanceof Long x) return x * b;
        throw typeError("*", a, b);
    }
    public static Object mul(Object a, int b) {
        if (a instanceof Integer x) return x * b;
        if (a instanceof Double x) return x * b;
        if (a instanceof Long x) return x * b;
        throw typeError("*", a, b);
    }
    public static Object mul(Object a, long b) {
        if (a instanceof Integer x) return x * b;
        if (a instanceof Long x) return x * b;
        if (a instanceof Double x) return x * b;
        throw typeError("*", a, b);
    }
    public static Object div(Object a, double b) {
        if (a instanceof Double x) return x / b;
        if (a instanceof Integer x) return x / b;
        if (a instanceof Long x) return x / b;
        throw typeError("/", a, b);
    }
    public static Object div(Object a, int b) {
        if (a instanceof Double x) return x / b;
        if (b == 0 && (a instanceof Integer || a instanceof Long)) throw new JmpError("Division by zero");
        if (a instanceof Integer x) return x / b;
        if (a instanceof Long x) return x / b;
        throw typeError("/", a, b);
    }
    public static Object div(Object a, long b) {
        if (a instanceof Double x) return x / b;
        if (b == 0 && (a instanceof Integer || a instanceof Long)) throw new JmpError("Division by zero");
        if (a instanceof Integer x) return x / b;
        if (a instanceof Long x) return x / b;
        throw typeError("/", a, b);
    }
    public static Object mod(Object a, double b) {
        if (a instanceof Double x) return x % b;
        if (a instanceof Integer x) return x % b;
        if (a instanceof Long x) return x % b;
        throw typeError("%", a, b);
    }
    public static Object mod(Object a, int b) {
        if (a instanceof Double x) return x % b;
        if (b == 0 && (a instanceof Integer || a instanceof Long)) throw new JmpError("Division by zero");
        if (a instanceof Integer x) return x % b;
        if (a instanceof Long x) return x % b;
        throw typeError("%", a, b);
    }
    public static Object mod(Object a, long b) {
        if (a instanceof Double x) return x % b;
        if (b == 0 && (a instanceof Integer || a instanceof Long)) throw new JmpError("Division by zero");
        if (a instanceof Integer x) return x % b;
        if (a instanceof Long x) return x % b;
        throw typeError("%", a, b);
    }

    public static Object mod(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) {
            if (y == 0) throw new JmpError("Division by zero");
            return x % y;
        }
        if (a instanceof Number x && b instanceof Number y) {
            if (a instanceof Double || b instanceof Double) return x.doubleValue() % y.doubleValue();
            if (y.longValue() == 0) throw new JmpError("Division by zero");
            return x.longValue() % y.longValue();
        }
        throw typeError("%", a, b);
    }

    public static Object neg(Object a) {
        if (a instanceof Integer x) return -x;
        if (a instanceof Double x) return -x;
        if (a instanceof Long x) return -x;
        throw new JmpError("Cannot negate " + typeName(a));
    }

    // ---------- bitwise ----------
    public static Object band(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x & y;
        return toLong(a, "&") & toLong(b, "&");
    }

    public static Object bor(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x | y;
        return toLong(a, "|") | toLong(b, "|");
    }

    public static Object bxor(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x ^ y;
        return toLong(a, "^") ^ toLong(b, "^");
    }

    public static Object shl(Object a, Object b) {
        if (a instanceof Integer x) return x << toInt(b, "<<");
        return toLong(a, "<<") << toInt(b, "<<");
    }

    public static Object shr(Object a, Object b) {
        if (a instanceof Integer x) return x >> toInt(b, ">>");
        return toLong(a, ">>") >> toInt(b, ">>");
    }

    public static Object ushr(Object a, Object b) {
        if (a instanceof Integer x) return x >>> toInt(b, ">>>");
        return toLong(a, ">>>") >>> toInt(b, ">>>");
    }

    private static long toLong(Object v, String op) {
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
        throw new JmpError("Operator " + op + " requires integers, got " + typeName(v));
    }

    private static int toInt(Object v, String op) {
        if (v instanceof Integer || v instanceof Long) return ((Number) v).intValue();
        throw new JmpError("Operator " + op + " requires integers, got " + typeName(v));
    }

    // ---------- comparison ----------
    public static boolean eq(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number x && b instanceof Number y) {
            if (a instanceof Double || b instanceof Double) return x.doubleValue() == y.doubleValue();
            return x.longValue() == y.longValue();
        }
        return a.equals(b);
    }

    public static boolean lt(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x < y;
        return compare(a, b, "<") < 0;
    }

    public static boolean le(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x <= y;
        return compare(a, b, "<=") <= 0;
    }

    public static boolean gt(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x > y;
        return compare(a, b, ">") > 0;
    }

    public static boolean ge(Object a, Object b) {
        if (a instanceof Integer x && b instanceof Integer y) return x >= y;
        return compare(a, b, ">=") >= 0;
    }

    // "Object vs primitive" comparison: the right operand is not boxed.
    //
    // Needed wherever one side is dynamic and the other is statically known - both a literal
    // (`e.x > 0.0`) and an unboxed local variable (`e.x > w`). Without these overloads unboxing
    // a local makes the code SLOWER: the value is taken from the local and immediately boxed
    // back to call lt(Object, Object). The semantics are exactly those of the boxed variant -
    // visible from the fact that the body simply calls it through box().

    public static boolean lt(Object a, int b) { return a instanceof Integer x ? x < b : lt(a, box(b)); }
    public static boolean le(Object a, int b) { return a instanceof Integer x ? x <= b : le(a, box(b)); }
    public static boolean gt(Object a, int b) { return a instanceof Integer x ? x > b : gt(a, box(b)); }
    public static boolean ge(Object a, int b) { return a instanceof Integer x ? x >= b : ge(a, box(b)); }
    public static boolean eq(Object a, int b) { return a instanceof Integer x ? x == b : eq(a, box(b)); }

    public static boolean lt(Object a, long b) { return lt(a, (Object) b); }
    public static boolean le(Object a, long b) { return le(a, (Object) b); }
    public static boolean gt(Object a, long b) { return gt(a, (Object) b); }
    public static boolean ge(Object a, long b) { return ge(a, (Object) b); }
    public static boolean eq(Object a, long b) { return eq(a, (Object) b); }

    public static boolean lt(Object a, double b) { return a instanceof Double x ? x < b : lt(a, (Object) b); }
    public static boolean le(Object a, double b) { return a instanceof Double x ? x <= b : le(a, (Object) b); }
    public static boolean gt(Object a, double b) { return a instanceof Double x ? x > b : gt(a, (Object) b); }
    public static boolean ge(Object a, double b) { return a instanceof Double x ? x >= b : ge(a, (Object) b); }
    public static boolean eq(Object a, double b) { return a instanceof Double x ? x == b : eq(a, (Object) b); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b, String op) {
        if (a instanceof Number x && b instanceof Number y) {
            if (a instanceof Double || b instanceof Double) return Double.compare(x.doubleValue(), y.doubleValue());
            return Long.compare(x.longValue(), y.longValue());
        }
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Comparable x && b != null && a.getClass() == b.getClass()) return x.compareTo(b);
        throw typeError(op, a, b);
    }

    // ---------- indexing ----------
    public static Object index(Object obj, Object key) {
        if (obj instanceof JTable t) return t.getField(key);
        if (obj instanceof JArray a) return a.get(indexToInt(key));
        if (obj instanceof java.util.List<?> l) return l.get(indexToInt(key));
        if (obj instanceof Map<?, ?> m) return m.get(key);
        if (obj instanceof String s) return charStr(s.charAt(indexToInt(key)));
        if (obj == null) throw new JmpError("Cannot index null with " + str(key));
        if (obj.getClass().isArray()) return javaArrayGet(obj, indexToInt(key));
        return Interop.getField(obj, str(key));
    }

    @SuppressWarnings("unchecked")
    public static void setIndex(Object obj, Object key, Object value) {
        if (obj instanceof JTable t) { t.put(key, value); return; }
        if (obj instanceof JArray a) { a.set(indexToInt(key), value); return; }
        if (obj instanceof java.util.List l) { l.set(indexToInt(key), value); return; }
        if (obj instanceof Map m) { m.put(key, value); return; }
        if (obj == null) throw new JmpError("Cannot index null with " + str(key));
        if (obj.getClass().isArray()) { javaArraySet(obj, indexToInt(key), value); return; }
        Interop.setField(obj, str(key), value);
    }

    private static int indexToInt(Object key) {
        if (key instanceof Integer i) return i;
        if (key instanceof Long l) return (int) (long) l;
        throw new JmpError("Array index must be an integer, got " + typeName(key));
    }

    // ---------- real Java arrays ----------
    // No reflection: java.lang.reflect.Array.get/set box and do not inline, which made access to
    // new int[n] 5-7 times more expensive than a script array. The instanceof chain at a single
    // call site is folded by C2 into one class check. Values are converted to Jumper types the same
    // way Interop.normalize does for fields and method results: char -> string, float -> double,
    // short/byte -> int (otherwise a float[] element arrived as Float and counted as integer arithmetic).

    /** Read a Java array element. obj must be an array. */
    static Object javaArrayGet(Object obj, int k) {
        if (obj instanceof int[] a) return a[k];
        if (obj instanceof double[] a) return a[k];
        if (obj instanceof Object[] a) return a[k];
        if (obj instanceof boolean[] a) return a[k];
        if (obj instanceof long[] a) return a[k];
        if (obj instanceof char[] a) return charStr(a[k]);
        if (obj instanceof byte[] a) return (int) a[k];
        if (obj instanceof short[] a) return (int) a[k];
        if (obj instanceof float[] a) return (double) a[k];
        throw new JmpError("Cannot index " + typeName(obj));
    }

    /** Write a Java array element with conversion by component type (like Interop.convert for arguments). */
    static void javaArraySet(Object obj, int k, Object v) {
        if (obj instanceof int[] a) { a[k] = argInt(v); return; }
        if (obj instanceof double[] a) { a[k] = argDouble(v); return; }
        if (obj instanceof Object[] a) {
            try { a[k] = v; } catch (ArrayStoreException e) {
                throw new JmpError("Cannot store " + typeName(v) + " into " + typeName(obj));
            }
            return;
        }
        if (obj instanceof boolean[] a) { a[k] = argBool(v); return; }
        if (obj instanceof long[] a) { a[k] = argLong(v); return; }
        if (obj instanceof char[] a) { a[k] = argChar(v); return; }
        if (obj instanceof byte[] a) { a[k] = (byte) argInt(v); return; }
        if (obj instanceof short[] a) { a[k] = (short) argInt(v); return; }
        if (obj instanceof float[] a) { a[k] = (float) argDouble(v); return; }
        throw new JmpError("Cannot index " + typeName(obj));
    }

    /** Length of a Java array, or -1 if the value is not an array. */
    static int javaArrayLength(Object v) {
        if (v instanceof Object[] a) return a.length;
        if (v instanceof int[] a) return a.length;
        if (v instanceof double[] a) return a.length;
        if (v instanceof boolean[] a) return a.length;
        if (v instanceof long[] a) return a.length;
        if (v instanceof char[] a) return a.length;
        if (v instanceof byte[] a) return a.length;
        if (v instanceof short[] a) return a.length;
        if (v instanceof float[] a) return a.length;
        return -1;
    }

    private static char argChar(Object v) {
        if (v instanceof String s && s.length() == 1) return s.charAt(0);
        if (v instanceof Character c) return c;
        if (v instanceof Integer i) return (char) (int) i;
        if (v == null) return 0;
        throw new JmpError("Cannot assign " + typeName(v) + " to char");
    }

    public static Object member(Object obj, String name) {
        if (obj instanceof JTable t) return t.getField(name);
        if (obj instanceof JArray a) {
            if (name.equals("length")) return a.size();
            return null;
        }
        if (obj instanceof String s) {
            if (name.equals("length")) return s.length();
        }
        if (obj instanceof JClass jc) return jc.staticGet(name);
        if (obj instanceof JavaClass jc) return name.equals("class") ? Interop.wrapClass(jc.cls()) : Interop.getStatic(jc.cls(), name);
        if (obj == null) throw new JmpError("Cannot read property '" + name + "' of null");
        if (name.equals("length")) {
            int n = javaArrayLength(obj);
            if (n >= 0) return n;
            if (obj instanceof Collection<?> c) return c.size();
        }
        return Interop.getField(obj, name);
    }

    public static void setMember(Object obj, String name, Object value) {
        if (obj instanceof JTable t) { t.put(name, value); return; }
        if (obj instanceof JClass jc) { jc.staticSet(name, value); return; }
        if (obj == null) throw new JmpError("Cannot set property '" + name + "' of null");
        if (obj instanceof JavaClass jc) { Interop.setStatic(jc.cls(), name, value); return; }
        Interop.setField(obj, name, value);
    }

    /** Check for `Vec v = value`: null or an instance of class node (or a subclass). */
    /** A value of a variable typed with a Java class (`Random r`): null or an instance of it. */
    public static Object checkJava(Object v, Class<?> cls) {
        if (v == null || cls.isInstance(v)) return v;
        throw new JmpError("Expected " + cls.getSimpleName() + ", got " + typeName(v));
    }

    public static Object checkClass(Object v, me.padej.jumper.ast.Classes.ClassNode node) {
        if (v == null) return v;
        if (v instanceof JTable t) {
            Shape s = t.shape;
            if (s != null && s.ownerNode == node) return v;   // exactly this class: the common case, two loads
            JClass c = t.cls();
            if (c != null && c.isa(node)) return v;
        }
        throw new JmpError("Expected " + node.name + ", got " + typeName(v));
    }

    /** The script was cancelled by the host ({@code Interpreter.cancel()}); thrown on a loop back-edge or a function entry. */
    public static JmpError cancelled() {
        return JmpCancelled.INSTANCE;
    }

    /**
     * What a script may not intercept: a policy violation and a cancellation. {@code catch} does not
     * receive them and {@code finally} does not run for them (a finally with a return or a loop in it
     * could otherwise swallow the one or outlive the other) - they go up to the host as they are.
     */
    public static boolean fatal(Throwable t) {
        if (t instanceof ScriptSecurityException || t instanceof JmpCancelled) return true;
        // in a sandbox a stack overflow too: see JmpStackOverflow
        return (t instanceof StackOverflowError || t instanceof JmpStackOverflow) && Access.current() != Access.ALL;
    }

    /** A StackOverflowError as the script sees it: fatal in a sandbox (JmpStackOverflow), an error otherwise. */
    public static JmpError stackOverflow() {
        return Access.current() != Access.ALL ? JmpStackOverflow.INSTANCE : new JmpError("Stack overflow");
    }

    public static Object call(Object f, Object[] args) {
        if (f instanceof JFunction fn) return fn.call(args);
        throw new JmpError("Attempt to call " + typeName(f));
    }

    // ---------- length / string ----------
    public static int len(Object v) {
        if (v instanceof String s) return s.length();
        if (v instanceof JArray a) return a.size();
        if (v instanceof JTable t) return t.size();
        if (v instanceof Collection<?> c) return c.size();
        if (v instanceof Map<?, ?> m) return m.size();
        int n = javaArrayLength(v);
        if (n >= 0) return n;
        throw new JmpError("len() not supported for " + typeName(v));
    }

    private static final String[] CHARS = new String[128];
    static { for (int i = 0; i < 128; i++) CHARS[i] = String.valueOf((char) i); }

    /** Single-character string (ASCII comes from a cache, no allocation). */
    public static String charStr(char c) {
        return c < 128 ? CHARS[c] : String.valueOf(c);
    }

    public static String str(int v) { return Integer.toString(v); }
    public static String str(long v) { return Long.toString(v); }
    public static String str(boolean v) { return v ? "true" : "false"; }
    public static String str(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return (long) d + ".0";
        return Double.toString(d);
    }

    public static String str(Object v) {
        if (v == null) return "null";
        if (v instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                long l = d.longValue();
                return l + ".0";
            }
            return d.toString();
        }
        return v.toString();
    }

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Integer) return "int";
        if (v instanceof Long) return "long";
        if (v instanceof Double) return "double";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof String) return "string";
        if (v instanceof JTable t) return t.cls() != null ? t.cls().name : "table";
        if (v instanceof JArray) return "array";
        if (v instanceof JFunction) return "function";
        if (v instanceof JavaClass jc) return "class " + jc.cls().getSimpleName();
        if (v instanceof JClass jc) return "class " + jc.name;
        Class<?> c = v.getClass();
        if (c.isArray()) return c.getComponentType().getSimpleName() + "[]";
        return c.getName();
    }

    private static JmpError typeError(String op, Object a, Object b) {
        return new JmpError("Cannot apply '" + op + "' to " + typeName(a) + " and " + typeName(b));
    }

    // ---------- helpers for compiled code (Tier 1) ----------
    public static int unboxInt(Object v) {
        if (v instanceof Integer i) return i;
        throw new JmpError("Expected int, got " + typeName(v));
    }

    public static long unboxLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i;
        throw new JmpError("Expected long, got " + typeName(v));
    }

    public static double unboxDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        throw new JmpError("Expected double, got " + typeName(v));
    }

    /** Call argument by index (missing ones are null). */
    public static Object arg(Object[] args, int i) {
        return i < args.length ? args[i] : null;
    }

    public static int argInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v == null) return 0;
        throw new JmpError("Cannot assign " + typeName(v) + " to int");
    }

    public static long argLong(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        if (v == null) return 0L;
        throw new JmpError("Cannot assign " + typeName(v) + " to long");
    }

    public static double argDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        if (v == null) return 0.0;
        throw new JmpError("Cannot assign " + typeName(v) + " to double");
    }

    // A dynamic value into a typed variable / field / return (Exprs.ToSlot): as arg*, with the variable's message.
    public static int slotInt(Object v) {
        if (v instanceof Integer i) return i;
        if (v == null) return 0;
        throw new JmpError("Expected int, got " + typeName(v));
    }

    public static long slotLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i;
        if (v == null) return 0L;
        throw new JmpError("Expected long, got " + typeName(v));
    }

    public static double slotDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        if (v == null) return 0.0;
        throw new JmpError("Expected double, got " + typeName(v));
    }

    public static boolean slotBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        throw new JmpError("Expected boolean, got " + typeName(v));
    }

    public static boolean argBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        throw new JmpError("Cannot assign " + typeName(v) + " to boolean");
    }

    // Tier 1 fast paths. Checks are ordered by frequency: script array, then a Java array of the
    // matching type, then the generic path. Widening conversions (int[] -> double) are allowed where
    // unbox* allows them; there are no narrowing ones (long[] -> int) - the language has none at all.

    public static Object indexInt(Object obj, int k) {
        if (obj instanceof JArray a) return a.get(k);
        if (obj instanceof Object[] arr) return arr[k];
        if (obj instanceof int[] arr) return arr[k];
        if (obj instanceof double[] arr) return arr[k];
        return index(obj, k);
    }

    public static void setIndexInt(Object obj, int k, Object v) {
        if (obj instanceof JArray a) { a.set(k, v); return; }
        setIndex(obj, k, v);
    }

    public static int indexAsInt(Object obj, int k) {
        if (obj instanceof JArray a) return a.getInt(k);
        if (obj instanceof int[] arr) return arr[k];
        if (obj instanceof byte[] arr) return arr[k];
        if (obj instanceof short[] arr) return arr[k];
        return unboxInt(index(obj, k));
    }

    public static double indexAsDouble(Object obj, int k) {
        if (obj instanceof JArray a) return a.getDouble(k);
        if (obj instanceof double[] arr) return arr[k];
        if (obj instanceof int[] arr) return arr[k];
        if (obj instanceof long[] arr) return arr[k];
        if (obj instanceof float[] arr) return arr[k];
        return unboxDouble(index(obj, k));
    }

    public static boolean indexAsBool(Object obj, int k) {
        if (obj instanceof JArray a) return a.getBool(k);
        if (obj instanceof boolean[] arr) return arr[k];
        return truthy(index(obj, k));
    }

    public static void setIndexI(Object obj, int k, int v) {
        if (obj instanceof JArray a) { a.setInt(k, v); return; }
        if (obj instanceof int[] arr) { arr[k] = v; return; }
        if (obj instanceof long[] arr) { arr[k] = v; return; }
        if (obj instanceof double[] arr) { arr[k] = v; return; }
        setIndex(obj, k, v);
    }

    public static void setIndexD(Object obj, int k, double v) {
        if (obj instanceof JArray a) { a.setDouble(k, v); return; }
        if (obj instanceof double[] arr) { arr[k] = v; return; }
        if (obj instanceof float[] arr) { arr[k] = (float) v; return; }
        setIndex(obj, k, v);
    }

    public static void setIndexZ(Object obj, int k, boolean v) {
        if (obj instanceof JArray a) { a.setBool(k, v); return; }
        if (obj instanceof boolean[] arr) { arr[k] = v; return; }
        setIndex(obj, k, v);
    }

    public static Object memberGet(Object obj, FieldCache c) {
        if (obj instanceof JTable t) return c.get(t);
        return c.getJava(obj);
    }

    public static void memberSet(Object obj, FieldCache c, Object v) {
        if (obj instanceof JTable t) c.set(t, v);
        else c.setJava(obj, v);
    }

    // Typed wrappers: `long s += o.x` must not box the field value.
    // For non-tables (Java objects) the path stays generic, through the property cache.

    public static int memberGetInt(Object obj, FieldCache c) {
        if (obj instanceof JTable t) return c.getInt(t);
        return unboxInt(c.getJava(obj));
    }

    public static long memberGetLong(Object obj, FieldCache c) {
        if (obj instanceof JTable t) return c.getLong(t);
        return unboxLong(c.getJava(obj));
    }

    public static double memberGetDouble(Object obj, FieldCache c) {
        if (obj instanceof JTable t) return c.getDouble(t);
        return unboxDouble(c.getJava(obj));
    }

    public static boolean memberGetBool(Object obj, FieldCache c) {
        if (obj instanceof JTable t) return c.getBool(t);
        return truthy(c.getJava(obj));
    }

    public static void memberSetI(Object obj, FieldCache c, int v) {
        if (obj instanceof JTable t) { c.setI(t, v); return; }
        c.setJava(obj, v);
    }

    public static void memberSetJ(Object obj, FieldCache c, long v) {
        if (obj instanceof JTable t) { c.setJ(t, v); return; }
        c.setJava(obj, v);
    }

    public static void memberSetD(Object obj, FieldCache c, double v) {
        if (obj instanceof JTable t) { c.setD(t, v); return; }
        c.setJava(obj, v);
    }

    public static void memberSetZ(Object obj, FieldCache c, boolean v) {
        if (obj instanceof JTable t) { c.setZ(t, v); return; }
        c.setJava(obj, v);
    }

    /** Iterator for for-each over any iterable Jumper value. */
    public static java.util.Iterator<?> iter(Object it) {
        if (it instanceof JArray a) return a.iterator();
        if (it instanceof JTable t) return new java.util.ArrayList<>(t.keys()).iterator();
        if (it instanceof Iterable<?> i) return i.iterator();
        if (it instanceof Map<?, ?> m) return new java.util.ArrayList<>(m.keySet()).iterator();
        if (it instanceof String s) return s.chars().mapToObj(ch -> (Object) String.valueOf((char) ch)).iterator();
        if (it instanceof java.util.Iterator<?> i) return i;
        if (it instanceof Object[] a) return java.util.Arrays.asList(a).iterator();
        int n = javaArrayLength(it);
        if (n >= 0) {
            java.util.ArrayList<Object> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(javaArrayGet(it, i));
            return l.iterator();
        }
        throw new JmpError("Cannot iterate over " + typeName(it));
    }

    public static Object box(int v) { return v; }
    public static Object box(long v) { return v; }
    public static Object box(double v) { return v; }
    public static Object box(boolean v) { return v; }

    // ---------- coercion for typed slots ----------
    public static Object toIntSlot(Object v) {
        if (v instanceof Integer) return v;
        if (v == null) return 0;
        throw new JmpError("Cannot assign " + typeName(v) + " to int");
    }

    public static Object toLongSlot(Object v) {
        if (v instanceof Long) return v;
        if (v instanceof Integer i) return (long) i;
        if (v == null) return 0L;
        throw new JmpError("Cannot assign " + typeName(v) + " to long");
    }

    public static Object toDoubleSlot(Object v) {
        if (v instanceof Double) return v;
        if (v instanceof Integer i) return (double) i;
        if (v instanceof Long l) return (double) l;
        if (v == null) return 0.0;
        throw new JmpError("Cannot assign " + typeName(v) + " to double");
    }

    public static Object toBooleanSlot(Object v) {
        if (v instanceof Boolean) return v;
        if (v == null) return Boolean.FALSE;
        throw new JmpError("Cannot assign " + typeName(v) + " to boolean");
    }

    public static Object toStringSlot(Object v) {
        if (v instanceof String || v == null) return v;
        throw new JmpError("Cannot assign " + typeName(v) + " to String");
    }
}
