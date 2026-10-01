package me.padej.jumper.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge to Java: method calls, constructors, field access.
 * Overload selection is by arity and type compatibility; the result is cached
 * by (class, name, arity) - and at the call site also by receiver class (see ast.MethodCall).
 */
public final class Interop {
    private Interop() {}

    private record Key(Class<?> cls, String name, int arity) {}

    private static final Map<Key, Method[]> METHOD_CACHE = new ConcurrentHashMap<>();
    /**
     * Classes found by name, per class loader: the same name is another class under another loader (two
     * plugins, or a language server that checks files of several servers, or a jar rebuilt while it runs).
     * Weak both ways: a loader that is gone takes its entries with it.
     */
    private static final Map<ClassLoader, Map<String, java.lang.ref.WeakReference<Class<?>>>> CLASS_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // ---------- classes ----------
    public static Class<?> findClass(String name) {
        Class<?> c = findClassUnchecked(name);
        if (c != null) {
            Access a = Access.current();
            if (!a.visible(c)) throw a.denied(c);
        }
        return c;
    }

    private static Class<?> findClassUnchecked(String name) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Map<String, java.lang.ref.WeakReference<Class<?>>> cache = CLASS_CACHE.computeIfAbsent(loader, k -> new ConcurrentHashMap<>());
        java.lang.ref.WeakReference<Class<?>> ref = cache.get(name);
        Class<?> c = ref == null ? null : ref.get();
        if (c != null) return c;
        // initialize = false: finding a class is a parse-time act (import, `new C`, C.class) - its static
        // initializer must not run there (an editor parses on every keystroke and never runs the script).
        // The JVM initializes the class on its first real use: new, a static call or field access.
        try {
            c = Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            try {
                c = Class.forName("java.lang." + name, false, Interop.class.getClassLoader());
            } catch (ClassNotFoundException e2) {
                return null;
            }
        }
        cache.put(name, new java.lang.ref.WeakReference<>(c));
        return c;
    }

    // ---------- method calls ----------
    public static Method[] candidates(Class<?> cls, String name, int arity, boolean wantStatic) {
        // the cache is per process, the policy per thread: filter after the cache
        return Access.current().filter(rawCandidates(cls, name, arity), cls);
    }

    /** Throw "Access denied" if the class has such a method but the policy closed it. */
    public static void checkDenied(Class<?> cls, String name, int arity) {
        Access policy = Access.current();
        if (policy == Access.ALL) return;
        Method[] raw = rawCandidates(cls, name, arity);
        if (raw.length > 0 && policy.filter(raw, cls).length == 0) throw policy.denied(cls, name);
    }

    private static Method[] rawCandidates(Class<?> cls, String name, int arity) {
        Key key = new Key(cls, name, arity);
        Method[] cached = METHOD_CACHE.get(key);
        if (cached == null) {
            List<Method> list = new ArrayList<>();
            collectMethods(cls, name, arity, list);
            cached = list.toArray(new Method[0]);
            METHOD_CACHE.put(key, cached);
        }
        return cached;
    }

    private static void collectMethods(Class<?> cls, String name, int arity, List<Method> out) {
        // Public methods of public classes/interfaces - otherwise IllegalAccess on ArrayList$Itr and the like.
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name)) continue;
            int pc = m.getParameterCount();
            if (pc != arity && !(m.isVarArgs() && arity >= pc - 1)) continue;
            if (!Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
                Method pub = findPublicOverride(m);
                if (pub == null) continue;
                m = pub;
            }
            out.add(m);
        }
    }

    /** For a method of a non-public class, look for the same signature in a public supertype/interface. */
    private static Method findPublicOverride(Method m) {
        List<Class<?>> queue = new ArrayList<>();
        queue.add(m.getDeclaringClass());
        for (int i = 0; i < queue.size(); i++) {
            Class<?> c = queue.get(i);
            if (Modifier.isPublic(c.getModifiers())) {
                try {
                    return c.getMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException ignored) {}
            }
            if (c.getSuperclass() != null) queue.add(c.getSuperclass());
            queue.addAll(List.of(c.getInterfaces()));
        }
        return null;
    }

    public static Method selectMethod(Method[] candidates, Object[] args, boolean wantStatic) {
        Method best = null;
        int bestScore = -1;
        for (Method m : candidates) {
            if (wantStatic != Modifier.isStatic(m.getModifiers())) continue;
            int score = matchScore(m.getParameterTypes(), m.isVarArgs(), args);
            if (score > bestScore) { best = m; bestScore = score; }
        }
        return best;
    }

    public static Object invoke(Object receiver, String name, Object[] args) {
        Class<?> cls = receiver.getClass();
        Method[] cands = candidates(cls, name, args.length, false);
        Method m = selectMethod(cands, args, false);
        if (m == null) throw noMethod(cls, name, args);
        return invokeMethod(m, receiver, args);
    }

    public static Object invokeStatic(Class<?> cls, String name, Object[] args) {
        Method[] cands = candidates(cls, name, args.length, true);
        Method m = selectMethod(cands, args, true);
        if (m == null) {
            Object r = classMember(cls, name, args);
            if (r != NO_CLASS_MEMBER) return r;
            throw noMethod(cls, name, args);
        }
        return invokeMethod(m, null, args);
    }

    public static final Object NO_CLASS_MEMBER = new Object();

    /**
     * {@code x.getName()} on a class value (a JavaClass: {@code Foo}, {@code Foo.class}, {@code obj.getClass()}
     * under a policy) that has no such static method: a question to the class itself. Only
     * {@link Access#CLASS_SAFE} - identity and shape, answered whatever the policy; anything else that
     * java.lang.Class has ({@code getClassLoader}, {@code forName}, {@code getMethods}...) is a
     * SecurityException under a policy. {@link #NO_CLASS_MEMBER} if Class has no such method at all.
     */
    public static Object classMember(Class<?> cls, String name, Object[] args) {
        Method[] raw = rawCandidates(Class.class, name, args.length);
        if (raw.length == 0) return NO_CLASS_MEMBER;
        Access policy = Access.current();
        if (Access.CLASS_SAFE.contains(name)) {
            Method m = selectMethod(raw, args, false);
            if (m == null) return NO_CLASS_MEMBER;
            return invokeMethod(m, cls, args);
        }
        if (policy != Access.ALL) throw policy.denied(Class.class, name);
        return NO_CLASS_MEMBER;
    }

    public static Object invokeMethod(Method m, Object receiver, Object[] args) {
        try {
            return normalize(m.invoke(receiver, convertArgs(m.getParameterTypes(), m.isVarArgs(), args)));
        } catch (InvocationTargetException e) {
            throw JmpError.rethrow(e.getTargetException());
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new JmpError("Cannot call " + m + ": " + e.getMessage(), e);
        }
    }

    /** Convert Java results to Jumper types: char -> String, float/short/byte -> double/int. */
    public static Object normalize(Object v) {
        if (v instanceof Class<?>) return wrapClass(v);
        if (v instanceof Character c) return Ops.charStr(c);
        if (v instanceof Float f) return f.doubleValue();
        if (v instanceof Short || v instanceof Byte) return ((Number) v).intValue();
        return v;
    }

    /**
     * A {@code java.lang.Class} coming from Java into a script under a policy becomes a {@link JavaClass}:
     * the same handle a class name gives - statics and {@code new} as the policy allows, and the
     * {@link Access#CLASS_SAFE} questions; never the Class object with its loader and reflection.
     * Without a policy a Class stays a Class (a script sees everything then).
     */
    public static Object wrapClass(Object v) {
        return v instanceof Class<?> c && Access.current() != Access.ALL ? new JavaClass(c) : v;
    }

    private static RuntimeException noMethod(Class<?> cls, String name, Object[] args) {
        Access policy = Access.current();
        // the method exists but the policy filtered it out - say so instead of "no method"
        // (only when the policy really removed something: an allowed method that simply does not
        // accept these arguments is an ordinary "no method")
        if (policy != Access.ALL) {
            Method[] raw = rawCandidates(cls, name, args.length);
            if (raw.length > 0 && policy.filter(raw, cls).length < raw.length) return policy.denied(cls, name);
        }
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Ops.typeName(a));
        }
        return new JmpError("No method " + cls.getSimpleName() + "." + name + "(" + sb + ")");
    }

    // ---------- constructors ----------
    public static Object construct(Class<?> cls, Object[] args) {
        Constructor<?> best = selectConstructor(cls, args);
        if (best == null) throw new JmpError("No constructor " + cls.getSimpleName() + " with " + args.length + " args");
        return invokeConstructor(best, args);
    }

    /** Best public constructor for the actual arguments, or null. */
    public static Constructor<?> selectConstructor(Class<?> cls, Object[] args) {
        Access a = Access.current();
        if (!a.classAllowed(cls)) throw a.denied(cls);
        Constructor<?> best = null;
        int bestScore = -1;
        for (Constructor<?> c : cls.getConstructors()) {
            int pc = c.getParameterCount();
            if (pc != args.length && !(c.isVarArgs() && args.length >= pc - 1)) continue;
            int score = matchScore(c.getParameterTypes(), c.isVarArgs(), args);
            if (score > bestScore) { best = c; bestScore = score; }
        }
        return best;
    }

    public static Object invokeConstructor(Constructor<?> best, Object[] args) {
        try {
            return best.newInstance(convertArgs(best.getParameterTypes(), best.isVarArgs(), args));
        } catch (InvocationTargetException e) {
            throw JmpError.rethrow(e.getTargetException());
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new JmpError("Cannot construct " + best.getDeclaringClass().getName() + ": " + e.getMessage(), e);
        }
    }

    public static Object getField(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            checkField(f, obj.getClass());
            return normalize(f.get(obj));
        } catch (NoSuchFieldException e) {
            // Field not found: a no-arg method with the same name (e.message), then a getter/is-method
            Method[] exact = candidates(obj.getClass(), name, 0, false);
            if (exact.length > 0 && obj instanceof Throwable) return invokeMethod(exact[0], obj, new Object[0]);
            Method[] getters = candidates(obj.getClass(), "get" + cap(name), 0, false);
            if (getters.length > 0) return invokeMethod(getters[0], obj, new Object[0]);
            Method[] is = candidates(obj.getClass(), "is" + cap(name), 0, false);
            if (is.length > 0) return invokeMethod(is[0], obj, new Object[0]);
            // a getter that exists but is closed: say so (`e.classLoader` -> getClassLoader)
            checkDenied(obj.getClass(), "get" + cap(name), 0);
            checkDenied(obj.getClass(), "is" + cap(name), 0);
            throw new JmpError("No field or property '" + name + "' on " + obj.getClass().getName());
        } catch (IllegalAccessException e) {
            throw new JmpError("Cannot access field " + name, e);
        }
    }

    public static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getField(name);
            checkField(f, obj.getClass());
            f.set(obj, convert(f.getType(), value));
        } catch (NoSuchFieldException e) {
            Method[] setters = candidates(obj.getClass(), "set" + cap(name), 1, false);
            if (setters.length > 0) { invokeMethod(setters[0], obj, new Object[]{value}); return; }
            checkDenied(obj.getClass(), "set" + cap(name), 1);   // `e.health = 0` with setHealth closed
            throw new JmpError("No field or property '" + name + "' on " + obj.getClass().getName());
        } catch (IllegalAccessException e) {
            throw new JmpError("Cannot set field " + name, e);
        }
    }

    public static Object getStatic(Class<?> cls, String name) {
        try {
            Field f = cls.getField(name);
            if (Modifier.isStatic(f.getModifiers())) { checkField(f, cls); return normalize(f.get(null)); }
        } catch (NoSuchFieldException ignored) {
        } catch (IllegalAccessException e) {
            throw new JmpError("Cannot access " + cls.getSimpleName() + "." + name, e);
        }
        // nested class?
        for (Class<?> inner : cls.getClasses()) {
            if (inner.getSimpleName().equals(name)) {
                Access a = Access.current();
                if (!a.visible(inner)) throw a.denied(inner);
                return new JavaClass(inner);
            }
        }
        // static method as a value
        final String n = name;
        return JFunction.of(cls.getSimpleName() + "." + name, args -> invokeStatic(cls, n, args));
    }

    public static void setStatic(Class<?> cls, String name, Object value) {
        try {
            Field f = cls.getField(name);
            checkField(f, cls);
            f.set(null, convert(f.getType(), value));
        } catch (ReflectiveOperationException e) {
            throw new JmpError("Cannot set static field " + cls.getSimpleName() + "." + name, e);
        }
    }

    private static void checkField(Field f, Class<?> runtime) {
        Access a = Access.current();
        if (!a.fieldAllowed(f, runtime)) throw a.denied(runtime, f.getName());
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------- argument matching / conversion ----------
    /** Compatibility score: -1 = does not fit, higher = more precise. */
    public static int matchScore(Class<?>[] params, boolean varargs, Object[] args) {
        int score = 0;
        int fixed = varargs ? params.length - 1 : params.length;
        if (!varargs && params.length != args.length) return -1;
        for (int i = 0; i < fixed; i++) {
            int s = scoreOne(params[i], args[i]);
            if (s < 0) return -1;
            score += s;
        }
        if (varargs) {
            Class<?> comp = params[params.length - 1].getComponentType();
            if (args.length == params.length && params[params.length - 1].isInstance(args[args.length - 1])) {
                score += 3;
            } else if (args.length == params.length && args[args.length - 1] instanceof JArray ja && arrayScore(comp, ja) >= 0) {
                score += 3;   // a script array as the vararg array itself (like a Java array in Java), not as one element
            } else {
                for (int i = fixed; i < args.length; i++) {
                    int s = scoreOne(comp, args[i]);
                    if (s < 0) return -1;
                    score += s;
                }
            }
            score -= 1; // prefer non-varargs
        }
        return score;
    }

    private static int scoreOne(Class<?> p, Object a) {
        if (a == null) return p.isPrimitive() ? -1 : 1;
        if (a instanceof JavaClass && p == Class.class) return 4;
        Class<?> ac = a.getClass();
        if (p == ac) return 4;
        if (p.isPrimitive()) {
            if (p == int.class) return ac == Integer.class ? 4 : -1;
            if (p == long.class) return ac == Long.class ? 4 : ac == Integer.class ? 2 : -1;
            if (p == double.class) return ac == Double.class ? 4 : ac == Integer.class || ac == Long.class ? 2 : -1;
            if (p == float.class) return ac == Double.class || ac == Integer.class ? 1 : -1;
            if (p == boolean.class) return ac == Boolean.class ? 4 : -1;
            if (p == char.class) return ac == String.class && ((String) a).length() == 1 ? 2 : -1;
            if (p == short.class || p == byte.class) return ac == Integer.class ? 1 : -1;
            return -1;
        }
        if (p.isInstance(a)) return p == Object.class ? 1 : 3;
        if (p == Long.class && ac == Integer.class) return 2;
        if (p == Double.class && (ac == Integer.class || ac == Long.class)) return 2;
        if (p == Float.class && (ac == Double.class || ac == Integer.class)) return 1;
        if (p == Character.class && ac == String.class && ((String) a).length() == 1) return 2;
        if (p.isInterface() && a instanceof JFunction && isFunctional(p)) return 2;
        if (p == Map.class && a instanceof JTable) return 2;
        if (p.isArray() && a instanceof JArray ja) return arrayScore(p.getComponentType(), ja);
        return -1;
    }

    /** A script array fits a Java array if every element converts to its type. */
    private static int arrayScore(Class<?> comp, JArray a) {
        int n = a.size();
        int worst = 3;
        for (int i = 0; i < n; i++) {
            int s = scoreOne(comp, a.get(i));
            if (s < 0) return -1;
            worst = Math.min(worst, s);
        }
        return Math.max(1, worst - 1);   // slightly worse than an exact array-type match
    }

    /** New Java array of component comp from the elements of a script array. */
    public static Object toJavaArray(Class<?> comp, JArray a) {
        int n = a.size();
        Object out = java.lang.reflect.Array.newInstance(comp, n);
        for (int i = 0; i < n; i++) java.lang.reflect.Array.set(out, i, convert(comp, a.get(i)));
        return out;
    }

    private static final Map<Class<?>, Boolean> FUNCTIONAL = new ConcurrentHashMap<>();

    private static boolean isFunctional(Class<?> iface) {
        return FUNCTIONAL.computeIfAbsent(iface, c -> {
            int abstractCount = 0;
            for (Method m : c.getMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) && !isObjectMethod(m)) abstractCount++;
            }
            return abstractCount == 1;
        });
    }

    private static boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static Object[] convertArgs(Class<?>[] params, boolean varargs, Object[] args) {
        Object[] out = new Object[params.length];
        int fixed = varargs ? params.length - 1 : params.length;
        for (int i = 0; i < fixed; i++) out[i] = convert(params[i], args[i]);
        if (varargs) {
            Class<?> arrType = params[params.length - 1];
            if (args.length == params.length && arrType.isInstance(args[args.length - 1])) {
                out[fixed] = args[fixed];
            } else if (args.length == params.length && args[args.length - 1] instanceof JArray ja
                    && arrayScore(arrType.getComponentType(), ja) >= 0) {
                out[fixed] = toJavaArray(arrType.getComponentType(), ja);
            } else {
                Class<?> comp = arrType.getComponentType();
                Object arr = Array.newInstance(comp, args.length - fixed);
                for (int i = fixed; i < args.length; i++) Array.set(arr, i - fixed, convert(comp, args[i]));
                out[fixed] = arr;
            }
        }
        return out;
    }

    public static Object convert(Class<?> p, Object a) {
        if (a == null) return null;
        Class<?> ac = a.getClass();
        if (p == ac) return a;   // the hot case first: this runs per argument of every Java call (Indy's filters)
        // a class value passed where Java takes a Class (or an Object: a List of classes stays a List of Class)
        if (ac == JavaClass.class && p.isAssignableFrom(Class.class)) return ((JavaClass) a).cls();
        if (!p.isPrimitive() && p.isInstance(a)) return a;
        if (p == long.class || p == Long.class) return ((Number) a).longValue();
        if (p == double.class || p == Double.class) return ((Number) a).doubleValue();
        if (p == float.class || p == Float.class) return ((Number) a).floatValue();
        if (p == int.class || p == Integer.class) return ((Number) a).intValue();
        if (p == short.class || p == Short.class) return ((Number) a).shortValue();
        if (p == byte.class || p == Byte.class) return ((Number) a).byteValue();
        if (p == char.class || p == Character.class) return ((String) a).charAt(0);
        if (p == boolean.class) return a;
        if (p == Map.class && a instanceof JTable t) return t.asMap();
        if (p.isArray() && a instanceof JArray ja) return toJavaArray(p.getComponentType(), ja);
        if (p.isInterface() && a instanceof JFunction fn) return proxy(p, fn);
        return a;
    }

    // ---------- MethodHandle versions for inline caches (Indy, FieldCache) ----------
    private static final MethodHandle MH_CONVERT, MH_NORMALIZE;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            MH_CONVERT = l.findStatic(Interop.class, "convert", MethodType.methodType(Object.class, Class.class, Object.class));
            MH_NORMALIZE = l.findStatic(Interop.class, "normalize", MethodType.methodType(Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** MethodHandle (Object)->p with the semantics of convert(p, .). */
    public static MethodHandle converter(Class<?> p) {
        if (p == Object.class) return MethodHandles.identity(Object.class);
        return MethodHandles.insertArguments(MH_CONVERT, 0, p).asType(MethodType.methodType(p, Object.class));
    }

    /** Property getter of a Java object of class cls as (Object)Object: public field, getX()/isX(); null if none. */
    public static MethodHandle propertyGetter(Class<?> cls, String name) {
        try {
            MethodHandle mh = null;
            try {
                Field f = cls.getField(name);
                if (!Modifier.isStatic(f.getModifiers())) {
                    if (!Access.current().fieldAllowed(f, cls)) return null;   // cache miss -> generic path -> a clear error
                    mh = MethodHandles.publicLookup().unreflectGetter(f);
                }
            } catch (NoSuchFieldException ignored) {
            }
            if (mh == null) {
                Method[] g = candidates(cls, "get" + cap(name), 0, false);
                if (g.length == 0) g = candidates(cls, "is" + cap(name), 0, false);
                if (g.length == 0) return null;
                mh = MethodHandles.publicLookup().unreflect(g[0]);
            }
            mh = mh.asType(MethodType.methodType(Object.class, Object.class));
            return MethodHandles.filterReturnValue(mh, MH_NORMALIZE);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** Property setter as (Object, Object)void: public field or setX(v); null if none. */
    public static MethodHandle propertySetter(Class<?> cls, String name) {
        try {
            MethodHandle mh;
            Class<?> vt;
            Field f = null;
            try { f = cls.getField(name); } catch (NoSuchFieldException ignored) { }
            if (f != null && !Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) {
                if (!Access.current().fieldAllowed(f, cls)) return null;
                mh = MethodHandles.publicLookup().unreflectSetter(f);
                vt = f.getType();
            } else {
                Method[] st = candidates(cls, "set" + cap(name), 1, false);
                if (st.length == 0) return null;
                mh = MethodHandles.publicLookup().unreflect(st[0]);
                vt = st[0].getParameterTypes()[0];
            }
            mh = MethodHandles.filterArguments(mh, 1, converter(vt));
            return mh.asType(MethodType.methodType(void.class, Object.class, Object.class));
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * A Jumper function as an implementation of a functional interface (Runnable, Function, Comparator...).
     *
     * <p>Tried and rejected: an inline cache on the interface's single method, to remove the
     * three reflective {@link Method} queries (declaring class, default, return type) from the
     * hot path. No gain - these are field reads and C2 folds them anyway: 5.68 vs 6.70 ns on
     * Function.apply, i.e. within noise.
     *
     * <p>Measurement of where the cost really is (20M calls, JDK 21): via Proxy 7.09 ns,
     * hand-written adapter 5.77, {@link JFunction#call} directly 5.77, the equivalent Java lambda 3.11.
     * So the Proxy itself costs 1.3 ns, and 2.66 is the generic call path through {@code Object[]}.
     * Replacing Proxy with a generated adapter would give about 8% of the gap in interop_callback,
     * under one percent of geomean; it only makes sense together with a typed entry into the
     * compiled body that bypasses {@code Object[]}.
     */
    public static Object proxy(Class<?> iface, JFunction fn) {
        // The interface itself need not be allowed: implementing it is not the same as calling its
        // methods; the script gains nothing from it, the host does the calling.
        Access policy = Access.current();
        if (policy == Access.ALL && fn instanceof me.padej.jumper.ast.FunctionNode.ScriptFunction sf) {
            Object b = me.padej.jumper.jit.BridgeGen.bridge(iface, sf);   // direct bridge when possible (only without a policy: the bridge calls the body bypassing ScriptFunction.call)
            if (b != null) return b;
        }
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, margs) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "proxy(" + fn + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == margs[0];
                    default -> null;
                };
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, margs);
            // callback from Java (possibly from another thread): the policy is the one the proxy was created under
            Access prev = Access.enter(policy);
            try {
                Object r = fn.call(margs == null ? new Object[0] : margs);
                Class<?> rt = method.getReturnType();
                return rt == void.class ? null : convert(rt, r);
            } finally {
                Access.exit(prev);
            }
        });
    }
}
