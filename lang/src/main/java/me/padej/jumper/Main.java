package me.padej.jumper;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.ast.Stmt;
import me.padej.jumper.ast.Stmts;
import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.parser.ParseError;
import me.padej.jumper.parser.Parser;
import me.padej.jumper.runtime.Builtins;
import me.padej.jumper.runtime.JmpError;
import me.padej.jumper.runtime.Ops;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jumper launcher.
 *   jmp script.jmp [args...]   - run a file (arguments are available as the `args` array)
 *   jmp config.jmc             - evaluate a config (interp.Config) and print its value
 *   jmp -e "<code>"            - run a string
 *   jmp --check a.jmp b.jmc    - report every error of the files without running them
 *   jmp --check --stdin a.jmp  - the same for the text on stdin (an unsaved editor buffer) as if it were a.jmp
 *   jmp --context a.jmp        - what surrounds a file: its host, policy, globals, classpath
 *   jmp --lsp                  - the language server (LSP over stdin/stdout) for editors
 *   jmp                        - REPL
 */
public final class Main {
    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            repl();
            return;
        }
        if (argv[0].equals("--opts")) {
            System.out.print(me.padej.jumper.runtime.Opts.describe());
            return;
        }
        if (argv[0].equals("-h") || argv[0].equals("--help")) {
            System.out.println("Usage: jmp <script.jmp> [args...]\n       jmp <config.jmc>           (print the config value)\n       jmp -e \"<code>\"\n       jmp            (REPL)\n       jmp --opts     (which optimizations can be switched off)\n       jmp --check <file>...  (all errors of the files, nothing is run)\n       jmp --context <file>   (the host, policy, globals and classpath found for a file)\n       jmp --lsp              (language server for editors, LSP over stdin/stdout)");
            return;
        }
        if (argv[0].equals("--check")) {
            System.exit(check(java.util.Arrays.copyOfRange(argv, 1, argv.length)));
        }
        if (argv[0].equals("--lsp")) {
            // stdout belongs to the protocol: whatever the checked code prints (an imported module runs
            // when parsed) goes to stderr, the editor's log, never between two LSP messages
            java.io.OutputStream protocol = new java.io.BufferedOutputStream(new java.io.FileOutputStream(java.io.FileDescriptor.out));
            System.setOut(System.err);
            System.exit(new me.padej.jumper.lsp.LspServer(System.in, protocol).run());
        }
        if (argv[0].equals("--context")) {
            System.exit(context(java.util.Arrays.copyOfRange(argv, 1, argv.length)));
        }
        Interpreter jj = new Interpreter();
        // Access policy for the CLI is optional: -Djmp.access=path.jma or JMP_ACCESS=path.jma
        String accessFile = System.getProperty("jmp.access", System.getenv("JMP_ACCESS"));
        if (accessFile != null && !accessFile.isBlank()) {
            try {
                jj.access(me.padej.jumper.runtime.Access.load(Path.of(accessFile)));
            } catch (java.io.IOException | IllegalArgumentException e) {
                System.err.println("Access policy: " + e.getMessage());
                System.exit(2);
            }
        }
        String[] scriptArgs = java.util.Arrays.copyOfRange(argv, argv[0].equals("-e") ? 2 : 1, argv.length);
        jj.define("args", new me.padej.jumper.runtime.JArray((Object[]) scriptArgs));
        try {
            Interpreter.runWithBigStack(() -> {
                if (argv[0].equals("-e")) return jj.eval(argv.length > 1 ? argv[1] : "");
                if (argv[0].endsWith(".jmc")) {   // a config: print what it evaluates to
                    System.out.println(me.padej.jumper.runtime.Ops.str(me.padej.jumper.interp.Config.load(Path.of(argv[0]))));
                    return null;
                }
                return jj.evalFile(Path.of(argv[0]));
            });
        } catch (ParseError e) {
            System.err.println("Syntax error: " + e.getMessage());
            System.exit(1);
        } catch (JmpError e) {
            System.err.println("Runtime error: " + e.getMessage());
            if (me.padej.jumper.runtime.Opts.flag("jmp.debug", "JMP_DEBUG")) e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * `jmp --check file...`: `file:line:col: error: message` for every error, the files are never run.
     * Each file is checked in its own context ({@link me.padej.jumper.workspace.Workspace}): a script under
     * its host's policy, with the host's globals and the server's classes; a .jmc as a config; a .jma as a
     * policy (only `Policy` visible). How the context was found, when not for sure, is a `note:` line.
     * Exit code 0 - no errors, 1 - errors, 2 - a file could not be read.
     */
    static int check(String[] files) {
        return check(files, System.in);
    }

    /**
     * `--stdin <file>`: the text comes from stdin (an editor's unsaved buffer), `file` only says where it
     * lives - its context (host, policy, globals) and the name in the report. The file need not exist.
     */
    static int check(String[] files, java.io.InputStream stdin) {
        boolean fromStdin = files.length > 0 && files[0].equals("--stdin");
        if (files.length == 0 || fromStdin && files.length != 2) {
            System.err.println("Usage: jmp --check <file.jmp|file.jmc|file.jma>...\n       jmp --check --stdin <file>   (the text of <file> from stdin)");
            return 2;
        }
        if (fromStdin) files = new String[] {files[1]};
        me.padej.jumper.workspace.Checker checker = new me.padej.jumper.workspace.Checker(60_000, 10_000);
        int errors = 0;
        boolean unreadable = false;
        for (String f : files) {
            Path path = Path.of(f);
            String src;
            try {
                src = fromStdin ? new String(stdin.readAllBytes(), StandardCharsets.UTF_8)
                        : java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                System.err.println(f + ": cannot read: " + e.getMessage());
                unreadable = true;
                continue;
            }
            me.padej.jumper.workspace.Checker.Report r = Interpreter.runWithBigStack(() -> checker.check(path, src));
            for (var pr : r.problems()) {
                if (pr.severity() == me.padej.jumper.workspace.Checker.Severity.NOTE) System.out.println(f + ": note: " + pr.message());
                else System.out.println(f + ":" + pr.line() + ":" + pr.col() + ": "
                        + pr.severity().name().toLowerCase(java.util.Locale.ROOT) + ": " + pr.message());
            }
            errors += (int) r.errors();
        }
        checker.close();
        if (errors > 0) System.err.println(errors + " error" + (errors == 1 ? "" : "s"));
        return unreadable ? 2 : errors > 0 ? 1 : 0;
    }

    /** `jmp --context file...`: what {@link me.padej.jumper.workspace.Workspace} found for each file. */
    static int context(String[] files) {
        if (files.length == 0) {
            System.err.println("Usage: jmp --context <file>...");
            return 2;
        }
        for (String f : files) {
            var c = me.padej.jumper.workspace.Workspace.contextFor(Path.of(f));
            System.out.println(f);
            System.out.println("  kind:      " + c.kind().name().toLowerCase(java.util.Locale.ROOT));
            System.out.println("  root:      " + c.root());
            System.out.println("  host:      " + (c.host() == null ? "-" : c.host().name() + " (" + c.host().jar() + ")"));
            System.out.println("  access:    " + (c.access() == null ? "-" : c.access()));
            System.out.println("  globals:   " + (c.globals().isEmpty() ? "-" : c.globals()));
            System.out.println("  hooks:     " + (c.hookTypes().isEmpty() && c.hooks().isEmpty() ? "-"
                    : String.join(", ", c.hookTypes()) + (c.hooks().isEmpty() ? "" : (c.hookTypes().isEmpty() ? "" : ", ") + c.hooks())));
            System.out.println("  classpath: " + c.classpath().size() + " jar" + (c.classpath().size() == 1 ? "" : "s"));
            for (Path j : c.classpath()) System.out.println("             " + j);
            for (String n : c.notes()) System.out.println("  note:      " + n);
        }
        return 0;
    }

    // ---------- REPL ----------

    static void repl() throws Exception {
        System.out.println("Jumper " + me.padej.jumper.script.JmpScriptEngineFactory.VERSION
                + " (" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version") + ")");
        System.out.println("Type expressions or statements; :quit to exit, :vars to list variables.");
        Map<String, Object> vars = new LinkedHashMap<>();
        Map<String, Object> globals = new HashMap<>(Builtins.globals());
        me.padej.jumper.interp.Modules mods = new me.padej.jumper.interp.Modules(globals);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buf = new StringBuilder();
        while (true) {
            System.out.print(buf.length() == 0 ? "jmp> " : "...  ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) break;
            String trimmed = line.trim();
            if (buf.length() == 0) {
                if (trimmed.equals(":quit") || trimmed.equals(":q") || trimmed.equals("exit")) break;
                if (trimmed.equals(":vars")) {
                    vars.forEach((k, v) -> System.out.println("  " + k + " = " + Ops.str(v)));
                    continue;
                }
                if (trimmed.isEmpty()) continue;
            }
            buf.append(line).append('\n');
            String src = buf.toString();
            if (!balanced(src)) continue; // waiting for a continuation
            if (!trimmed.isEmpty() && incomplete(src, globals)) continue; // statement not finished (for without a body etc.); an empty line forces it
            buf.setLength(0);
            evalLine(src, vars, globals, mods);
        }
        System.out.println();
    }

    /** Parsing fails at end of input -> the line is not finished yet. */
    private static boolean incomplete(String src, Map<String, Object> globals) {
        try {
            new Parser(src, globals).repl(new HashMap<>()).parseProgram();
            return false;
        } catch (ParseError e) {
            if (!e.atEof) return false;
            try { // but maybe it is an expression without ';'
                new Parser("return " + src.trim() + ";", globals).repl(new HashMap<>()).parseProgram();
                return false;
            } catch (ParseError e2) {
                return true;
            }
        }
    }

    private static void evalLine(String src, Map<String, Object> vars, Map<String, Object> globals, me.padej.jumper.interp.Modules mods) {
        try {
            Interpreter.runWithBigStack(() -> {
                FunctionNode main;
                boolean isExpr = false;
                try {
                    main = new Parser(src, globals).repl(vars).source(null, mods).parseProgram();
                } catch (ParseError first) {
                    // expression without a semicolon: `1 + 2`, `x`
                    try {
                        main = new Parser("return " + src.trim() + ";", globals).repl(vars).source(null, mods).parseProgram();
                    } catch (ParseError second) {
                        throw first;
                    }
                    isExpr = true;
                }
                if (!isExpr) isExpr = isSingleExpression(main);
                Object r = new FunctionNode.ScriptFunction(main, null).call(new Object[0]);
                if (isExpr && r != null) System.out.println(Ops.str(r));
                return null;
            });
        } catch (ParseError e) {
            System.out.println("Syntax error: " + e.getMessage());
        } catch (JmpError e) {
            System.out.println("Error: " + e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("Error: " + e);
        }
    }

    /** A single expression statement (not an assignment/declaration): print its value. */
    private static boolean isSingleExpression(FunctionNode main) {
        if (!(main.body instanceof Stmts.Block b) || b.stmts.length != 1) return false;
        Stmt s = b.stmts[0];
        if (!(s instanceof Stmts.ExprStmt es)) return false;
        String n = es.e.getClass().getSimpleName();
        if (n.contains("Assign") || n.contains("Inc") || n.equals("GlobalSet")) return false;
        // turn it into a return to get the value
        b.stmts[0] = new Stmts.Return(es.e, s.line);
        return true;
    }

    private static boolean balanced(String src) {
        int depth = 0;
        boolean str = false;
        char q = 0;
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (str) {
                if (ch == '\\') i++;
                else if (ch == q) str = false;
            } else if (ch == '"' || ch == '\'') { str = true; q = ch; }
            else if (ch == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') { while (i < src.length() && src.charAt(i) != '\n') i++; }
            else if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
        }
        return depth <= 0 && !str;
    }
}
