package me.padej.jumper.workspace;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Functions a script declares for its host to call - `void onJoin(dyn p) { ... }` - as the host's descriptor
 * names them ({@link HostDescriptor}, key `hooks`):
 *
 * <ul>
 *   <li>an interface (or several): each of its methods is a function a script may implement; the script's
 *       function goes to that method, like an implementation to the interface method it implements;</li>
 *   <li>a table `{ onJoin: "a.Class#method" }`: one by one, each to a Java method (or only a class).</li>
 * </ul>
 */
public final class Hooks {
    private Hooks() {}

    /**
     * A hook: the class and the method a script function of that name implements. `methods` - every method
     * of that name (the overloads); empty when the descriptor names only a class.
     */
    public record Hook(String name, Class<?> owner, List<Method> methods) {
        /** The overload taking `arity` arguments, else the first; null if only a class is named. */
        public Method method(int arity) {
            for (Method m : methods) if (m.getParameterCount() == arity) return m;
            return methods.isEmpty() ? null : methods.get(0);
        }

        /** Does some overload take `arity` arguments (or is no method named at all)? */
        public boolean takes(int arity) {
            if (methods.isEmpty()) return true;
            for (Method m : methods) if (m.getParameterCount() == arity) return true;
            return false;
        }
    }

    /** The hook `name` in the context of a file, or null when its host does not call such a function. */
    public static Hook find(FileContext ctx, String name, ClassLoader loader) {
        String spec = ctx.hooks().get(name);
        if (spec != null) {
            int hash = spec.indexOf('#');
            Class<?> c = Members.load((hash < 0 ? spec : spec.substring(0, hash)).strip(), loader);
            if (c == null) return null;
            String m = hash < 0 ? "" : spec.substring(hash + 1).replaceAll("\\(.*", "").strip();
            return new Hook(name, c, m.isEmpty() ? List.of() : named(c, m));
        }
        for (String type : ctx.hookTypes()) {
            Class<?> c = Members.load(type.strip(), loader);
            if (c == null) continue;
            List<Method> ms = named(c, name);
            if (!ms.isEmpty()) return new Hook(name, c, ms);
        }
        return null;
    }

    /** Every hook name the host calls (for completion): the interfaces' methods, then the table's names. */
    public static List<String> names(FileContext ctx, ClassLoader loader) {
        List<String> out = new ArrayList<>();
        for (String type : ctx.hookTypes()) {
            Class<?> c = Members.load(type.strip(), loader);
            if (c == null) continue;
            Method[] ms = c.getMethods();
            java.util.Arrays.sort(ms, java.util.Comparator.comparing(Method::getName));   // reflection has no order
            for (Method m : ms) {
                if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) continue;
                if (!out.contains(m.getName())) out.add(m.getName());
            }
        }
        for (String n : ctx.hooks().keySet()) if (!out.contains(n)) out.add(n);
        return out;
    }

    /** `void onJoin(ScriptPlayer player)` - with parameter names when the class keeps them. */
    public static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
        var ps = m.getParameters();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ps[i].getType().getSimpleName());
            if (ps[i].isNamePresent()) sb.append(' ').append(ps[i].getName());
        }
        return sb.append(')').toString();
    }

    private static List<Method> named(Class<?> c, String name) {
        List<Method> out = new ArrayList<>();
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && !Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass() != Object.class) out.add(m);
        }
        if (out.isEmpty()) {
            // a non-public class or method the table names: what it declares itself
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) out.add(m);
        }
        out.sort(java.util.Comparator.comparingInt(Method::getParameterCount));
        return out;
    }
}
