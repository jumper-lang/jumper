package me.padej.jumper.workspace;

import me.padej.jumper.runtime.Access;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * What a script can reach on a Java class, for tools: public methods and fields as the interpreter sees them
 * (the same getters make properties), filtered by the policy the way a call is. Classes come from the
 * checker's class loader without being initialized; a class whose members cannot be listed (a missing
 * dependency in its signatures) simply has none.
 */
public final class Members {
    private Members() {}

    /** The class, not initialized, or null if it is not there. */
    public static Class<?> load(String name, ClassLoader loader) {
        if (name == null || name.isEmpty()) return null;
        try {
            return Class.forName(name, false, loader != null ? loader : Members.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** Public methods callable on an instance (statics = false) or on the class (statics = true), sorted by name. */
    public static List<Method> methods(Class<?> c, boolean statics) {
        try {
            List<Method> out = new ArrayList<>();
            for (Method m : c.getMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                if (statics && !Modifier.isStatic(m.getModifiers())) continue;
                out.add(m);
            }
            out.sort(Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount));
            return out;
        } catch (LinkageError e) {
            return List.of();
        }
    }

    /** The methods the policy lets a script call on c (null policy - no policy, everything). */
    public static List<Method> allowed(List<Method> methods, Class<?> c, Access policy) {
        if (policy == null || methods.isEmpty()) return methods;
        return Arrays.asList(policy.filter(methods.toArray(new Method[0]), c));
    }

    public static List<Method> named(List<Method> methods, String name) {
        List<Method> out = new ArrayList<>();
        for (Method m : methods) if (m.getName().equals(name)) out.add(m);
        return out;
    }

    public static List<Field> fields(Class<?> c, boolean statics, Access policy) {
        try {
            List<Field> out = new ArrayList<>();
            for (Field f : c.getFields()) {
                if (statics && !Modifier.isStatic(f.getModifiers())) continue;
                if (policy != null && !policy.fieldAllowed(f, c)) continue;
                out.add(f);
            }
            out.sort(Comparator.comparing(Field::getName));
            return out;
        } catch (LinkageError e) {
            return List.of();
        }
    }

    /**
     * The type of `obj.name` read as a value, as Interop reads it: a public field, else getName()/isName().
     * Null if there is no such member.
     */
    public static Class<?> propertyType(Class<?> c, String name) {
        try {
            for (Field f : c.getFields()) if (f.getName().equals(name)) return f.getType();
            String cap = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() == 0 && (m.getName().equals("get" + cap) || m.getName().equals("is" + cap))) return m.getReturnType();
            }
        } catch (LinkageError e) {
            // no members
        }
        return null;
    }

    /** The common return type of the methods, or null if they differ (an overload returning something else). */
    public static Class<?> returnType(List<Method> methods) {
        Class<?> t = null;
        for (Method m : methods) {
            if (t == null) t = m.getReturnType();
            else if (t != m.getReturnType()) return null;
        }
        return t;
    }

    /** `int size()`, `static double max(double, double)`, `void log(String)`. */
    public static String signature(Method m) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isStatic(m.getModifiers())) sb.append("static ");
        sb.append(simple(m.getReturnType())).append(' ').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(m.isVarArgs() && i == ps.length - 1 ? simple(ps[i].getComponentType()) + "..." : simple(ps[i]));
        }
        return sb.append(')').toString();
    }

    public static String signature(Field f) {
        return (Modifier.isStatic(f.getModifiers()) ? "static " : "") + simple(f.getType()) + " " + f.getName();
    }

    /** A type as source code writes it: `String`, `int[]`, `Map.Entry`. */
    public static String simple(Class<?> c) {
        if (c.isArray()) return simple(c.getComponentType()) + "[]";
        String n = c.getName();
        n = n.substring(n.lastIndexOf('.') + 1);
        return n.replace('$', '.');
    }
}
