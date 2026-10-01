package me.padej.jumper.runtime;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/** Builtin functions. Resolved to constants at parse time - access is free. */
public final class Builtins {
    private Builtins() {}

    /** Where print/println write. Replaced in tests. */
    public static volatile PrintStream out = System.out;

    /** Singletons the compiler recognizes by reference and replaces with a direct call (Exprs.BuiltinCall). */
    public static final JFunction LEN = JFunction.of("len", args -> Ops.len(arg(args, 0)));

    // int(x)/long(x)/double(x): a number converts, a string parses (null when it does not), else an error.
    // Kept as static functions so the compiler can call the same code for a dyn argument (BuiltinCall).
    public static Object toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        if (v instanceof Boolean b) return b ? 1 : 0;
        throw new JmpError("int(): cannot convert " + Ops.typeName(v));
    }

    public static Object toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        throw new JmpError("long(): cannot convert " + Ops.typeName(v));
    }

    public static Object toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        throw new JmpError("double(): cannot convert " + Ops.typeName(v));
    }

    public static final JFunction INT = JFunction.of("int", args -> toInt(arg(args, 0)));
    public static final JFunction LONG = JFunction.of("long", args -> toLong(arg(args, 0)));
    public static final JFunction DOUBLE = JFunction.of("double", args -> toDouble(arg(args, 0)));
    public static final JFunction STR = JFunction.of("str", args -> Ops.str(arg(args, 0)));
    /** array(n, fill): the compiler recognizes it to learn the element kind of the array (Unbox.arrayKind). */
    public static final JFunction ARRAY = JFunction.of("array", args -> {
        int n = args.length > 0 ? ((Number) args[0]).intValue() : 0;
        Object fill = args.length > 1 ? args[1] : null;
        return JArray.filled(n, fill);
    });
    public static final JFunction TYPE = JFunction.of("type", args -> Ops.typeName(arg(args, 0)));

    public static Map<String, Object> globals() {
        Map<String, Object> g = new HashMap<>();
        g.put("print", JFunction.of("print", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(Ops.str(args[i]));
            }
            out.print(sb);
            return null;
        }));
        g.put("println", JFunction.of("println", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(Ops.str(args[i]));
            }
            out.println(sb);
            return null;
        }));
        g.put("len", LEN);
        g.put("str", STR);
        g.put("type", TYPE);
        g.put("int", INT);
        g.put("long", LONG);
        g.put("double", DOUBLE);
        g.put("nanoTime", JFunction.of("nanoTime", args -> System.nanoTime()));
        g.put("millis", JFunction.of("millis", args -> System.currentTimeMillis()));
        g.put("keys", JFunction.of("keys", args -> {
            Object v = arg(args, 0);
            if (v instanceof JTable t) return new JArray(t.keys().toArray());
            if (v instanceof Map<?, ?> m) return new JArray(m.keySet().toArray());
            throw new JmpError("keys(): expected table, got " + Ops.typeName(v));
        }));
        g.put("error", JFunction.of("error", args -> {
            throw new JmpError(Ops.str(arg(args, 0)));
        }));
        g.put("assert", JFunction.of("assert", args -> {
            if (!Ops.truthy(arg(args, 0)))
                throw new JmpError(args.length > 1 ? "Assertion failed: " + Ops.str(args[1]) : "Assertion failed");
            return args.length > 0 ? args[0] : null;
        }));
        g.put("format", JFunction.of("format", args -> {
            if (args.length == 0) return "";
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return String.format(Ops.str(args[0]), rest);
        }));
        g.put("range", JFunction.of("range", args -> {
            int from = 0, to, step = 1;
            if (args.length == 1) to = ((Number) args[0]).intValue();
            else {
                from = ((Number) args[0]).intValue();
                to = ((Number) args[1]).intValue();
                if (args.length > 2) step = ((Number) args[2]).intValue();
            }
            if (step == 0) throw new JmpError("range(): step must not be 0");
            JArray a = new JArray(Math.max(4, Math.abs((to - from) / step) + 1));
            if (step > 0) for (int i = from; i < to; i += step) a.add(i);
            else for (int i = from; i > to; i += step) a.add(i);
            return a;
        }));
        g.put("array", ARRAY);
        g.put("table", JFunction.of("table", args -> JTable.plain()));
        g.put("isa", JFunction.of("isa", args -> {
            Object v = arg(args, 0), c = arg(args, 1);
            if (c instanceof JClass jc) return v instanceof JTable t && t.cls() != null && t.cls().isSubclassOf(jc);
            if (c instanceof JavaClass jc) return jc.cls().isInstance(v);
            throw new JmpError("isa(): second argument must be a class, got " + Ops.typeName(c));
        }));
        return g;
    }

    private static Object arg(Object[] args, int i) {
        return i < args.length ? args[i] : null;
    }
}
