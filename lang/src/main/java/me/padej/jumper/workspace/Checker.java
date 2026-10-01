package me.padej.jumper.workspace;

import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.parser.ParseError;
import me.padej.jumper.parser.Parser;
import me.padej.jumper.runtime.Access;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a Jumper file the way its host will load it, without running it: a script under its host's
 * policy, with the host's globals and the server's classes; a .jmc as a config; a .jma as a policy (only
 * `Policy` visible). Shared by `jmp --check` (one pass) and the language server (every keystroke), so it
 * keeps what is costly to find again: the file's context (for {@code contextTtlMs}), the loaded policies
 * (while the .jma is unchanged on disk) and the class loader of a classpath.
 *
 * <p>Not thread-safe; {@link #check} must run on a thread with a big stack (as scripts do): the parser
 * recurses on nesting.
 */
public final class Checker implements AutoCloseable {
    public enum Severity { ERROR, WARNING, NOTE }

    /** 1-based line and column; a note (how the context was found) is at 1:1. */
    public record Problem(int line, int col, String message, Severity severity) {}

    public record Report(FileContext context, List<Problem> problems) {
        public long errors() {
            return problems.stream().filter(p -> p.severity() == Severity.ERROR).count();
        }
    }

    /** What a file sees, for a tool that asks about it (hover, completion): its context, classes and policy. */
    public record Env(FileContext context, ClassLoader loader, Access policy) {}

    /** The environment of `file`: the class loader of its classpath and its policy (null - none, or a broken one). */
    public Env env(Path file) {
        FileContext ctx = context(file);
        Access policy = null;
        if (ctx.kind() == FileContext.Kind.SCRIPT && ctx.access() != null) policy = policy(ctx.access()).access;
        return new Env(ctx, loader(ctx.classpath()), policy);
    }

    private final long contextTtlMs, timeoutMs;
    /** Stops a check that runs too long. Nothing a check does runs script code (imported modules are only parsed), so this is a last resort. */
    private final java.util.concurrent.ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jumper-check-watchdog");
        t.setDaemon(true);
        return t;
    });
    private record CachedContext(FileContext ctx, long at) {}
    private final Map<Path, CachedContext> contexts = new HashMap<>();
    private record CachedPolicy(long size, long mtime, Access access, String error) {}
    private final Map<Path, CachedPolicy> policies = new HashMap<>();
    private List<Path> loaderPath = List.of();
    private URLClassLoader loader;

    /**
     * @param contextTtlMs how long a file's context is trusted before it is looked up again (0 - every check)
     * @param timeoutMs    a check still running after this is cancelled and reported
     */
    public Checker(long contextTtlMs, long timeoutMs) {
        this.contextTtlMs = contextTtlMs;
        this.timeoutMs = timeoutMs;
    }

    /** Forget the contexts (a jar, a descriptor or a policy file changed): the next check looks again. */
    public void invalidate() {
        contexts.clear();
    }

    public FileContext context(Path file) {
        Path f = file.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        CachedContext c = contexts.get(f);
        if (c != null && now - c.at < contextTtlMs) return c.ctx;
        FileContext ctx = Workspace.contextFor(f);
        contexts.put(f, new CachedContext(ctx, now));
        return ctx;
    }

    /** Checks `source` as the text of `file` (which need not exist on disk). */
    public Report check(Path file, String source) {
        FileContext ctx = context(file);
        List<Problem> out = new ArrayList<>();
        for (String n : ctx.notes()) out.add(new Problem(1, 1, n, Severity.NOTE));
        Interpreter jj;
        Access policyUsed = null;
        if (ctx.kind() == FileContext.Kind.POLICY) {
            jj = me.padej.jumper.security.Policy.interpreter();
        } else {
            jj = new Interpreter();
            if (ctx.access() != null) {
                CachedPolicy p = policy(ctx.access());
                if (p.error != null) {
                    // without its policy the host would not load the script at all
                    out.add(new Problem(1, 1, "its access policy cannot be loaded: " + p.error, Severity.ERROR));
                    return new Report(ctx, out);
                }
                jj.access(p.access);
                policyUsed = p.access;
            }
            for (var g : ctx.globals().entrySet()) jj.define(g.getKey(), new HostGlobal(g.getKey(), g.getValue()));
        }
        jj.cancellable(true);
        Thread t = Thread.currentThread();
        ClassLoader prev = t.getContextClassLoader();
        java.util.concurrent.ScheduledFuture<?> stop = watchdog.schedule(jj::cancel, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            ClassLoader cl = loader(ctx.classpath());
            if (cl != null) t.setContextClassLoader(cl);
            Parser.Result r = jj.check(source, file);
            boolean stopped = jj.cancelled();
            for (ParseError e : r.errors()) {
                // a cancelled check: say what happened
                String msg = stopped && e.reason.equals("Cancelled") ? timedOut() : e.reason;
                out.add(new Problem(e.line, e.col, msg, Severity.ERROR));
            }
            // calls that parse but will fail at run time, where the receiver's Java type is known
            if (!stopped) out.addAll(MemberCheck.run(r.program(), r.ranges(), source, t.getContextClassLoader(), policyUsed, ctx));
            // functions the host calls, declared with other parameters than it passes
            if (!stopped && r.program() != null) out.addAll(hookProblems(r.program(), source, ctx, t.getContextClassLoader()));
        } catch (RuntimeException e) {
            if (!jj.cancelled()) throw e;
            out.add(new Problem(1, 1, timedOut(), Severity.ERROR));
        } finally {
            stop.cancel(false);
            t.setContextClassLoader(prev);
        }
        return new Report(ctx, out);
    }

    /**
     * A top-level function the host calls (see {@link Hooks}) with a parameter count no overload of its API
     * method has: the host passes its arguments anyway - the missing ones arrive as null, the extra ones
     * are dropped, silently. A warning where the function is declared.
     */
    static List<Problem> hookProblems(me.padej.jumper.ast.FunctionNode program, String source, FileContext ctx, ClassLoader loader) {
        List<Problem> out = new ArrayList<>();
        if (ctx.hookTypes().isEmpty() && ctx.hooks().isEmpty()) return out;
        me.padej.jumper.ast.Stmt body = program.body;
        me.padej.jumper.ast.Stmt[] stmts = body instanceof me.padej.jumper.ast.Stmts.Block b ? b.stmts
                : body == null ? new me.padej.jumper.ast.Stmt[0] : new me.padej.jumper.ast.Stmt[] {body};
        String[] lines = source.split("\n", -1);
        for (me.padej.jumper.ast.Stmt st : stmts) {
            if (!(st instanceof me.padej.jumper.ast.Stmts.FuncDecl fd)) continue;
            Hooks.Hook h = Hooks.find(ctx, fd.fn.name, loader);
            if (h == null || h.takes(fd.fn.nparams)) continue;
            java.lang.reflect.Method m = h.method(-1);
            StringBuilder want = new StringBuilder();
            for (java.lang.reflect.Method k : h.methods()) {
                if (want.length() > 0) want.append(" or ");
                want.append(k.getParameterCount());
            }
            int line = fd.fn.line, col = 1;
            if (line >= 1 && line <= lines.length) {
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(fd.fn.name) + "\\s*\\(")
                        .matcher(lines[line - 1]);
                if (mm.find()) col = mm.start() + 1;
            }
            String host = ctx.host() != null ? ctx.host().name() : "the host";
            out.add(new Problem(line, col, fd.fn.name + " takes " + fd.fn.nparams + " parameter(s), but " + host + " calls it with "
                    + want + " - it implements " + h.owner().getSimpleName() + "." + Hooks.signature(m).replaceFirst("^\\S+ ", ""),
                    Severity.WARNING));
        }
        return out;
    }

    private String timedOut() {
        return "the check was stopped after " + timeoutMs + " ms";
    }

    private CachedPolicy policy(Path jma) {
        long size = -1, mtime = -1;
        try {
            BasicFileAttributes a = Files.readAttributes(jma, BasicFileAttributes.class);
            size = a.size();
            mtime = a.lastModifiedTime().toMillis();
        } catch (IOException e) {
            // reported by Access.load below
        }
        CachedPolicy c = policies.get(jma);
        if (c != null && c.size == size && c.mtime == mtime) return c;
        try {
            c = new CachedPolicy(size, mtime, Access.load(jma), null);
        } catch (IOException | IllegalArgumentException e) {
            c = new CachedPolicy(size, mtime, null, e.getMessage());
        }
        policies.put(jma, c);
        return c;
    }

    /** The loader of the classpath: `import` of a server class finds it (and never initializes it). */
    private ClassLoader loader(List<Path> jars) {
        if (jars.isEmpty()) return null;
        if (jars.equals(loaderPath) && loader != null) return loader;
        closeLoader();
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try { urls[i] = jars.get(i).toUri().toURL(); } catch (java.net.MalformedURLException e) { throw new IllegalStateException(e); }
        }
        loader = new URLClassLoader(urls, Checker.class.getClassLoader());
        loaderPath = List.copyOf(jars);
        return loader;
    }

    private void closeLoader() {
        if (loader == null) return;
        try { loader.close(); } catch (IOException e) { /* nothing to do */ }
        loader = null;
        loaderPath = List.of();
    }

    @Override
    public void close() {
        closeLoader();
        watchdog.shutdownNow();
    }
}
