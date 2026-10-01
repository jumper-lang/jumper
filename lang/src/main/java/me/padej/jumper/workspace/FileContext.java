package me.padej.jumper.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What surrounds one Jumper file: what kind of file it is, whose it is, and the world it will run in -
 * the access policy, the names the host defines, the classes it can see. Found by {@link Workspace#contextFor}.
 *
 * @param root      the folder the host's paths are relative to (the server folder); for a file no host and no
 *                  policy claims - its own folder
 * @param host      the embedding host whose descriptor claims the file, or null (found by convention, or alone)
 * @param access    the policy file the script runs under (a script, and a config's host policy), or null
 * @param globals   names the host defines for its scripts -> type (a Java class name, "function", "dyn")
 * @param classpath the jars whose classes the file can name: the server's and its host's, not other hosts'; empty
 *                  when no server folder is known
 * @param notes     how the context was found when it is not certain - for a tool to show, not errors
 */
public record FileContext(Kind kind, Path file, Path root, HostDescriptor host, Path access,
                          Map<String, String> globals, List<Path> classpath, List<String> notes) {

    /** Interfaces whose methods a script of its host implements (see {@link Hooks}); empty for other files. */
    public List<String> hookTypes() {
        return kind == Kind.SCRIPT && host != null ? host.hookTypes() : List.of();
    }

    /** Functions of a script its host calls, one by one: name -> "a.Class#method"; empty for other files. */
    public Map<String, String> hooks() {
        return kind == Kind.SCRIPT && host != null ? host.hooks() : Map.of();
    }

    public enum Kind {
        /** .jmp - a script: runs under its host's policy with the host's globals. */
        SCRIPT,
        /** .jmc - a config: data, no policy, no globals. */
        CONFIG,
        /** .jma - an access policy: a script that sees only `Policy`. */
        POLICY,
        /** Anything else. */
        OTHER;

        public static Kind of(Path file) {
            String n = file.getFileName().toString();
            if (n.endsWith(".jmp")) return SCRIPT;
            if (n.endsWith(".jmc")) return CONFIG;
            if (n.endsWith(".jma")) return POLICY;
            return OTHER;
        }
    }

    /** The indexes of the classpath jars (read once, then from the cache). A jar that cannot be read is skipped. */
    public List<JarIndex> index(IndexCache cache) {
        java.util.List<JarIndex> out = new java.util.ArrayList<>();
        for (Path jar : classpath) {
            try { out.add(cache.get(jar)); } catch (java.io.IOException e) { /* unreadable jar: no classes from it */ }
        }
        return out;
    }
}
