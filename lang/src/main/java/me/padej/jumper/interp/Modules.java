package me.padej.jumper.interp;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.parser.Parser;
import me.padej.jumper.runtime.JmpError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Module loader: `import "utils.jmp";`.
 *
 * A module is an ordinary script. It is parsed and executed once per interpreter (on the first
 * import, i.e. while the importing file is being parsed), and its top-level declarations become
 * visible to the importer: classes as types, functions with direct typed calls.
 * Importing the same file again yields the same values; an import cycle is an error.
 */
public final class Modules {
    private final Map<String, Object> globals;
    private final Map<Path, Map<String, Object>> loaded = new HashMap<>();
    private final Deque<Path> loading = new ArrayDeque<>();
    /** Class loader for this interpreter's generated classes - shared by the main script and its modules. */
    private final me.padej.jumper.jit.ScriptLoader loader = new me.padej.jumper.jit.ScriptLoader();

    public Modules(Map<String, Object> globals) {
        this.globals = globals;
    }

    public me.padej.jumper.jit.ScriptLoader loader() {
        return loader;
    }

    /** Access policy of this interpreter (null = everything allowed). */
    public me.padej.jumper.runtime.Access access() {
        return loader.access;
    }

    public void access(me.padej.jumper.runtime.Access a) {
        loader.access = a;
    }

    /** Resolve the path from `import "..."` relative to the importing file's directory. */
    public Path resolve(Path baseDir, String spec) {
        Path p = Path.of(spec);
        if (!p.isAbsolute()) p = (baseDir == null ? Path.of("") : baseDir).resolve(p);
        if (!spec.endsWith(".jmp") && !Files.exists(p)) {
            Path withExt = p.resolveSibling(p.getFileName() + ".jmp");
            if (Files.exists(withExt)) p = withExt;
        }
        return p.toAbsolutePath().normalize();
    }

    /**
     * Check mode (tools: `jmp --check`, the language server): an imported module is parsed, never run.
     * Its functions and classes are known from their declarations; its variables have no values. A module
     * that prints, writes files or loops at its top level does nothing while a file that imports it is
     * being checked.
     */
    private boolean checking;
    private final Map<Path, Map<String, Parser.Export>> parsed = new HashMap<>();
    private final Map<Path, String> problems = new HashMap<>();

    /** Runs `parse` with imported modules parsed instead of run (see {@link #checking}). */
    public <T> T checking(java.util.function.Supplier<T> parse) {
        boolean was = checking;
        checking = true;
        try {
            return parse.get();
        } finally {
            checking = was;
        }
    }

    public boolean checking() {
        return checking;
    }

    /**
     * What a module exports, as the importer needs it. Run mode: the module is run once (see {@link #load})
     * and its values are exported; check mode: it is only parsed ({@link #checking}).
     */
    public Map<String, Parser.Export> exports(Path file) {
        if (checking) return parse(file);
        Map<String, Parser.Export> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : load(file).entrySet()) {
            Object v = e.getValue();
            FunctionNode fn = v instanceof FunctionNode.ScriptFunction sf ? sf.node : null;
            me.padej.jumper.ast.Classes.ClassNode cls = v instanceof me.padej.jumper.runtime.JClass jc
                    && jc.node instanceof me.padej.jumper.ast.Classes.ClassNode cn ? cn : null;
            out.put(e.getKey(), new Parser.Export(v, fn, cls));
        }
        return out;
    }

    /** In check mode: the first error of the module (or of a module it imports), or null. */
    public String problem(Path file) {
        return checking ? problems.get(canonical(file)) : null;
    }

    private Map<String, Parser.Export> parse(Path file) {
        Path key = canonical(file);
        Map<String, Parser.Export> done = parsed.get(key);
        if (done != null) return done;
        enter(key, file);
        String src = read(key, file);
        loading.push(key);
        try {
            Parser ps = Parser.tolerant(src, globals).module(new LinkedHashMap<>(), key.getParent(), this);
            Parser.Result r = ps.parseTolerant();
            if (!r.errors().isEmpty()) {
                me.padej.jumper.parser.ParseError e = r.errors().get(0);
                problems.put(key, "line " + e.line + ": " + e.reason);
            }
            done = ps.staticExports();
        } finally {
            loading.pop();
        }
        parsed.put(key, done);
        return done;
    }

    /** A cycle is an error; so is a module under a policy that closes modules. */
    private void enter(Path key, Path file) {
        if (loading.contains(key)) {
            StringBuilder chain = new StringBuilder();
            for (java.util.Iterator<Path> it = loading.descendingIterator(); it.hasNext(); )
                chain.append(it.next().getFileName()).append(" -> ");
            throw new JmpError("Circular import: " + chain + key.getFileName());
        }
        me.padej.jumper.runtime.Access policy = loader.access;
        if (policy != null && !policy.modulesAllowed())
            throw new me.padej.jumper.runtime.ScriptSecurityException("Access denied: import \"" + file + "\" (modules are not allowed by the access policy)");
    }

    private static String read(Path key, Path file) {
        try {
            return Files.readString(key, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JmpError("Cannot read module " + file + ": " + e.getMessage());
        }
    }

    /** Top-level values of a module (executed on first load). */
    public Map<String, Object> load(Path file) {
        Path key = canonical(file);
        Map<String, Object> vars = loaded.get(key);
        if (vars != null) return vars;
        enter(key, file);
        String src = read(key, file);
        vars = new LinkedHashMap<>();
        loading.push(key);
        try {
            FunctionNode main = new Parser(src, globals).module(vars, key.getParent(), this).parseProgram();
            new FunctionNode.ScriptFunction(main, null).call(new Object[0]);
        } finally {
            loading.pop();
        }
        loaded.put(key, vars);
        return vars;
    }

    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
