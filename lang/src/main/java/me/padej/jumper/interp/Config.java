package me.padej.jumper.interp;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.parser.Parser;
import me.padej.jumper.runtime.Access;
import me.padej.jumper.runtime.JArray;
import me.padej.jumper.runtime.JTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config files (*.jmc, JuMper Config). The language of scripts, cut down to what a config needs:
 * values, variables, expressions, table and array literals, if/else, switch, ternaries, return
 * (see Parser.config). No loops, functions, lambdas, classes, import or try - and no Java at all:
 * the file runs under {@link Access#none()}.
 *
 * <pre>
 * // server.jmc - the top-level variables are the config
 * int port = 25565;
 * boolean isDev = false;
 * int permLevel = isDev ? 10 : 0;
 * dyn motd = {"Welcome", "to", "the server"};     // {a, b} is an array, as a Java initializer
 * dyn database = { url: "jdbc:mysql://localhost/mc", pool: { min: 2, max: 10 } };
 * </pre>
 * gives the table {@code {port: 25565, isDev: false, permLevel: 0, motd: [...], database: {...}}}, in
 * declaration order. A file may instead end with {@code return <value>;}, and then that value is the config.
 *
 * <p>Configs like the one above - declarations, assignments, if/else, operators over literals and the
 * variables above - are read by {@link ConfigReader} in one pass, without the parser and the interpreter;
 * anything else takes the full path. Both give the same values (tested against each other).
 *
 * <p>Three kinds of files, three extensions: {@code .jmp} scripts, {@code .jma} access policies,
 * {@code .jmc} configs.
 */
public final class Config {
    private Config() {}

    /** Read and evaluate a *.jmc file. */
    public static Object load(Path file) throws IOException {
        warmUp();
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the first load in a process

    /**
     * The first config a process loads spends most of its time in the JVM, not in reading: loading,
     * verifying and linking the reader's classes and resolving their call sites, all interpreted. Reading
     * the file (the first file read in a process loads its own share of JDK classes) does not need any of
     * it - so the first load has another thread load and initialize the reader's classes while this one
     * reads the file. When the text is there they are ready or half-way. Once per process, with 4+ cores
     * (-Djmp.configwarmup=1 forces it, =0 turns it off).
     */
    private static void warmUp() {
        if (warmed) return;
        warmed = true;
        String w = System.getProperty("jmp.configwarmup");
        // on two cores the second thread competes with this one and the JIT: measured slower there
        if (!FAST || "0".equals(w) || w == null && Runtime.getRuntime().availableProcessors() < 4) return;
        Thread t = new Thread(null, new Runnable() {   // not a lambda: an invokedynamic bootstrap costs more than it saves
            @Override
            public void run() {
                ClassLoader l = Config.class.getClassLoader();
                for (String c : WARM_CLASSES) {
                    try { Class.forName(c, true, l); } catch (Throwable ignored) { return; }
                }
            }
        }, "jmc-warmup", 1 << 20);
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean warmed;

    private static final String[] WARM_CLASSES = {"me.padej.jumper.interp.ConfigReader", "me.padej.jumper.ast.VarType",
            "me.padej.jumper.runtime.Shape", "me.padej.jumper.runtime.JTable", "me.padej.jumper.runtime.JTable$Plain",
            "me.padej.jumper.runtime.JArray"};


    /** Evaluate config text. ParseError for what a config may not contain, JmpError for runtime errors. */
    public static Object parse(String source) {
        if (FAST) {
            int slot = TEMPLATE ? templateSlot(source) : 0;
            Template t = TEMPLATE ? TEMPLATES[slot] : null;
            boolean again = t != null && t.text.equals(source);
            if (again && t.value != null) return copy(t.value);
            Object v = ConfigReader.read(source);
            if (v != ConfigReader.FALLBACK) {
                // the first time a text is seen only the text is kept; read a second time, it gets a prototype
                // (a file edited on every read never pays for the copy)
                if (TEMPLATE) TEMPLATES[slot] = new Template(source, again ? copy(v) : null);
                return v;
            }
        }
        return parseFull(source);
    }

    // ------------------------------------------------------------------ templates of unchanged text

    /** As Opts.offValue - repeated here so that the cold config path does not load Opts. */
    private static boolean off(String v) {
        if (v == null) return false;
        String t = v.trim();
        return t.equals("0") || t.equalsIgnoreCase("off") || t.equalsIgnoreCase("false");
    }

    /**
     * A config read again with exactly the same text - a /reload with nothing edited, a plugin reading
     * its config on every event - is not read at all: its value is a pure function of the text (a
     * fast-path config has no calls, no Java, no globals), so the value built the first time is kept as
     * a prototype and every read of the same text gets a fresh copy of it - new tables and arrays the
     * caller owns and may change, the immutable strings and numbers shared. One comparison of the text
     * (vectorized) and the copying: no scanning at all. A different text - any edit - is read as usual;
     * it becomes the prototype when it is read a second time unchanged. Only fast-path results: a config that needs the interpreter may one day
     * call something that is not a pure function. -Djmp.configtemplate=0 turns it off.
     */
    private static final boolean TEMPLATE = !off(System.getProperty("jmp.configtemplate"));

    private record Template(String text, Object value) {}

    private static final Template[] TEMPLATES = new Template[64];

    private static int templateSlot(String text) {
        int n = text.length();
        int h = n * 31 + (n > 0 ? text.charAt(n - 1) + text.charAt(n >> 1) * 7 : 0);
        return (h ^ (h >>> 6)) & (TEMPLATES.length - 1);
    }

    /** A deep copy of the tables and arrays of a config value; strings, numbers and booleans are shared. */
    private static Object copy(Object v) {
        if (v instanceof JTable.Plain t) {
            JTable.Plain c = t.copyStorage();
            java.util.LinkedHashMap<Object, Object> d = c.dictionary();
            if (d != null) {
                for (java.util.Map.Entry<Object, Object> e : d.entrySet()) {
                    Object x = e.getValue();
                    if (x instanceof JTable || x instanceof JArray) e.setValue(copy(x));
                }
            } else {
                Object[] vs = c.values;
                for (int i = 0; i < vs.length; i++) {
                    Object x = vs[i];
                    if (x instanceof JTable || x instanceof JArray) vs[i] = copy(x);
                }
            }
            return c;
        }
        if (v instanceof JArray a) {
            JArray c = a.copyStorage();
            if (c.objs != null) {
                Object[] os = c.objs;
                for (int i = 0; i < c.size; i++) {
                    Object x = os[i];
                    if (x instanceof JTable || x instanceof JArray) os[i] = copy(x);
                }
            }
            return c;
        }
        return v;
    }

    /** Pure-data configs skip the language entirely (ConfigReader); -Djmp.fastconfig=0 sends everything the full way. */
    // Not in runtime.Opts: those are Tier 1 optimizations the bench gate flips on every benchmark,
    // and no benchmark loads a config.
    private static final boolean FAST = !off(System.getProperty("jmp.fastconfig"));

    /** The full path: parse in config mode, run under Access.none(). Also what the fast path is tested against. */
    public static Object parseFull(String source) {
        Interpreter jj = new Interpreter().access(Access.none());
        FunctionNode main = jj.parse(() -> new Parser(source, jj.globals()).source(null, jj.modules()).config().parseProgram());
        Script s = new Script(main);
        Object r = s.run();
        if (r != null) return r;
        JTable t = JTable.plain(s.names().size());
        for (String name : s.names()) t.put(name, s.get(name));
        return t;
    }
}
