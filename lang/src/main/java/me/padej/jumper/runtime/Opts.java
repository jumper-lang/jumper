package me.padej.jumper.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of Jumper optimizations: the one place that lists everything that can be switched off.
 *
 * <h2>Why</h2>
 *
 * Optimizations accumulate, and each one gets its own flag wherever it was written. A dozen
 * changes later nobody remembers what can be switched off at all or what it is called. Yet the
 * switch is needed all the time: it is the emergency rollback and - above all - the only reliable
 * way to measure the effect of a change.
 *
 * <p>Measuring with two separate runs does not work: the machine drifts between sessions, and
 * the measured reproducibility of the rig is about 13% (the same bytecode gave 1.67x and 1.48x).
 * The difference being looked for is often of the same order. The only reliable way is both
 * variants back to back, on one machine, in one session - that is what the {@code benchAB} task
 * does, and it takes the list of flags from here.
 *
 * <h2>How to add an optimization</h2>
 *
 * Add a line to {@link #ALL} and read the value via {@link #on(String)} instead of your own
 * {@code System.getProperty} call. It then automatically shows up in {@code jmp --opts}, in the
 * correctness gate and in A/B.
 *
 * <h2>The rule</h2>
 *
 * <b>A disabled optimization must give the same answers as an enabled one.</b> If not, it is
 * not an optimization but a semantic change, and it belongs in the language spec, not here.
 * The correctness gate checks this on all scripts and on all checksums of the suite.
 */
public final class Opts {
    private Opts() {}

    /** One optimization: its name, the property that disables it, what it does. */
    public record Opt(String name, String property, boolean defaultOn, String about) {
        public boolean enabled() {
            String v = System.getProperty(property);
            if (v == null) return defaultOn;
            return !offValue(v);
        }
    }

    /** `0`, `off`, `false` (any case, surrounding spaces ignored): the value of a switch that means "off". */
    public static boolean offValue(String v) {
        if (v == null) return false;
        String s = v.trim();
        return s.equals("0") || s.equalsIgnoreCase("off") || s.equalsIgnoreCase("false");
    }

    /**
     * A diagnostic switch (JMP_DEBUG, -Djmp.debug): on when set to anything but an off value,
     * so `-Djmp.debug` alone turns it on and `-Djmp.debug=0` keeps it off. The property wins over the variable.
     */
    public static boolean flag(String property, String env) {
        String v = System.getProperty(property);
        if (v == null) v = System.getenv(env);
        return v != null && !offValue(v);
    }

    /**
     * All optimizations that can be disabled individually.
     *
     * <p>Tier 1 is here too, although it is a whole tier rather than an "optimization": its
     * switch is of the same nature and it takes part in A/B like the others.
     */
    public static final List<Opt> ALL = List.of(
            new Opt("tier1", "jmp.tier1", true,
                    "bytecode compilation; jmp.tier1=force compiles everything on the first call"),
            new Opt("genclass", "jmp.genclass", true,
                    "a script class instance is a generated JVM class with real fields, "
                            + "not a JTable with arrays (one allocation instead of two, getfield instead of two accesses)"),
            new Opt("unbox", "jmp.unbox", true,
                    "a dynamic local that only ever receives one primitive type lives in a primitive "
                            + "JVM local, and operations on it are typed (dyn i = 0 in a loop is not boxed)"),
            new Opt("tableprims", "jmp.tableprims", true,
                    "a plain-table slot that received a primitive while the literal was built is stored "
                            + "unboxed, and Tier 1 reads and writes it directly under a shape guard "
                            + "({ x: 0.5 } and e.x += e.vx stop boxing doubles)"),
            new Opt("cellsite", "jmp.cellsite", true,
                    "a call by top-level name in ScriptEngine/REPL is an invokedynamic with the function body "
                            + "under the cell's SwitchPoint: no guard on the hot path, a write to the cell invalidates "
                            + "the point (fib recursion in the engine runs as in Interpreter)"),
            new Opt("bridge", "jmp.bridge", true,
                    "a script function as an implementation of a Java functional interface is a generated "
                            + "bridge class calling the body directly (list.forEach(x -> ...) without Proxy and Object[])"),
            new Opt("fieldcall", "jmp.fieldcall", true,
                    "a call of a plain table's function field (t.f(x) with { f: (x) -> ... }) is a direct call of the body "
                            + "under a guard on the shape and function node, not the generic path via JFunction.call"),
            new Opt("dual", "jmp.dual", true,
                    "a dyn local that holds numbers gets a primitive JVM local next to the Object one plus a flag; "
                            + "arithmetic over such locals and table fields runs unboxed under one guard, everything else re-boxes"),
            new Opt("dualacc", "jmp.dualacc", true,
                    "acc += <dynamic value> on a dual local stays in the primitive half when the value is a box "
                            + "the slot's kind absorbs exactly (dyn sum = 0L; sum += t.f(i) without Long.valueOf per iteration)"),
            new Opt("getsite", "jmp.getsite", true,
                    "a field read obj.name outside a guarded region is an invokedynamic with a chain of up to 8 "
                            + "(class, shape) layers, each a constant-index read or the constant null for a missing key, "
                            + "not the shared monomorphic FieldCache (binary_trees: n.left on node/leaf stops missing)"),
            new Opt("intentry", "jmp.intentry", true,
                    "a function whose dyn parameters the code treats as ints gets a second static body bodyI(int...) "
                            + "with those parameters in int locals; int arguments call it directly (fib(n - 1)), body forwards Integers to it"),
            new Opt("foldcall", "jmp.foldcall", true,
                    "`obj.m(x) % k` (or + - * /, or the call alone) needed as a primitive, obj of no static class: the "
                            + "method site returns the primitive and applies the operation per target - an int-returning method "
                            + "without a box"),
            new Opt("rawarray", "jmp.rawarray", true,
                    "a script array element inside a guarded region is read and written without re-testing the "
                            + "array's kind (the region's guard proved it): only the bounds check remains"),
            new Opt("litsite", "jmp.litsite", true,
                    "a table literal with untyped values is an invokedynamic with a layer per pattern of the values' kinds: "
                            + "the typed shape and the layout class's constructor are constants, no Object[] and no typedBy per table"),
            new Opt("concat", "jmp.concat", true,
                    "a chain of string + is one StringConcatFactory site (as javac does): one allocation for \"key\" + i, "
                            + "not Integer.toString plus a String.concat per step"),
            new Opt("layout", "jmp.layout", true,
                    "a table literal is an instance of a class generated for its typed shape, with the slots as real fields: "
                            + "one allocation instead of three, and a field under a region guard is a getfield, not prims[i]")
    );

    public static Opt byName(String name) {
        for (Opt o : ALL) {
            if (o.name().equals(name) || o.property().equals(name)) return o;
        }
        return null;
    }

    public static boolean on(String name) {
        Opt o = byName(name);
        return o == null || o.enabled();
    }

    /** State line for reports and the log: `tier1=on genclass=on unbox=off`. */
    public static String state() {
        StringBuilder b = new StringBuilder();
        for (Opt o : ALL) {
            if (b.length() > 0) b.append(' ');
            b.append(o.name()).append('=').append(o.enabled() ? "on" : "off");
        }
        return b.toString();
    }

    /** `jmp --opts`: what can be switched off at all. */
    public static String describe() {
        StringBuilder b = new StringBuilder("Optimizations (disable with -D<property>=0)\n\n");
        int w = 0;
        for (Opt o : ALL) w = Math.max(w, o.property().length());
        for (Opt o : ALL) {
            b.append(String.format("  %-" + w + "s  %-3s  %s%n",
                    o.property(), o.enabled() ? "on" : "off", wrap(o.about(), w + 9)));
        }
        return b.toString();
    }

    private static String wrap(String s, int indent) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() + word.length() > 72) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        out.add(line.toString());
        return String.join("\n" + " ".repeat(indent), out);
    }
}
