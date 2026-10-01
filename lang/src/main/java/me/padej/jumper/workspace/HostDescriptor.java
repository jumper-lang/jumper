package me.padej.jumper.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a jar that embeds Jumper lays out its files: the {@value JarIndex#DESCRIPTOR} resource of that jar,
 * written by the plugin's author at build time. It is a Jumper config, so it is data - reading it never
 * runs anything:
 *
 * <pre>
 * String name = "Jumper";                  // for messages; default: the jar's file name
 * String root = "..";                      // the folder the paths below are relative to, from the jar's folder
 *                                          //   (default ".." - a jar in server/plugins/ -> server/)
 * dyn scripts = ["scripts/*.jmp"];         // its scripts: globs (* ** ?), one string or an array
 * String access = "scripts.jma";           // the access policy those scripts run under
 * dyn configs = ["config.jmc"];            // its configs
 * dyn globals = { server: "example.jumper.api.ScriptServer", log: "function" };   // what it defines for scripts
 * String hooks = "example.jumper.api.ScriptEvents";   // functions a script may declare and the host calls:
 *                                          //   a Java interface (or several - an array) whose methods they
 *                                          //   implement, or a table { onEnable: "a.Class#method" }
 * </pre>
 *
 * Two plugins that embed Jumper each carry their own descriptor, and each script belongs to the one whose
 * globs match it - that is how a tool tells their files apart without any folder convention.
 */
public record HostDescriptor(Path jar, String name, Path root, List<String> scripts, Path access,
                             List<String> configs, Map<String, String> globals, List<String> hookTypes,
                             Map<String, String> hooks, List<String> problems) {

    private static final java.util.Set<String> KEYS = java.util.Set.of("name", "root", "scripts", "access", "configs", "globals", "hooks");

    /** Reads the descriptor text of `jar`; what is wrong with it goes to {@link #problems()}, never an exception. */
    public static HostDescriptor parse(Path jar, String source) {
        List<String> problems = new ArrayList<>();
        String fileName = jar.getFileName().toString();
        String name = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String root = "..";
        List<String> scripts = List.of(), configs = List.of(), hookTypes = List.of();
        String access = null;
        Map<String, String> globals = new LinkedHashMap<>(), hooks = new LinkedHashMap<>();
        Object v;
        try {
            v = me.padej.jumper.interp.Config.parse(source);
        } catch (RuntimeException e) {
            problems.add(JarIndex.DESCRIPTOR + ": " + e.getMessage());
            v = null;
        }
        if (v instanceof me.padej.jumper.runtime.JTable t) {
            for (Object k : t.keys()) {
                String key = String.valueOf(k);
                Object val = t.get(k);
                switch (key) {
                    case "name" -> name = str(val, key, name, problems);
                    case "root" -> root = str(val, key, root, problems);
                    case "access" -> access = str(val, key, null, problems);
                    case "scripts" -> scripts = strings(val, key, problems);
                    case "configs" -> configs = strings(val, key, problems);
                    case "globals" -> table(val, key, globals, "{ name: \"type\" }", problems);
                    case "hooks" -> {
                        if (val instanceof me.padej.jumper.runtime.JTable) table(val, key, hooks, "", problems);
                        else if (val instanceof String || val instanceof List<?>) hookTypes = strings(val, key, problems);
                        else problems.add("hooks: expected an interface name, an array of them or a table { onEnable: \"a.Class#method\" }");
                    }
                    default -> problems.add("unknown key '" + key + "' (known: " + String.join(", ", new java.util.TreeSet<>(KEYS)) + ")");
                }
            }
        } else if (v != null) {
            problems.add("expected top-level variables, got " + me.padej.jumper.runtime.Ops.typeName(v));
        }
        Path jarDir = jar.toAbsolutePath().normalize().getParent();
        Path rootPath = jarDir.resolve(root).normalize();
        return new HostDescriptor(jar, name, rootPath, scripts, access == null ? null : rootPath.resolve(access).normalize(),
                configs, Collections.unmodifiableMap(globals), hookTypes, Collections.unmodifiableMap(hooks), List.copyOf(problems));
    }

    private static void table(Object v, String key, Map<String, String> into, String shape, List<String> problems) {
        if (v instanceof me.padej.jumper.runtime.JTable t) {
            for (Object k : t.keys()) into.put(String.valueOf(k), String.valueOf(t.get(k)));
        } else problems.add(key + ": expected a table " + shape);
    }

    private static String str(Object v, String key, String dflt, List<String> problems) {
        if (v instanceof String s) return s;
        problems.add(key + ": expected a string");
        return dflt;
    }

    private static List<String> strings(Object v, String key, List<String> problems) {
        if (v instanceof String s) return List.of(s);
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof String s) out.add(s);
                else problems.add(key + ": expected strings, got " + me.padej.jumper.runtime.Ops.typeName(o));
            }
            return List.copyOf(out);
        }
        problems.add(key + ": expected a string or an array of strings");
        return List.of();
    }

    /** Does one of `globs` (relative to the root) match the file? Returns the matching glob or null. */
    String match(List<String> globs, Path file) {
        Path f = file.toAbsolutePath().normalize();
        if (!f.startsWith(root)) return null;
        String rel = root.relativize(f).toString().replace('\\', '/');
        for (String g : globs) if (Glob.matches(g, rel)) return g;
        return null;
    }
}
