package me.padej.jumper.parser;

import me.padej.jumper.ast.*;
import me.padej.jumper.ast.Classes.*;
import me.padej.jumper.ast.Exprs.*;
import me.padej.jumper.ast.Fields.*;
import me.padej.jumper.ast.Prims.*;
import me.padej.jumper.ast.Stmts.*;
import me.padej.jumper.lexer.Lexer;
import me.padej.jumper.lexer.Token;
import me.padej.jumper.lexer.TokenType;
import me.padej.jumper.runtime.Builtins;
import me.padej.jumper.runtime.Interop;
import me.padej.jumper.runtime.JavaClass;
import me.padej.jumper.runtime.JFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static me.padej.jumper.lexer.TokenType.*;

/**
 * Recursive descent + Pratt for expressions. One pass: names are resolved to slots
 * right during parsing (as in the Lua compiler), there is no separate resolver.
 */
public final class Parser {
    private final List<Token> toks;
    private int p = 0;
    private final Map<String, Object> globals;

    // ---------- scopes ----------
    /** slot is an index into Frame.slots (for DYN/String) or Frame.p (for primitive types). */
    private static final class VarInfo {
        final int slot;
        final VarType type;
        final Object constant;
        final boolean isConst;
        /** If the variable is declared as a named function, its node (for direct calls in Tier 1). */
        FunctionNode fn;
        /** The name is hoisted by a function declaration further down the block; the declaration itself is not parsed yet. */
        boolean hoisted;
        /** Calls parsed before the function declaration; they get their target once fn is known. */
        List<Call> pendingCalls;
        /** The name is assigned at least once: then calls through it cannot be bound permanently. */
        boolean reassigned;
        /** Calls bound directly to this function; unbound if the name gets reassigned. */
        List<Call> boundCalls;
        /** Element type of the Java array the variable was initialized with (null = unknown). */
        Class<?> arrayComp;
        /** Read nodes of this variable that received arrayComp; unbound if the name gets reassigned. */
        List<Expr> arrayReads;
        /** If the variable is a class declared in this source. */
        ClassNode cls;
        /** The name is hoisted by a class declaration further down the block; the class contents (cls) are not known yet. */
        boolean classPending;
        /** The variable's declared type is a script class (`Vec v`); type is DYN in that case. */
        ClassNode instCls;
        /** Declared with a Java class as its type (`Random r`): what it may hold besides null. */
        Class<?> javaType;
        /** REPL: a top-level variable lives in the session Map, not in a slot. */
        boolean global;

        VarInfo(int slot, VarType type, Object constant, boolean isConst) {
            this.slot = slot;
            this.type = type;
            this.constant = constant;
            this.isConst = isConst;
        }

        boolean prim() { return type.isPrimitive(); }
    }

    private static final class FuncState {
        final FuncState enclosing;
        final FunctionNode node;
        final ArrayList<HashMap<String, VarInfo>> scopes = new ArrayList<>();
        int nslots = 0, nprims = 0;
        /** Loops around the current point, in this function only: break/continue need one. */
        int loops;
        VarType returnType = VarType.DYN;
        ClassNode returnClass;
        Class<?> returnJava;

        FuncState(FuncState enclosing, FunctionNode node) {
            this.enclosing = enclosing;
            this.node = node;
            scopes.add(new java.util.LinkedHashMap<>());   // declaration order: Script.names, Config tables
        }
    }

    private void hoist(Token name) {
        HashMap<String, VarInfo> scope = fs.scopes.get(fs.scopes.size() - 1);
        if (!scope.containsKey(name.text())) {
            VarInfo v = declare(name.text(), VarType.DYN, name);
            v.hoisted = true;
        }
    }

    /** Reuses a hoisted name or declares a new one (for function/class). */
    private VarInfo declareHoistable(Token name) {
        VarInfo v = fs.scopes.get(fs.scopes.size() - 1).get(name.text());
        if (v != null && v.hoisted && v.fn == null) { v.hoisted = false; return v; }
        return declare(name.text(), VarType.DYN, name);
    }

    /**
     * Function name hoisting: before parsing a block, declare every `function f(`, `void f(`, `int f(`
     * at its top level so that mutual recursion and "bottom-up" calls work (as in Java).
     */
    private void hoistFunctions() {
        // 0) the file's imports: modules are loaded before the class prescan so their classes are visible as types
        int depth = 0;
        for (int i = p; i < toks.size(); i++) {
            TokenType t = toks.get(i).type();
            if (t == LBRACE) depth++;
            else if (t == RBRACE) { if (depth == 0) break; depth--; }
            else if (t == EOF) break;
            else if (depth == 0 && t == IMPORT && i + 1 < toks.size() && toks.get(i + 1).type() == TokenType.STRING)
                importModule(toks.get(i), (String) toks.get(i + 1).value());
        }
        // 1) class names, so that `Vec v` and `Vec make()` further down the block are recognized as types
        List<int[]> classes = new ArrayList<>(); // [name index, body index after '{']
        depth = 0;
        for (int i = p; i < toks.size(); i++) {
            TokenType t = toks.get(i).type();
            if (t == LBRACE) depth++;
            else if (t == RBRACE) { if (depth == 0) break; depth--; }
            else if (t == EOF) break;
            else if (depth == 0 && t == CLASS && i + 1 < toks.size() && toks.get(i + 1).type() == IDENT) {
                hoist(toks.get(i + 1));
                VarInfo cv = fs.scopes.get(fs.scopes.size() - 1).get(toks.get(i + 1).text());
                if (cv != null && cv.cls == null) cv.classPending = true;
                int j = i + 2;
                while (j < toks.size() && toks.get(j).type() != LBRACE && toks.get(j).type() != EOF) j++;
                classes.add(new int[]{i + 1, j + 1});
            }
        }
        // 2) class contents (fields, methods, field types), once all names are known
        for (int[] c : classes) {
            Token name = toks.get(c[0]);
            VarInfo v = fs.scopes.get(fs.scopes.size() - 1).get(name.text());
            if (v != null && v.cls == null) v.cls = prescanClass(name.text(), c[1]);
        }
        // 2b) field types, only now that the contents of ALL classes in the block are known: a field
        // `Node left` inside Node itself, or `B b` in class A declared before B, used to vanish silently otherwise
        for (int[] c : classes) {
            VarInfo v = fs.scopes.get(fs.scopes.size() - 1).get(toks.get(c[0]).text());
            if (v != null && v.cls != null) { v.classPending = false; resolveFieldClasses(v.cls); }
        }
        // 3) functions: void f(, int f(, dyn f(, Vec f(
        depth = 0;
        for (int i = p; i < toks.size(); i++) {
            TokenType t = toks.get(i).type();
            if (t == LBRACE) depth++;
            else if (t == RBRACE) { if (depth == 0) break; depth--; }
            else if (t == EOF) break;
            else if (depth == 0 && (t == VOID || isTypeKeyword(t) || (t == IDENT && classNamed(toks.get(i).text()) != null))
                    && i + 2 < toks.size() && toks.get(i + 1).type() == IDENT && toks.get(i + 2).type() == LPAREN) {
                hoist(toks.get(i + 1));
            }
        }
    }

    /** The name is a class of this source, even if its contents are not scanned yet (hoisted further down the block). */
    private boolean isClassName(String name) {
        if (classNamed(name) != null) return true;
        Resolved r = resolve(name);
        return r != null && r.info.classPending;
    }

    /** Script class with this name in scope (declared or hoisted), or null. */
    private ClassNode classNamed(String name) {
        Resolved r = resolve(name);
        if (r != null) return r.info.cls;
        if (replVars != null && replVars.get(name) instanceof me.padej.jumper.runtime.JClass jc && jc.node instanceof ClassNode cn) return cn;
        return null;
    }

    /** Token p+k is a class name followed by an identifier: a declaration with a class type. */
    private ClassNode classTypeAt(int k) {
        if (!checkAt(k, IDENT) || !checkAt(k + 1, IDENT)) return null;
        return classNamed(peek(k).text());
    }

    /**
     * A Java class as a type (`Random rand = new Random();`): a name followed by a name, where the first is an
     * imported class (or one of java.lang). Only in that position: two names in a row are otherwise always an
     * error, so looking the class up costs nothing elsewhere.
     */
    private Class<?> javaTypeAt(int k) {
        if (!checkAt(k, IDENT) || !checkAt(k + 1, IDENT)) return null;
        return javaClassNamed(peek(k).text());
    }

    private Class<?> javaClassNamed(String n) {
        if (n.isEmpty() || !Character.isUpperCase(n.charAt(0))) return null;
        Resolved r = resolve(n);
        if (r != null) return r.info.isConst && r.info.constant instanceof JavaClass jc ? jc.cls() : null;
        if (globals.containsKey(n)) return globals.get(n) instanceof JavaClass jc ? jc.cls() : null;
        // `import java.util.Random;` further down, or not parsed yet (a class body is prescanned before the
        // statements of its block): the imports of the file by their tokens
        String fq = fileImports().get(n);
        return Interop.findClass(fq != null ? fq : n);   // else java.lang - under a policy, checked like any use
    }

    private Map<String, String> fileImports;

    /** Simple name -> qualified name of every `import a.b.C;` in the file. */
    private Map<String, String> fileImports() {
        if (fileImports != null) return fileImports;
        fileImports = new HashMap<>();
        for (int i = 0; i + 1 < toks.size(); i++) {
            if (toks.get(i).type() != IMPORT || toks.get(i + 1).type() != IDENT) continue;
            StringBuilder sb = new StringBuilder(toks.get(i + 1).text());
            int j = i + 2;
            while (j + 1 < toks.size() && toks.get(j).type() == DOT && toks.get(j + 1).type() == IDENT) {
                sb.append('.').append(toks.get(j + 1).text());
                j += 2;
            }
            if (j < toks.size() && toks.get(j).type() == SEMI) {
                String fq = sb.toString();
                fileImports.put(fq.substring(fq.lastIndexOf('.') + 1), fq);
            }
        }
        return fileImports;
    }

    /**
     * Value for a variable/parameter/return of a Java class type: null, a statically fitting value, or a runtime
     * check (CheckJava: null or an instance of the class); a value known not to fit is a parse error.
     */
    private Expr checkedJava(Expr value, Class<?> cls, Token at) {
        if (value instanceof Literal l && l.value == null) return value;
        Class<?> st = switch (value.type) {
            case INT -> Integer.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
            case BOOLEAN -> Boolean.class;
            case STRING -> String.class;
            default -> null;
        };
        if (st != null || value.staticClass != null) {
            if (st != null && cls.isAssignableFrom(st)) return value;
            String what = st != null ? value.type.name().toLowerCase(java.util.Locale.ROOT) : value.staticClass.name;
            throw new ParseError("Cannot assign " + what + " to " + cls.getSimpleName(), at.line(), at.col());
        }
        return new CheckJava(value, cls, at.line());
    }

    /**
     * Value for a variable/field/parameter of type cls: a statically known subclass as is,
     * otherwise a runtime check (CheckClass); a type known to be foreign is a parse error.
     */
    private Expr checked(Expr value, ClassNode cls, Token at) {
        if (value.staticClass != null) {
            if (value.staticClass.isSubclassOf(cls)) return value;
            throw new ParseError("Cannot assign " + value.staticClass.name + " to " + cls.name, at.line(), at.col());
        }
        if (value.type != VarType.DYN) throw new ParseError("Cannot assign " + value.type.name().toLowerCase(java.util.Locale.ROOT) + " to " + cls.name, at.line(), at.col());
        if (value instanceof Literal l && l.value == null) return value;
        return new CheckClass(value, cls, at.line());
    }

    /** Checks of class-typed parameters at the start of a function body. */
    private void addParamChecks(FunctionNode fn, List<ClassNode> paramClasses, List<Class<?>> paramJavas, List<Token> at) {
        List<Stmt> checks = new ArrayList<>();
        for (int i = 0; i < paramClasses.size(); i++) {
            ClassNode pc = paramClasses.get(i);
            Class<?> pj = paramJavas.get(i);
            if (pc == null && pj == null) continue;
            int line = at.get(i).line();
            Expr param = new Local(fn.paramIndex[i], line);
            checks.add(new ExprStmt(pc != null ? new CheckClass(param, pc, line) : new CheckJava(param, pj, line), line));
        }
        if (checks.isEmpty()) return;
        checks.add(fn.body);
        fn.body = new Block(checks.toArray(new Stmt[0]), fn.body.line);
    }

    private FuncState fs;
    /** Variable node -> its info (for recognizing direct calls of declared functions). */
    private final java.util.IdentityHashMap<Expr, VarInfo> varOf = new java.util.IdentityHashMap<>();
    /** A read of a typed top-level variable of an engine / module (`int n = 0;`): its type, for later assignments. */
    private final java.util.IdentityHashMap<Expr, VarType> globalType = new java.util.IdentityHashMap<>();
    /** All variables declared as functions: at the end of parsing we decide which calls are bound permanently. */
    private final List<VarInfo> funcVars = new ArrayList<>();
    /** Variables with a known array element type - see finishFixedTargets(). */
    private final List<VarInfo> arrayVars = new ArrayList<>();
    /** `new C(...)` with a known class: constructor arity is checked at the end of parsing (the class body may come later). */
    private record PendingNew(ClassNode cls, int argc, int line, int col) {}
    private final List<PendingNew> pendingNews = new ArrayList<>();
    /** Expressions that are a reference to this of the current class (for direct field access). */
    private final java.util.Set<Expr> thisRefs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Classes whose method bodies are being parsed now (for bare field/method names, this, super). */
    private final java.util.ArrayDeque<ClassNode> classStack = new java.util.ArrayDeque<>();
    /** Boundary function of the current method/initializer: variables outside it yield to class fields (as in Java). */
    private final java.util.ArrayDeque<FuncState> memberBoundary = new java.util.ArrayDeque<>();
    private boolean sawSuperCall;
    /** REPL/module: top-level variables live in a Map (null for an ordinary script). */
    private Map<String, Object> replVars;
    /** REPL only: redeclaring a name is a reassignment. */
    private boolean allowRedeclare;
    /** Directory of the current file and the module loader (for `import "..."`). */
    private java.nio.file.Path baseDir;
    private me.padej.jumper.interp.Modules modules;
    /**
     * Files already imported into each scope (a repeated import there is a no-op: the hoisting pass and
     * then the statement itself both come here). Per scope, not per parse: two sibling blocks importing the
     * same module each get its names (the module itself is loaded once per interpreter, Modules caches it).
     */
    private final java.util.IdentityHashMap<Object, java.util.Set<java.nio.file.Path>> imported = new java.util.IdentityHashMap<>();

    public Parser repl(Map<String, Object> vars) {
        this.replVars = vars;
        this.allowRedeclare = true;
        return this;
    }

    /** Module parse: top-level declarations go into vars, imports resolve against baseDir. */
    public Parser module(Map<String, Object> vars, java.nio.file.Path baseDir, me.padej.jumper.interp.Modules modules) {
        this.replVars = vars;
        this.baseDir = baseDir;
        this.modules = modules;
        return this;
    }

    /**
     * Config mode (*.jmc, interp.Config): data plus branching - variables, expressions, table and array
     * literals, if/else, switch, ternaries, return. No loops, no functions or lambdas, no classes, no
     * import, no try/throw: a config always finishes, in time proportional to its length.
     */
    private boolean config;

    public Parser config() {
        this.config = true;
        return this;
    }

    private void notInConfig(Token t, String what) {
        if (config) throw new ParseError(what + " is not allowed in a config (.jmc)", t.line(), t.col());
    }

    /** The file the script is read from (for relative imports). */
    public Parser source(java.nio.file.Path baseDir, me.padej.jumper.interp.Modules modules) {
        this.baseDir = baseDir;
        this.modules = modules;
        return this;
    }

    private boolean atReplTop() {
        return replVars != null && fs.enclosing == null && fs.scopes.size() == 1 && classStack.isEmpty();
    }

    public Parser(String src) {
        this(src, Builtins.globals());
    }

    public Parser(String src, Map<String, Object> globals) {
        this.toks = Lexer.tokenize(src);
        this.globals = globals;
    }

    private Parser(String src, Map<String, Object> globals, List<ParseError> errors) {
        this.errors = errors;
        this.ranges = new java.util.IdentityHashMap<>();
        this.toks = Lexer.tokenizeTolerant(src, errors);
        this.globals = globals;
    }

    /**
     * A parser for tools (LSP, `jmp --check`): it does not stop at the first error. After an error it
     * skips to the end of the statement, restores its state and goes on; {@link #parseTolerant()} returns
     * every error of the file. The tree it builds is for inspection only and is never run.
     * A strict parser (the constructors above) is unchanged: the first error is thrown.
     */
    public static Parser tolerant(String src, Map<String, Object> globals) {
        return new Parser(src, globals, new ArrayList<>());
    }

    public static Parser tolerant(String src) {
        return tolerant(src, Builtins.globals());
    }

    /**
     * What a tolerant parse found: the (possibly partial) tree, the errors in source order and the source
     * range of each node (identity map: node -> range).
     */
    public record Result(FunctionNode program, List<ParseError> errors, Map<Object, Range> ranges) {
        public boolean ok() { return errors.isEmpty(); }
    }

    /**
     * A top-level name of a module as an importer sees it: its value (null when the module was only
     * parsed, not run - see {@link me.padej.jumper.interp.Modules#checking}), and what is known of it
     * statically - the function or class node behind it.
     */
    public record Export(Object value, FunctionNode fn, ClassNode cls) {}

    /** The top scope of the program being parsed (kept apart from fs, which a failed parse may leave elsewhere). */
    private HashMap<String, VarInfo> mainScope;

    /**
     * After a parse of a module: its top-level declarations - what it exports - known without running
     * it. Functions and classes carry their nodes; variables have no value.
     */
    public Map<String, Export> staticExports() {
        Map<String, Export> out = new java.util.LinkedHashMap<>();
        if (mainScope == null) return out;
        for (var e : mainScope.entrySet()) {
            VarInfo v = e.getValue();
            if (v.isConst) continue;   // the module's own imports are not re-exported
            Object value = v.fn != null ? new FunctionNode.ScriptFunction(v.fn, null) : null;
            out.put(e.getKey(), new Export(value, v.fn, v.cls));
        }
        return out;
    }

    /** The whole file with every error; for a parser made by {@link #tolerant}. */
    public Result parseTolerant() {
        if (errors == null) throw new IllegalStateException("not a tolerant parser: use Parser.tolerant(...)");
        FunctionNode main = null;
        try {
            main = parseProgram();
        } catch (ParseError e) {
            record(e);   // what recovery could not absorb (a broken import, a declaration scan)
        } catch (me.padej.jumper.runtime.ScriptSecurityException e) {
            record(new ParseError(e.getMessage(), peek().line(), peek().col()));
        }
        List<ParseError> out = new ArrayList<>(errors);
        out.sort(java.util.Comparator.comparingInt((ParseError e) -> e.line).thenComparingInt(e -> e.col));
        return new Result(main, out, ranges());
    }

    /** Non-null in tolerant mode: the errors found so far. */
    private List<ParseError> errors;
    /** Errors after which a tolerant parse gives up: a file this broken is not worth more diagnostics. */
    private static final int MAX_ERRORS = 200;
    private final java.util.Set<String> seenErrors = new java.util.HashSet<>();

    private void record(ParseError e) {
        if (errors.size() >= MAX_ERRORS) return;
        if (seenErrors.add(e.line + ":" + e.col + ":" + e.getMessage())) errors.add(e);
    }

    /**
     * A statement of a block, the program or a switch branch. Strict: just statement(). Tolerant: an error
     * is recorded, the parser state (current function, scopes, loop depth, class stack) is put back to
     * what it was at the statement's start, and the tokens up to the statement's end are skipped.
     */
    private Stmt statementOrRecover() {
        if (errors == null) return statement();
        int start = p;
        FuncState saveFs = fs;
        int scopes = fs.scopes.size(), loops = fs.loops, classes = classStack.size(), members = memberBoundary.size();
        try {
            return statement();
        } catch (ParseError e) {
            record(e);
        } catch (me.padej.jumper.runtime.ScriptSecurityException e) {
            Token t = toks.get(start);
            record(new ParseError(e.getMessage(), t.line(), t.col()));
        }
        if (errors.size() >= MAX_ERRORS) { p = toks.size() - 1; }   // give up: jump to EOF
        int errorAt = p;
        fs = saveFs;
        while (fs.scopes.size() > scopes) fs.scopes.remove(fs.scopes.size() - 1);
        fs.loops = loops;
        while (classStack.size() > classes) classStack.pop();
        while (memberBoundary.size() > members) memberBoundary.pop();
        declareBrokenDeclaration(start);
        p = statementEnd(start);
        if (p < errorAt && errors.size() < MAX_ERRORS) p = statementEnd(errorAt);
        if (p <= start) p = start + 1;   // always make progress
        if (p >= toks.size()) p = toks.size() - 1;
        return new Empty(toks.get(start).line());
    }

    /**
     * `int x = <broken>;` still declares x: without it every later use of x would be one more
     * "Undefined variable" error for a mistake made once.
     */
    private void declareBrokenDeclaration(int start) {
        if (start + 2 >= toks.size()) return;
        Token a = toks.get(start), name = toks.get(start + 1), after = toks.get(start + 2);
        boolean typeStart = a.type() == DYN || isTypeKeyword(a.type()) || a.type() == IDENT;
        if (!typeStart || name.type() != IDENT || (after.type() != EQ && after.type() != SEMI && after.type() != COMMA)) return;
        HashMap<String, VarInfo> scope = fs.scopes.get(fs.scopes.size() - 1);
        if (scope.containsKey(name.text()) || resolve(name.text()) != null && a.type() == IDENT) return;
        try { declare(name.text(), VarType.DYN, name); } catch (ParseError ignored) { }
    }

    /**
     * The index just past the statement that starts at `from`, by the tokens alone: its `;` outside
     * braces (a `for (...)` header's own `;` do not count), or its closing `}` when no `else`, `catch`,
     * `finally` or the rest of an expression follows. A `}` that closes an enclosing block ends the
     * scan before it.
     */
    private int statementEnd(int from) {
        int i = from, depth = 0;
        if (i < toks.size() && toks.get(i).type() == FOR && i + 1 < toks.size() && toks.get(i + 1).type() == LPAREN) {
            int parens = 0;   // skip the header: its ';' are not the statement's end
            for (i = i + 1; i < toks.size(); i++) {
                TokenType t = toks.get(i).type();
                if (t == LPAREN) parens++;
                else if (t == RPAREN && --parens == 0) { i++; break; }
                else if (t == LBRACE || t == RBRACE || t == EOF) break;
            }
        }
        boolean isDo = i < toks.size() && toks.get(from).type() == DO;
        for (; i < toks.size(); i++) {
            TokenType t = toks.get(i).type();
            if (t == EOF) return i;
            if (t == LBRACE) depth++;
            else if (t == RBRACE) {
                if (depth == 0) return i;   // the enclosing block's brace
                if (--depth == 0) {
                    TokenType next = i + 1 < toks.size() ? toks.get(i + 1).type() : EOF;
                    boolean more = next == ELSE || next == CATCH || next == FINALLY || next == SEMI || next == RPAREN
                            || next == COMMA || next == DOT || next == LPAREN || next == LBRACKET || next == RBRACKET
                            || (isDo && next == WHILE);
                    if (!more) return i + 1;
                }
            } else if (t == SEMI && depth == 0) return i + 1;
        }
        return toks.size() - 1;
    }

    /** Tolerant: record the error and go on; strict: throw it. */
    private void tolerate(Runnable r) {
        if (errors == null) { r.run(); return; }
        try { r.run(); } catch (ParseError e) { record(e); }
    }

    /** Parses the script as the body of a top-level function. */
    public FunctionNode parseProgram() {
        if (config) {
            // before hoisting, which already loads imported modules
            for (Token t : toks) {
                tolerate(() -> {
                    switch (t.type()) {
                        case IMPORT -> notInConfig(t, "import");
                        case WHILE, DO, FOR -> notInConfig(t, "A loop");
                        case CLASS -> notInConfig(t, "A class");
                        case TRY, THROW -> notInConfig(t, "try/throw");
                        default -> {}
                    }
                });
            }
        }
        FunctionNode main = newFunctionNode("<main>", new VarType[0], 1);
        fs = new FuncState(null, main);
        mainScope = fs.scopes.get(0);
        tolerate(this::hoistFunctions);
        List<Stmt> stmts = new ArrayList<>();
        while (!check(EOF)) {
            if (errors != null && check(RBRACE)) { record(error("Unexpected '}'")); p++; continue; }
            stmts.add(statementOrRecover());
        }
        main.body = span(new Block(hoistDecls(stmts), 1), 0);
        finishFunction(main);
        main.topLevel = new java.util.LinkedHashMap<>();
        for (var e : fs.scopes.get(0).entrySet()) {
            VarInfo v = e.getValue();
            if (v.isConst) continue;
            main.topLevel.put(e.getKey(), new int[]{v.slot, v.prim() ? 1 : 0, v.type.ordinal()});
        }
        finishFixedTargets();
        tolerate(this::checkNewArity);
        return main;
    }

    /**
     * `new C(a, b)` with a known class: the argument count must match the constructor - as in
     * Java. Functions have Lua semantics (extras dropped, missing ones null), but a class has one
     * statically known constructor, and a mismatch here is always a typo: `new P(1, 2)` with `P(x)`
     * used to create the object silently. The dynamic path (`new v(...)` where v is a variable)
     * stays lenient. A class whose parent is outside the source does not know the arity - skipped.
     */
    private void checkNewArity() {
        for (PendingNew pn : pendingNews) {
            int want = ClassNode.ctorArity(pn.cls());
            if (want < 0 || want == pn.argc()) continue;
            ParseError e = new ParseError("Constructor " + pn.cls().name + " expects " + want + " argument" + (want == 1 ? "" : "s")
                    + ", got " + pn.argc(), pn.line(), pn.col());
            if (errors == null) throw e;
            record(e);
        }
    }

    // ---------- helpers ----------
    /** Tier 1 class loader: the interpreter's (via Modules), otherwise the shared one. Every node gets it on creation. */
    private me.padej.jumper.jit.ScriptLoader loader() {
        return modules != null ? modules.loader() : null;
    }

    private FunctionNode newFunctionNode(String name, VarType[] paramTypes, int line) {
        FunctionNode f = new FunctionNode(name, paramTypes, line);
        f.loader = loader();
        return f;
    }

    /**
     * Source ranges of the nodes (Expr and Stmt), when asked for: a tolerant parser always keeps them,
     * a strict one after {@link #keepRanges()}. Otherwise null and span() costs one comparison.
     */
    private java.util.IdentityHashMap<Object, Range> ranges;

    /** Keep the source range of every node (for tools); see {@link #ranges()}. */
    public Parser keepRanges() {
        if (ranges == null) ranges = new java.util.IdentityHashMap<>();
        return this;
    }

    /**
     * Node -> source range, after the parse; empty unless ranges were kept. A node has the range of the
     * innermost construct that built it (a node reused by desugaring keeps its first); nodes the parser
     * synthesized without source of their own have none.
     */
    public Map<Object, Range> ranges() {
        return ranges == null ? Map.of() : java.util.Collections.unmodifiableMap(ranges);
    }

    /** Records the range of a node: from the token at `start` to the last consumed token. */
    private <N> N span(N node, int start) {
        if (ranges != null && node != null && start < toks.size() && !ranges.containsKey(node)) {
            Token a = toks.get(start), b = p > start ? toks.get(p - 1) : a;
            ranges.put(node, new Range(a.line(), a.col(), b.line(), p > start ? b.endCol() : a.col()));
        }
        return node;
    }

    private Token peek() { return toks.get(p); }
    private Token peek(int k) { return toks.get(Math.min(p + k, toks.size() - 1)); }
    private Token prev() { return toks.get(p - 1); }
    private boolean check(TokenType t) { return peek().type() == t; }
    private boolean checkAt(int k, TokenType t) { return peek(k).type() == t; }

    private boolean match(TokenType... types) {
        for (TokenType t : types) if (check(t)) { p++; return true; }
        return false;
    }

    private Token expect(TokenType t, String what) {
        if (!check(t)) throw error("Expected " + what + " but got '" + peek().text() + "'");
        return toks.get(p++);
    }

    private ParseError error(String msg) {
        Token t = peek();
        ParseError e = new ParseError(msg, t.line(), t.col());
        e.atEof = t.type() == EOF;
        return e;
    }

    private int line() { return peek().line(); }

    // ---------- scopes ----------
    private void beginScope() { fs.scopes.add(new java.util.LinkedHashMap<>()); }
    private void endScope() { fs.scopes.remove(fs.scopes.size() - 1); }

    private VarInfo declare(String name, VarType type, Token at) {
        HashMap<String, VarInfo> scope = fs.scopes.get(fs.scopes.size() - 1);
        if (atReplTop()) { // REPL/module top level: the variable lives in a Map
            if (!allowRedeclare && scope.containsKey(name))
                throw new ParseError("Variable '" + name + "' is already declared in this scope", at.line(), at.col());
            VarInfo g = new VarInfo(-1, type, null, false);
            g.global = true;
            scope.put(name, g);
            return g;
        }
        if (scope.containsKey(name))
            throw new ParseError("Variable '" + name + "' is already declared in this scope", at.line(), at.col());
        VarInfo v = new VarInfo(type.isPrimitive() ? fs.nprims++ : fs.nslots++, type, null, false);
        if (type.isPrimitive()) fs.node.primTypes.add(type);
        scope.put(name, v);
        return v;
    }

    private void finishFunction(FunctionNode fn) {
        fn.nslots = fs.nslots;
        fn.nprims = fs.nprims;
        fn.returnType = fs.returnType;
    }

    private void declareConst(String name, Object value) {
        fs.scopes.get(fs.scopes.size() - 1).put(name, new VarInfo(-1, VarType.DYN, value, true));
    }

    private record Resolved(int depth, VarInfo info) {}

    private Resolved resolve(String name) {
        int depth = 0;
        for (FuncState s = fs; s != null; s = s.enclosing, depth++) {
            for (int i = s.scopes.size() - 1; i >= 0; i--) {
                VarInfo v = s.scopes.get(i).get(name);
                if (v != null) return new Resolved(depth, v);
            }
        }
        return null;
    }

    /** Number of hops from the current function to s, or -1. */
    private int depthTo(FuncState s) {
        int d = 0;
        for (FuncState k = fs; k != null; k = k.enclosing, d++) if (k == s) return d;
        return -1;
    }

    private Expr variable(Token nameTok) {
        String name = nameTok.text();
        int line = nameTok.line();
        Resolved r = resolve(name);
        if (r != null && r.info.isConst && (r.info.fn != null || r.info.cls != null)) {
            Expr e = constant(r.info.constant, line);   // a name from a module: a class as a type, a function as a direct call target
            varOf.put(e, r.info);
            return e;
        }
        if (r != null && !memberBoundary.isEmpty() && !name.equals("this")
                && (isMemberName(name) || staticInScope(name) != null)) {
            // a class member shadows variables declared outside the method (but not its locals)
            int b = depthTo(memberBoundary.peek());
            if (b >= 0 && r.depth > b) {
                Expr m = classMember(name, nameTok);
                if (m != null) return m;
            }
        }
        if (r != null) {
            if (r.info.isConst) return constant(r.info.constant, line);
            VarInfo v = r.info;
            if (v.global) {
                Expr g = new GlobalGet(replVars, name, line);
                // varOf is needed not only for functions but for classes too: `new P(...)` gets its
                // ClassNode from exactly here. Without v.cls in REPL mode (javax.script, i.e. the whole
                // benchmark) New's staticClass stayed null, directNew never kicked in, and every new
                // went through invokedynamic with an Object[] of arguments - 134 bytes per object instead of 72.
                if (v.fn != null || v.hoisted || v.cls != null || v.javaType != null) varOf.put(g, v);
                if (v.type != VarType.DYN) globalType.put(g, v.type);
                return g;
            }
            switch (v.type) {
                case INT -> { return new IntLocal(v.slot, r.depth, line); }
                case LONG -> { return new LongLocal(v.slot, r.depth, line); }
                case DOUBLE -> { return new DoubleLocal(v.slot, r.depth, line); }
                case BOOLEAN -> { return new BoolLocal(v.slot, r.depth, line); }
                default -> {}
            }
            Expr e = r.depth == 0 ? new Local(v.slot, line) : new Upvalue(r.depth, v.slot, line);
            if (v.type == VarType.STRING) e.type = VarType.STRING;
            e.staticClass = v.instCls;
            if (v.arrayComp != null) {
                e.arrayComp = v.arrayComp;
                if (v.arrayReads == null) v.arrayReads = new ArrayList<>();
                v.arrayReads.add(e);
            }
            varOf.put(e, v);
            if (name.equals("this") && !classStack.isEmpty()) thisRefs.add(e);
            return e;
        }
        Expr cm = classMember(name, nameTok);
        if (cm != null) return cm;
        if (globals.containsKey(name)) return constant(globals.get(name), line);
        if (replVars != null && replVars.containsKey(name)) return new GlobalGet(replVars, name, line);
        Class<?> cls = Interop.findClass(name);
        if (cls != null) return new Literal(new JavaClass(cls), line);
        if (name.equals("this")) throw new ParseError(classStack.isEmpty() ? "'this' is only valid inside a class"
                : "'this' is not available in a static method or static initializer", nameTok.line(), nameTok.col());
        throw new ParseError("Undefined variable '" + name + "'", nameTok.line(), nameTok.col());
    }

    private boolean isMemberName(String name) {
        for (ClassNode c : classStack) {
            for (ClassNode k = c; k != null; k = k.parentNode) {
                for (String f : k.fieldNames) if (f.equals(name)) return true;
                for (String m : k.methodNames) if (m.equals(name)) return true;
            }
        }
        return false;
    }

    /** Access node for a static member of class cls (cls is the class that declares it). */
    private Expr staticRef(ClassNode cls, String name, int line) {
        ClassNode owner = cls.staticOwner(name);
        return new StaticField(owner, name, owner.staticIndex(name), owner.staticType(name), line);
    }

    /** If the expression is a reference to a class declared in this source (or imported), its node. */
    private ClassNode classRefOf(Expr e) {
        VarInfo v = varOf.get(e);
        return v == null ? null : v.cls;
    }

    /** Static member visible from the current class, or null. */
    private ClassNode staticInScope(String name) {
        for (ClassNode c : classStack) {
            ClassNode so = c.staticOwner(name);
            if (so != null) return so;
        }
        return null;
    }

    /** Bare name inside a class: a static member, an instance field/method, or null. */
    private Expr classMember(String name, Token tok) {
        if (classStack.isEmpty()) return null;
        ClassNode so = staticInScope(name);
        if (so != null) return staticRef(so, name, tok.line());
        if (!isMemberName(name)) return null;
        if (resolve("this") == null)
            throw new ParseError("Cannot use instance member '" + name + "' in a static method or static initializer", tok.line(), tok.col());
        return member(variable(new Token(IDENT, "this", null, tok.line(), tok.col())), name, tok.line());
    }

    /**
     * obj.name: if obj is this of the current class and the instance layout is known at parse time,
     * the field is accessed by a fixed index with a known type (ThisField), otherwise a Member with an inline cache.
     */
    private Expr member(Expr obj, String name, int line) {
        ClassNode cr = classRefOf(obj);                    // Cls.staticMember: direct static access
        if (cr != null && cr.staticOwner(name) != null) return staticRef(cr, name, line);
        ClassNode cn = obj.staticClass;
        if (cn == null && thisRefs.contains(obj) && !classStack.isEmpty()) cn = classStack.peek();
        if (cn != null && cn.layoutKnown()) {
            int idx = cn.fieldIndex(name);
            if (idx >= 0) {
                ThisField f = new ThisField(obj, name, idx, cn.fieldType(name), cn, line);
                f.staticClass = cn.fieldClass(name);
                return f;
            }
        }
        return new Member(obj, name, line);
    }

    /** A variable name, or a fully qualified class name: java.util.HashMap (without import). */
    private Expr variableOrQualifiedClass(Token nameTok) {
        String name = nameTok.text();
        if (resolve(name) == null && !globals.containsKey(name) && Interop.findClass(name) == null
                && check(DOT) && (checkAt(1, IDENT) || isTypeKeyword(peek(1).type()))) {
            StringBuilder sb = new StringBuilder(name);
            int save = p;
            // a path segment may also be a type keyword: java.lang.String, java.lang.Integer
            while (check(DOT) && (checkAt(1, IDENT) || isTypeKeyword(peek(1).type()))) {
                p += 2;
                sb.append('.').append(prev().text());
                Class<?> cls = Interop.findClass(sb.toString());
                if (cls != null) return new Literal(new JavaClass(cls), nameTok.line());
            }
            p = save;
        }
        return variable(nameTok);
    }

    /** Constant literal with a static type. */
    private static Expr constant(Object v, int line) {
        if (v instanceof Integer i) return new IntLit(i, line);
        if (v instanceof Long l) return new LongLit(l, line);
        if (v instanceof Double d) return new DoubleLit(d, line);
        if (v instanceof Boolean b) return new BoolLit(b, line);
        Literal lit = new Literal(v, line);
        if (v instanceof String) lit.type = VarType.STRING;
        return lit;
    }

    /** A dynamic value for a typed primitive location goes through ToSlot (null -> default, no truthiness). */
    private static Expr toSlot(VarType target, Expr value) {
        return target.isPrimitive() && value.type == VarType.DYN ? new ToSlot(value, target, value.line) : value;
    }

    /** Static check: can a value of this type be stored in a variable of that type. */
    private void checkAssignable(VarType target, Expr value, Token at) {
        VarType vt = value.type;
        if (vt == VarType.DYN || target == VarType.DYN) return;
        boolean ok = switch (target) {
            case INT -> vt == VarType.INT;
            case LONG -> vt == VarType.INT || vt == VarType.LONG;
            case DOUBLE -> vt.isNumeric();
            case BOOLEAN -> vt == VarType.BOOLEAN;
            case STRING -> vt == VarType.STRING;
            default -> true;
        };
        if (!ok) throw new ParseError("Cannot assign " + vt.name().toLowerCase(java.util.Locale.ROOT) + " to " + target.name().toLowerCase(java.util.Locale.ROOT), at.line(), at.col());
    }

    private static VarType typeOf(TokenType t) {
        return switch (t) {
            case DYN -> VarType.DYN;
            case KW_INT -> VarType.INT;
            case KW_LONG -> VarType.LONG;
            case KW_DOUBLE -> VarType.DOUBLE;
            case KW_BOOLEAN -> VarType.BOOLEAN;
            case KW_STRING -> VarType.STRING;
            default -> null;
        };
    }

    private boolean isTypeKeyword(TokenType t) {
        return typeOf(t) != null;
    }

    // ---------- statements ----------
    private Stmt statement() {
        int s0 = p;
        return span(statement0(), s0);
    }

    private Stmt statement0() {
        Token t = peek();
        int line = t.line();
        switch (t.type()) {
            case IMPORT -> { return importStmt(); }
            case LBRACE -> { return block(); }
            case IF -> { return ifStmt(); }
            case WHILE -> { fs.node.hasLoop = true; return whileStmt(); }
            case DO -> { fs.node.hasLoop = true; return doWhileStmt(); }
            case FOR -> { fs.node.hasLoop = true; return forStmt(); }
            case RETURN -> {
                p++;
                Expr v = check(SEMI) ? null : expression();
                if (v != null && fs.returnType == VarType.VOID)
                    throw new ParseError("Cannot return a value from a void function", t.line(), t.col());
                if (v != null) { checkAssignable(fs.returnType, v, t); v = toSlot(fs.returnType, v); }
                if (v != null && fs.returnClass != null) v = checked(v, fs.returnClass, t);
                if (v != null && fs.returnJava != null) v = checkedJava(v, fs.returnJava, t);
                expect(SEMI, "';'");
                return new Return(v, line);
            }
            case BREAK, CONTINUE -> {
                // Outside a loop they used to end the function silently (and kept it out of Tier 1)
                if (fs.loops == 0) throw error("'" + t.text() + "' outside of a loop");
                p++;
                expect(SEMI, "';'");
                return t.type() == BREAK ? new Break(line) : new Continue(line);
            }
            case SEMI -> { p++; return new Empty(line); }
            case VOID -> { p++; return funcDecl(VarType.VOID, null); }
            case CLASS -> { return classDecl(); }
            case THROW -> {
                p++;
                Expr v = expression();
                expect(SEMI, "';'");
                return new Throw(v, line);
            }
            case TRY -> { return tryStmt(); }
            case SWITCH -> { return switchStmt(); }
            default -> {}
        }
        if (isTypeKeyword(t.type()) && checkAt(1, IDENT)) {
            VarType type = typeOf(t.type());
            if (checkAt(2, LPAREN)) { p++; return funcDecl(type, null); }
            return varDecl();
        }
        ClassNode ct = classTypeAt(0);
        if (ct != null) {
            if (checkAt(2, LPAREN)) { p++; return funcDecl(VarType.DYN, ct); }
            return varDecl();
        }
        Class<?> jt = javaTypeAt(0);
        if (jt != null) {
            if (checkAt(2, LPAREN)) { p++; return funcDecl(VarType.DYN, null, jt); }
            return varDecl();
        }
        if (t.type() == IDENT && t.text().equals("function") && (checkAt(1, IDENT) || checkAt(1, LPAREN)))
            throw error("'function' is not a keyword: declare functions as 'dyn name(...)', 'void name(...)' or 'int name(...)', lambdas as '(x) -> ...'");
        // Java's `dyn` is inferred-and-fixed; Jumper's dynamic variable is `dyn` - a different thing, so a different word
        if (t.type() == IDENT && t.text().equals("var") && checkAt(1, IDENT))
            throw error("'var' is not a keyword in Jumper: a dynamically typed variable is 'dyn name = ...' (a typed one: int, long, double, boolean, String or a class name)");
        Expr e = expression();
        expect(SEMI, "';'");
        return new ExprStmt(e, line);
    }

    private Stmt importStmt() {
        Token at = expect(IMPORT, "import");
        if (check(TokenType.STRING)) return importModule(at);
        StringBuilder sb = new StringBuilder(expect(IDENT, "class name").text());
        while (match(DOT)) sb.append('.').append(expect(IDENT, "identifier").text());
        expect(SEMI, "';'");
        String full = sb.toString();
        Class<?> cls = Interop.findClass(full);
        if (cls == null) throw new ParseError("Class not found: " + full, at.line(), at.col());
        // `import java.util.List;` twice is harmless; over another binding of this scope it would silently replace it
        VarInfo prev = fs.scopes.get(fs.scopes.size() - 1).get(cls.getSimpleName());
        if (prev != null && !allowRedeclare
                && !(prev.isConst && prev.constant instanceof JavaClass jc && jc.cls() == cls))
            throw new ParseError("Imported name '" + cls.getSimpleName() + "' conflicts with a declaration in this scope", at.line(), at.col());
        declareConst(cls.getSimpleName(), new JavaClass(cls));
        return new Empty(at.line());
    }

    /** import "utils.jmp"; the module's top-level names become constants of this scope. */
    private Stmt importModule(Token at) {
        String spec = (String) toks.get(p++).value();
        expect(SEMI, "';'");
        return importModule(at, spec);
    }

    /** Loads a module and declares its names in the current scope (importing the same file again is a no-op). */
    private Stmt importModule(Token at, String spec) {
        if (modules == null) modules = new me.padej.jumper.interp.Modules(globals);
        me.padej.jumper.runtime.Access policy = modules.access();
        if (policy != null && !policy.modulesAllowed())
            throw new me.padej.jumper.runtime.ScriptSecurityException("Access denied: import \"" + spec + "\" (modules are not allowed by the access policy)");
        java.nio.file.Path file = modules.resolve(baseDir, spec);
        if (!java.nio.file.Files.exists(file))
            throw new ParseError("Module not found: " + spec, at.line(), at.col());
        HashMap<String, VarInfo> into = fs.scopes.get(fs.scopes.size() - 1);
        if (!imported.computeIfAbsent(into, k -> new java.util.HashSet<>()).add(file)) return new Empty(at.line());
        Map<String, Export> exported;
        try {
            exported = modules.exports(file);
        } catch (me.padej.jumper.runtime.JmpError e) {
            throw new ParseError(e.message(), at.line(), at.col());
        }
        // a module only parsed for a check (not run): its errors belong to the import - the names it
        // does declare are still imported
        String problem = modules.problem(file);
        if (problem != null) {
            ParseError pe = new ParseError("In module " + spec + ", " + problem, at.line(), at.col());
            if (errors != null) record(pe); else throw pe;
        }
        // REPL: names must outlive the input line, so they also go into the session variables
        boolean toSession = allowRedeclare && replVars != null && fs.enclosing == null;
        HashMap<String, VarInfo> scope = fs.scopes.get(fs.scopes.size() - 1);
        for (Map.Entry<String, Export> en : exported.entrySet()) {
            Export ex = en.getValue();
            if (toSession) replVars.put(en.getKey(), ex.value());
            if (scope.containsKey(en.getKey()))
                throw new ParseError("Imported name '" + en.getKey() + "' conflicts with a declaration in this scope", at.line(), at.col());
            VarInfo v = new VarInfo(-1, VarType.DYN, ex.value(), true);
            v.cls = ex.cls();
            v.fn = ex.fn();
            scope.put(en.getKey(), v);
        }
        return new Empty(at.line());
    }

    private Block block() {
        int s0 = p;
        return span(block0(), s0);
    }

    private Block block0() {
        int line = line();
        expect(LBRACE, "'{'");
        beginScope();
        tolerate(this::hoistFunctions);
        List<Stmt> stmts = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) stmts.add(statementOrRecover());
        tolerate(() -> expect(RBRACE, "'}'"));
        endScope();
        return new Block(hoistDecls(stmts), line);
    }

    private Stmt varDecl() {
        int line = line();
        ClassNode ct = classTypeAt(0);
        Class<?> jt = ct == null ? javaTypeAt(0) : null;
        VarType type = ct != null || jt != null ? VarType.DYN : typeOf(toks.get(p).type());
        p++;
        List<Stmt> decls = new ArrayList<>();
        do {
            Token name = expect(IDENT, "variable name");
            Expr init = match(EQ) ? adaptToPrim(expression(), type) : null;
            if (init != null) { checkAssignable(type, init, name); init = toSlot(type, init); }
            if (init != null && ct != null) init = checked(init, ct, name);
            if (init != null && jt != null) init = checkedJava(init, jt, name);
            // declare AFTER the initializer: `dyn x = x` must not see the new x
            VarInfo v = declare(name.text(), type, name);
            v.instCls = ct;
            v.javaType = jt;
            // `dyn a = new int[n]`: the element type is statically known. It holds exactly until
            // the first assignment to the name: after that the variable may hold anything.
            if (type == VarType.DYN && init != null && init.arrayComp != null) {
                v.arrayComp = init.arrayComp;
                arrayVars.add(v);
            }
            if (v.global) decls.add(new ExprStmt(new GlobalSet(replVars, name.text(), type, init, line), line));
            else decls.add(v.prim() ? new PrimVarDecl(v.slot, type, init, line) : new VarDecl(v.slot, type, init, line));
        } while (match(COMMA));
        expect(SEMI, "';'");
        return decls.size() == 1 ? decls.get(0) : new Block(decls.toArray(new Stmt[0]), line);
    }

    private Stmt funcDecl(VarType returnType, ClassNode returnClass) {
        return funcDecl(returnType, returnClass, null);
    }

    private Stmt funcDecl(VarType returnType, ClassNode returnClass, Class<?> returnJava) {
        notInConfig(peek(), "A function");
        Token name = expect(IDENT, "function name");
        // declare the slot before the body (recursion); the name may already be hoisted by hoistFunctions()
        VarInfo v = declareHoistable(name);
        FunctionNode fn = functionRest(name.text(), name.line(), returnType, returnClass, v, returnJava);
        Stmt d = v.global
                ? new ExprStmt(new GlobalSet(replVars, name.text(), VarType.DYN, new Lambda(fn, name.line()), name.line()), name.line())
                : new FuncDecl(v.slot, fn, name.line());
        funcDecls.add(d);
        return d;
    }

    /** Declarations made by funcDecl: they run first in their block (see hoistDecls). */
    private final java.util.Set<Stmt> funcDecls = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * A named function exists from the start of its block, not from its line: the name is hoisted at
     * parse time (hoistFunctions), and here the closure's creation moves to the top too, keeping
     * the order of the declarations. Without it Tier 0 failed a call above the declaration
     * ("not a function") while Tier 1 called the target directly and succeeded. The closure
     * captures the frame, not values, so creating it earlier changes nothing else.
     */
    private Stmt[] hoistDecls(List<Stmt> stmts) {
        Stmt[] out = new Stmt[stmts.size()];
        int k = 0;
        for (Stmt st : stmts) if (funcDecls.contains(st)) out[k++] = st;
        if (k == 0) return stmts.toArray(out);
        for (Stmt st : stmts) if (!funcDecls.contains(st)) out[k++] = st;
        return out;
    }

    /** After the name: (params) { body }. */
    private FunctionNode functionRest(String name, int line) {
        return functionRest(name, line, null, null, null);
    }

    /** Parameter type: primitive/String/dyn, or a script class (then the node is added to classes, otherwise null). */
    private VarType paramType(List<ClassNode> classes, List<Class<?>> javas) {
        if (isTypeKeyword(peek().type())) { classes.add(null); javas.add(null); return typeOf(toks.get(p++).type()); }
        ClassNode ct = classTypeAt(0);
        if (ct != null) { p++; classes.add(ct); javas.add(null); return VarType.DYN; }
        Class<?> jt = javaTypeAt(0);
        if (jt != null) { p++; classes.add(null); javas.add(jt); return VarType.DYN; }
        classes.add(null);
        javas.add(null);
        return VarType.DYN;
    }

    private FunctionNode functionRest(String name, int line, VarType returnType, ClassNode returnClass, VarInfo declVar) {
        return functionRest(name, line, returnType, returnClass, declVar, null);
    }

    private FunctionNode functionRest(String name, int line, VarType returnType, ClassNode returnClass, VarInfo declVar, Class<?> returnJava) {
        expect(LPAREN, "'('");
        List<String> names = new ArrayList<>();
        List<VarType> types = new ArrayList<>();
        List<ClassNode> classes = new ArrayList<>();
        List<Class<?>> javas = new ArrayList<>();
        List<Token> toksParams = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                VarType pt = paramType(classes, javas);
                Token pn = expect(IDENT, "parameter name");
                names.add(pn.text());
                types.add(pt);
                toksParams.add(pn);
            } while (match(COMMA));
        }
        expect(RPAREN, "')'");
        FunctionNode fn = newFunctionNode(name, types.toArray(new VarType[0]), line);
        fn.returnClass = returnClass;
        if (declVar != null) { // before parsing the body: recursive calls get their target
            declVar.fn = fn;
            funcVars.add(declVar);
            if (declVar.pendingCalls != null) {
                for (Call call : declVar.pendingCalls) if (call.args.length == fn.nparams) bindCall(declVar, call);
                declVar.pendingCalls = null;
            }
        }
        FuncState saved = fs;
        fs = new FuncState(saved, fn);
        if (returnType != null) fs.returnType = returnType;
        fs.returnClass = returnClass;
        fs.returnJava = returnJava;
        for (int i = 0; i < names.size(); i++) {
            VarInfo pv = declare(names.get(i), types.get(i), toksParams.get(i));
            pv.instCls = classes.get(i);
            pv.javaType = javas.get(i);
            fn.paramIndex[i] = pv.slot;
        }
        fn.body = block();
        addParamChecks(fn, classes, javas, toksParams);
        finishFunction(fn);
        fs = saved;
        return fn;
    }

    /** Lambda: parameters already read; next comes `->` and an expression or a block. */
    private FunctionNode lambdaRest(List<Token> params, List<VarType> types, List<ClassNode> classes, List<Class<?>> javas, int line) {
        notInConfig(peek(), "A lambda");
        expect(ARROW, "'->'");
        FunctionNode fn = newFunctionNode("<lambda>", types.toArray(new VarType[0]), line);
        FuncState saved = fs;
        fs = new FuncState(saved, fn);
        for (int i = 0; i < params.size(); i++) {
            VarInfo pv = declare(params.get(i).text(), types.get(i), params.get(i));
            pv.instCls = classes.get(i);
            pv.javaType = javas.get(i);
            fn.paramIndex[i] = pv.slot;
        }
        if (check(LBRACE)) {
            fn.body = block();
        } else {
            Expr e = expression();
            fn.body = new Return(e, line);
        }
        addParamChecks(fn, classes, javas, params);
        finishFunction(fn);
        fs = saved;
        return fn;
    }

    private Stmt ifStmt() {
        int line = line();
        expect(IF, "if");
        expect(LPAREN, "'('");
        Expr cond = expression();
        expect(RPAREN, "')'");
        Stmt then = scopedStatement();
        Stmt otherwise = match(ELSE) ? scopedStatement() : null;
        return new If(cond, then, otherwise, line);
    }

    /** Single statement in its own scope (for if/while without braces). */
    private Stmt scopedStatement() {
        if (check(LBRACE)) return block();
        beginScope();
        Stmt s = statement();
        endScope();
        return s;
    }

    /** The body of a loop: break and continue are allowed inside (but not in a function or lambda declared there). */
    private Stmt loopBody() {
        fs.loops++;
        try {
            return scopedStatement();
        } finally {
            fs.loops--;
        }
    }

    private Stmt whileStmt() {
        int line = line();
        expect(WHILE, "while");
        expect(LPAREN, "'('");
        Expr cond = expression();
        expect(RPAREN, "')'");
        While w = new While(cond, loopBody(), line);
        w.cancel = Stmts.cancelOf(loader());
        return w;
    }

    private Stmt doWhileStmt() {
        int line = line();
        expect(DO, "do");
        Stmt body = loopBody();
        expect(WHILE, "while");
        expect(LPAREN, "'('");
        Expr cond = expression();
        expect(RPAREN, "')'");
        expect(SEMI, "';'");
        DoWhile dw = new DoWhile(body, cond, line);
        dw.cancel = Stmts.cancelOf(loader());
        return dw;
    }

    private Stmt forStmt() {
        int line = line();
        expect(FOR, "for");
        expect(LPAREN, "'('");
        beginScope();
        try {
            // for (type name : expr)
            ClassNode ct = classTypeAt(0);
            Class<?> jt = ct == null ? javaTypeAt(0) : null;
            if ((isTypeKeyword(peek().type()) || ct != null || jt != null) && checkAt(1, IDENT) && checkAt(2, COLON)) {
                VarType type = ct != null || jt != null ? VarType.DYN : typeOf(toks.get(p).type());
                p++;
                Token name = expect(IDENT, "variable name");
                expect(COLON, "':'");
                Expr iterable = expression();
                expect(RPAREN, "')'");
                VarInfo v = declare(name.text(), type, name);
                v.instCls = ct;
                v.javaType = jt;
                Stmt body = loopBody();
                if (jt != null)   // each element is checked before the body sees it
                    body = new Block(new Stmt[] {new ExprStmt(new CheckJava(new Local(v.slot, line), jt, line), line), body}, line);
                ForEach fe = new ForEach(v.slot, type, iterable, body, line);
                fe.cancel = Stmts.cancelOf(loader());
                fe.cls = ct;
                return fe;
            }
            Stmt init = null;
            if (!check(SEMI)) {
                if (isTypeKeyword(peek().type()) || classTypeAt(0) != null || javaTypeAt(0) != null) init = varDecl();
                else { init = new ExprStmt(expression(), line); expect(SEMI, "';'"); }
            } else p++;
            Expr cond = check(SEMI) ? null : expression();
            expect(SEMI, "';'");
            Expr update = check(RPAREN) ? null : expression();
            expect(RPAREN, "')'");
            Stmt body = loopBody();
            For fr = new For(init, cond, update, body, line);
            fr.cancel = Stmts.cancelOf(loader());
            return fr;
        } finally {
            endScope();
        }
    }

    private Stmt tryStmt() {
        int line = line();
        expect(TRY, "try");
        Stmt body = block();
        int catchSlot = -1;
        Stmt handler = null;
        if (match(CATCH)) {
            expect(LPAREN, "'('");
            Token name = expect(IDENT, "exception variable");
            expect(RPAREN, "')'");
            beginScope();
            catchSlot = declare(name.text(), VarType.DYN, name).slot;
            handler = block();
            endScope();
        }
        Stmt fin = match(FINALLY) ? block() : null;
        if (handler == null && fin == null) throw error("'try' without 'catch' or 'finally'");
        return new Try(body, catchSlot, handler, fin, line);
    }

    // ---------- switch ----------

    private int switchSeq;

    /** A selector that can be re-evaluated without side effects or extra cost. */
    private static boolean isSimpleRef(Expr e) {
        return e instanceof Literal || e instanceof Local || e instanceof Upvalue || e instanceof GlobalGet
                || e instanceof IntLit || e instanceof LongLit || e instanceof DoubleLit || e instanceof BoolLit
                || Prims.location(e) != null || e instanceof StaticField
                || (e instanceof ThisField tf && isSimpleRef(tf.self));
    }

    /** Read of the hidden selector variable. */
    private Expr tempRead(VarInfo v, String name, int line) {
        if (v.global) return new GlobalGet(replVars, name, line);
        if (v.prim()) return switch (v.type) {
            case INT -> new IntLocal(v.slot, 0, line);
            case LONG -> new LongLocal(v.slot, 0, line);
            case DOUBLE -> new DoubleLocal(v.slot, 0, line);
            default -> new BoolLocal(v.slot, 0, line);
        };
        Expr e = new Local(v.slot, line);
        if (v.type == VarType.STRING) e.type = VarType.STRING;
        return e;
    }

    /** Store of the selector into the hidden variable (an expression: returns the stored value). */
    private Expr tempWrite(VarInfo v, String name, Expr value, int line) {
        if (v.global) return new GlobalSet(replVars, name, VarType.DYN, value, line);
        if (v.prim()) return Prims.assign(v.type, v.slot, 0, value, line);
        return new AssignLocal(v.slot, v.type, value, line).typed(v.type);
    }

    /** `(expr)` and `{` of a switch; returns [selector for the first comparison, selector for the rest]. */
    private Expr[] switchHead(Token kw) {
        expect(LPAREN, "'('");
        Expr sel = expression();
        expect(RPAREN, "')'");
        expect(LBRACE, "'{'");
        if (isSimpleRef(sel)) return new Expr[]{sel, sel};
        String name = "<switch" + switchSeq++ + ">";
        VarInfo v = declare(name, sel.type.isPrimitive() ? sel.type : VarType.DYN, kw);
        return new Expr[]{tempWrite(v, name, sel, kw.line()), tempRead(v, name, kw.line())};
    }

    /** Labels of one branch: `case a, b` or `default`; null means default. */
    private List<Expr> caseLabels() {
        if (match(DEFAULT)) return null;
        expect(CASE, "'case' or 'default'");
        List<Expr> labels = new ArrayList<>();
        do labels.add(expression()); while (match(COMMA));
        return labels;
    }

    /** Branch condition: selector == any of the labels. */
    private Expr caseCond(Expr sel, List<Expr> labels, Token at) {
        Expr cond = makeBinary(EQEQ, sel, labels.get(0), at);
        for (int i = 1; i < labels.size(); i++) cond = new Logical(false, cond, makeBinary(EQEQ, sel, labels.get(i), at), at.line());
        return cond;
    }

    private void expectArrow() {
        if (check(COLON)) throw error("switch uses arrows: 'case 1 -> ...' (form with ':' and fallthrough is not supported)");
        expect(ARROW, "'->'");
    }

    /** switch statement: branches without fall-through, expanded into an if/else chain. */
    private Stmt switchStmt() {
        Token kw = expect(SWITCH, "switch");
        beginScope();
        try {
            Expr[] sel = switchHead(kw);
            List<Expr> conds = new ArrayList<>();
            List<Stmt> bodies = new ArrayList<>();
            Stmt dflt = null;
            boolean first = true;
            while (!check(RBRACE) && !check(EOF)) {
                Token at = peek();
                List<Expr> labels = caseLabels();
                expectArrow();
                Stmt body = check(LBRACE) ? block() : statement();
                if (labels == null) {
                    if (dflt != null) throw new ParseError("Duplicate 'default' in switch", at.line(), at.col());
                    dflt = body;
                } else {
                    conds.add(caseCond(first ? sel[0] : sel[1], labels, at));
                    bodies.add(body);
                    first = false;
                }
            }
            expect(RBRACE, "'}'");
            if (conds.isEmpty() && dflt == null) throw error("Empty switch");
            Stmt chain = dflt;
            for (int i = conds.size() - 1; i >= 0; i--) chain = new If(conds.get(i), bodies.get(i), chain, kw.line());
            if (conds.isEmpty()) // only default: the selector is still evaluated
                return new Block(new Stmt[]{new ExprStmt(sel[0], kw.line()), chain}, kw.line());
            return chain;
        } finally {
            endScope();
        }
    }

    /** switch expression: `dyn s = switch (x) { case 1 -> "one"; default -> "?" };` */
    private Expr switchExpr() {
        // its own scope: the hidden selector variable must not become a top-level name
        // (Script.names, engine bindings, module exports, Config.parseFull)
        beginScope();
        try { return switchExprBody(); } finally { endScope(); }
    }

    private Expr switchExprBody() {
        Token kw = expect(SWITCH, "switch");
        Expr[] sel = switchHead(kw);
        List<Expr> conds = new ArrayList<>(), values = new ArrayList<>();
        Expr dflt = null;
        boolean first = true;
        while (!check(RBRACE) && !check(EOF)) {
            Token at = peek();
            List<Expr> labels = caseLabels();
            expectArrow();
            if (check(LBRACE)) throw error("switch expression needs a value: 'case 1 -> \"one\";'");
            Expr value = expression();
            expect(SEMI, "';'");
            if (labels == null) {
                if (dflt != null) throw new ParseError("Duplicate 'default' in switch", at.line(), at.col());
                dflt = value;
            } else {
                conds.add(caseCond(first ? sel[0] : sel[1], labels, at));
                values.add(value);
                first = false;
            }
        }
        expect(RBRACE, "'}'");
        if (dflt == null) throw error("switch expression must have a 'default'");
        if (conds.isEmpty()) throw error("switch expression must have at least one 'case'");
        Expr chain = dflt;
        for (int i = conds.size() - 1; i >= 0; i--) chain = new Ternary(conds.get(i), values.get(i), chain, kw.line());
        return chain;
    }

    // ---------- classes ----------

    private Stmt classDecl() {
        Token kw = expect(CLASS, "class");
        Token name = expect(IDENT, "class name");
        Expr parent = null;
        ClassNode parentNode = null;
        Token pn = null;
        if (match(EXTENDS)) {
            pn = expect(IDENT, "superclass name");
            Resolved pr = resolve(pn.text());
            if (pr != null && pr.info.cls != null) parentNode = pr.info.cls;
            parent = variableOrQualifiedClass(pn);
        }
        VarInfo v = declareHoistable(name);
        expect(LBRACE, "'{'");
        ClassNode node = v.cls;
        if (node == null) { node = prescanClass(name.text(), p); resolveFieldClasses(node); }
        // A extends A, or A extends B extends A (classes of one block see each other through hoisting):
        // every walk up the parent chain - layout, field indexes, constructor arity - would never end
        for (ClassNode k = parentNode; k != null; k = k.parentNode) {
            if (k == node) throw new ParseError("Cyclic inheritance: class " + name.text() + " cannot extend "
                    + pn.text() + (parentNode == node ? " (itself)" : ", which already extends " + name.text()), pn.line(), pn.col());
        }
        node.parentNode = parentNode;
        node.hasParent = parent != null;
        for (int i = 0; i < node.fieldNames.length; i++) {
            String fn = node.fieldNames[i];
            for (int j = 0; j < i; j++) if (node.fieldNames[j].equals(fn))
                throw new ParseError("Field '" + fn + "' is already declared in " + name.text(), name.line(), name.col());
            if (parentNode != null && parentNode.fieldIndex(fn) >= 0)
                throw new ParseError("Field '" + fn + "' is already declared in a superclass of " + name.text(), name.line(), name.col());
        }
        v.cls = node;
        classStack.push(node);

        // field initializers: a synthetic function (this)
        FunctionNode initFn = newFunctionNode("<init " + name.text() + ">", new VarType[]{VarType.DYN}, kw.line());
        FuncState initState = new FuncState(fs, initFn);
        FuncState outer = fs;
        fs = initState;
        initFn.paramIndex[0] = declare("this", VarType.DYN, name).slot;
        fs = outer;
        List<Stmt> inits = new ArrayList<>();

        FunctionNode ctor = null;
        boolean ctorCallsSuper = false;
        List<FunctionNode> methods = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        List<FunctionNode> staticMethods = new ArrayList<>();
        List<String> staticMethodNames = new ArrayList<>();
        // static initializers: a synthetic function without parameters
        FunctionNode staticInitFn = newFunctionNode("<static " + name.text() + ">", new VarType[0], kw.line());
        FuncState staticState = new FuncState(fs, staticInitFn);
        List<Stmt> staticInits = new ArrayList<>();

        while (!check(RBRACE) && !check(EOF)) {
            if (match(SEMI)) continue;
            boolean isStatic = match(STATIC);
            Token t = peek();
            boolean typed = isTypeKeyword(t.type());
            ClassNode ct = typed ? null : classTypeAt(0);
            Class<?> jt = typed || ct != null ? null : javaTypeAt(0);
            if ((typed || ct != null || jt != null || t.type() == VOID) && checkAt(1, IDENT) && checkAt(2, LPAREN)) {
                // method: void / dyn / typed return / class
                p++;
                Token mn = expect(IDENT, "method name");
                VarType rt = typed ? typeOf(t.type()) : VarType.VOID;
                if (ct != null || jt != null) rt = VarType.DYN;
                // no overloading: a second method of the same name would silently replace the first
                if (methodNames.contains(mn.text()) || staticMethodNames.contains(mn.text()))
                    throw new ParseError("Method '" + mn.text() + "' is already declared in " + name.text(), mn.line(), mn.col());
                if (isStatic) {
                    staticMethods.add(methodRest(mn.text(), mn.line(), rt, ct, node, true, jt));
                    staticMethodNames.add(mn.text());
                } else {
                    methods.add(methodRest(mn.text(), mn.line(), rt, ct, node, false, jt));
                    methodNames.add(mn.text());
                }
            } else if ((typed || ct != null || jt != null) && checkAt(1, IDENT)) {
                // field(s); a Java class type is checked on the field's initializer
                VarType ft = ct != null || jt != null ? VarType.DYN : typeOf(toks.get(p).type());
                p++;
                do {
                    Token fname = expect(IDENT, "field name");
                    Expr init;
                    FuncState target = isStatic ? staticState : initState;
                    fs = target;
                    memberBoundary.push(target);
                    try {
                        if (match(EQ)) { // without an initializer the field already has its default value (0 / null)
                            init = expression();
                            if (jt != null) init = checkedJava(init, jt, fname);
                            if (isStatic) {
                                staticInits.add(new ExprStmt(makeAssign(staticRef(node, fname.text(), fname.line()), init, fname), fname.line()));
                            } else {
                                Expr self = variable(new Token(IDENT, "this", null, fname.line(), fname.col()));
                                inits.add(new ExprStmt(makeAssign(member(self, fname.text(), fname.line()), init, fname), fname.line()));
                            }
                        }
                    } finally {
                        memberBoundary.pop();
                        fs = outer;
                    }
                } while (match(COMMA));
                expect(SEMI, "';'");
            } else if (isStatic) {
                throw error("Expected a static field or method in class " + name.text());
            } else if (t.type() == IDENT && checkAt(1, LPAREN) && t.text().equals(name.text())) {
                p++;
                if (ctor != null) throw new ParseError("Class " + name.text() + " already has a constructor", t.line(), t.col());
                sawSuperCall = false;
                ctor = methodRest("<init>", t.line(), VarType.VOID, node);
                ctorCallsSuper = sawSuperCall;
            } else if (t.type() == IDENT && checkAt(1, LPAREN)) {
                throw new ParseError("Method '" + t.text() + "' needs a return type: void, dyn, int, ...", t.line(), t.col());
            } else throw error("Expected field, method or constructor in class " + name.text());
        }
        expect(RBRACE, "'}'");
        classStack.pop();

        FunctionNode fieldInit = null;
        if (!inits.isEmpty()) {
            fs = initState;
            initFn.body = new Block(inits.toArray(new Stmt[0]), kw.line());
            finishFunction(initFn);
            fs = outer;
            fieldInit = initFn;
        }
        node.methodNames = methodNames.toArray(new String[0]); // order = methods
        node.methodNodes = methods.toArray(new FunctionNode[0]);
        java.util.Map<String, FunctionNode> smn = new HashMap<>();
        for (int i = 0; i < staticMethods.size(); i++) smn.put(staticMethodNames.get(i), staticMethods.get(i));
        node.staticMethodNodes = smn;
        FunctionNode staticInit = null;
        if (!staticInits.isEmpty()) {
            fs = staticState;
            staticInitFn.body = new Block(staticInits.toArray(new Stmt[0]), kw.line());
            finishFunction(staticInitFn);
            fs = outer;
            staticInit = staticInitFn;
        }
        node.ctorNode = ctor;
        node.ctorCallsSuper = ctorCallsSuper;
        node.fieldInitNode = fieldInit;
        node.bodyParsed = true;
        ClassDecl decl = new ClassDecl(v.slot, node, parent, fieldInit, ctor, ctorCallsSuper,
                methods.toArray(new FunctionNode[0]), kw.line());
        decl.staticMethods = staticMethods.toArray(new FunctionNode[0]);
        decl.staticMethodNames = staticMethodNames.toArray(new String[0]);
        decl.staticInit = staticInit;
        if (v.global) decl.globalVars = replVars;
        return decl;
    }

    private FunctionNode methodRest(String name, int line, VarType returnType, ClassNode cls) {
        return methodRest(name, line, returnType, null, cls, false);
    }

    private FunctionNode methodRest(String name, int line, VarType returnType, ClassNode returnClass, ClassNode cls) {
        return methodRest(name, line, returnType, returnClass, cls, false, null);
    }

    private FunctionNode methodRest(String name, int line, VarType returnType, ClassNode returnClass, ClassNode cls, boolean isStatic) {
        return methodRest(name, line, returnType, returnClass, cls, isStatic, null);
    }

    /** Method/constructor: (params) { body }; a non-static one has an implicit first parameter this. */
    private FunctionNode methodRest(String name, int line, VarType returnType, ClassNode returnClass, ClassNode cls, boolean isStatic,
                                    Class<?> returnJava) {
        expect(LPAREN, "'('");
        List<Token> names = new ArrayList<>();
        List<VarType> types = new ArrayList<>();
        List<ClassNode> classes = new ArrayList<>();
        List<Class<?>> javas = new ArrayList<>();
        Token thisTok = peek();
        if (!isStatic) {
            types.add(VarType.DYN); // this
            classes.add(null);
            javas.add(null);
            names.add(thisTok);
        }
        if (!check(RPAREN)) {
            do {
                VarType pt = paramType(classes, javas);
                names.add(expect(IDENT, "parameter name"));
                types.add(pt);
            } while (match(COMMA));
        }
        expect(RPAREN, "')'");
        FunctionNode fn = newFunctionNode(cls.name + "." + name, types.toArray(new VarType[0]), line);
        fn.returnClass = returnClass;
        FuncState saved = fs;
        fs = new FuncState(saved, fn);
        if (returnType != null) fs.returnType = returnType;
        fs.returnClass = returnClass;
        fs.returnJava = returnJava;
        if (!isStatic) fn.paramIndex[0] = declare("this", VarType.DYN, thisTok).slot;
        for (int i = isStatic ? 0 : 1; i < names.size(); i++) {
            VarInfo pv = declare(names.get(i).text(), types.get(i), names.get(i));
            pv.instCls = classes.get(i);
            pv.javaType = javas.get(i);
            fn.paramIndex[i] = pv.slot;
        }
        memberBoundary.push(fs);
        try {
            fn.body = block();
        } finally {
            memberBoundary.pop();
        }
        addParamChecks(fn, classes, javas, names);
        finishFunction(fn);
        fs = saved;
        return fn;
    }

    /** Field and method names of a class before its bodies are parsed, so bare names inside methods resolve to this.x. */
    private ClassNode prescanClass(String className, int start) {
        List<String> fields = new ArrayList<>(), methods = new ArrayList<>(), statics = new ArrayList<>();
        List<VarType> types = new ArrayList<>(), staticTypes = new ArrayList<>();
        List<String> classes = new ArrayList<>(), staticClasses = new ArrayList<>();
        int i = start, depth = 0;
        while (i < toks.size()) {
            TokenType t = toks.get(i).type();
            if (t == EOF) break;
            if (depth > 0) {
                if (t == LBRACE || t == LPAREN || t == LBRACKET) depth++;
                else if (t == RBRACE || t == RPAREN || t == RBRACKET) depth--;
                i++;
                continue;
            }
            if (t == RBRACE) break;
            boolean isStatic = t == STATIC;
            if (isStatic) { i++; t = toks.get(i).type(); }
            boolean typed = isTypeKeyword(t);
            // Class type: any class of the block, including the one being parsed (`Node left` inside Node) and those declared below.
            // Only the name here; resolveFieldClasses() fills in the class node once all classes are scanned.
            String ct = (!typed && t == IDENT && i + 1 < toks.size() && toks.get(i + 1).type() == IDENT
                    && (toks.get(i).text().equals(className) || isClassName(toks.get(i).text()) || javaClassNamed(toks.get(i).text()) != null))
                    ? toks.get(i).text() : null;
            if ((typed || ct != null || t == VOID) && i + 2 < toks.size() && toks.get(i + 1).type() == IDENT && toks.get(i + 2).type() == LPAREN) {
                if (isStatic) { statics.add(toks.get(i + 1).text()); staticTypes.add(VarType.DYN); staticClasses.add(null); }
                else methods.add(toks.get(i + 1).text());
                i += 2; // '(' will raise depth
            } else if ((typed || ct != null) && i + 1 < toks.size() && toks.get(i + 1).type() == IDENT) {
                VarType ft = ct != null ? VarType.DYN : typeOf(t);
                i++;
                // field list: name [= expr] {, name [= expr]} ;
                while (i < toks.size()) {
                    if (toks.get(i).type() == IDENT) {
                        if (isStatic) { statics.add(toks.get(i).text()); staticTypes.add(ft); staticClasses.add(ct); }
                        else { fields.add(toks.get(i).text()); types.add(ft); classes.add(ct); }
                    }
                    i++;
                    int d = 0;
                    while (i < toks.size()) { // skip the initializer
                        TokenType x = toks.get(i).type();
                        if (d == 0 && (x == COMMA || x == SEMI || x == EOF)) break;
                        if (x == LBRACE || x == LPAREN || x == LBRACKET) d++;
                        else if (x == RBRACE || x == RPAREN || x == RBRACKET) d--;
                        i++;
                    }
                    if (i >= toks.size() || toks.get(i).type() != COMMA) break;
                    i++;
                }
                i++; // ';'
            } else if (t == IDENT && i + 1 < toks.size() && toks.get(i + 1).type() == LPAREN) {
                if (!toks.get(i).text().equals(className)) methods.add(toks.get(i).text());
                i++;
            } else if (t == LBRACE || t == LPAREN || t == LBRACKET) { depth++; i++; }
            else i++;
        }
        ClassNode cn = new ClassNode(className, fields.toArray(new String[0]), types.toArray(new VarType[0]), methods.toArray(new String[0]));
        cn.loader = loader();
        cn.fieldClasses = new ClassNode[classes.size()];
        cn.pendingFieldClassNames = classes.toArray(new String[0]);
        cn.staticNames = statics.toArray(new String[0]);
        cn.staticTypes = staticTypes.toArray(new VarType[0]);
        cn.staticClasses = new ClassNode[staticClasses.size()];
        cn.pendingStaticClassNames = staticClasses.toArray(new String[0]);
        return cn;
    }

    /** Class-type names of fields (see prescanClass) -> class nodes; the class's own name is the class itself. */
    private void resolveFieldClasses(ClassNode cn) {
        if (cn.pendingFieldClassNames == null) return;
        for (int i = 0; i < cn.pendingFieldClassNames.length; i++) {
            String n = cn.pendingFieldClassNames[i];
            cn.fieldClasses[i] = n == null ? null : n.equals(cn.name) ? cn : classNamed(n);
        }
        for (int i = 0; i < cn.pendingStaticClassNames.length; i++) {
            String n = cn.pendingStaticClassNames[i];
            cn.staticClasses[i] = n == null ? null : n.equals(cn.name) ? cn : classNamed(n);
        }
        cn.pendingFieldClassNames = null;
        cn.pendingStaticClassNames = null;
    }

    // ---------- expressions (Pratt) ----------
    public Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        int s0 = p;
        return span(assignment0(), s0);
    }

    private Expr assignment0() {
        Expr left = ternary();
        Token t = peek();
        if (match(EQ)) {
            Expr value = assignment();
            return makeAssign(left, value, t);
        }
        if (match(PLUSEQ, MINUSEQ, STAREQ, SLASHEQ, PERCENTEQ)) {
            checkLvalue(left, t);
            Expr value = assignment();
            if (left instanceof ThisField tf) {
                Expr combined = adaptToPrim(makeBinary(t.type(), left, value, t), tf.ftype);
                checkAssignable(tf.ftype, combined, t);
                combined = toSlot(tf.ftype, combined);
                return new ThisFieldSet(tf, combined, t.line());
            }
            if (left instanceof StaticField sf) {
                Expr combined = adaptToPrim(makeBinary(t.type(), left, value, t), sf.ftype);
                checkAssignable(sf.ftype, combined, t);
                combined = toSlot(sf.ftype, combined);
                return new StaticFieldSet(sf, combined, t.line());
            }
            if (left instanceof GlobalGet g && globalType.containsKey(g)) {
                VarType gt = globalType.get(g);
                Expr combined = adaptToPrim(makeBinary(t.type(), left, value, t), gt);
                checkAssignable(gt, combined, t);
                return new GlobalSet(g.vars, g.name, gt, toSlot(gt, combined), t.line());
            }
            int[] loc = Prims.location(left);
            if (loc != null) {
                Expr combined = adaptToPrim(makeBinary(t.type(), left, value, t), left.type);
                checkAssignable(left.type, combined, t);
                combined = toSlot(left.type, combined);
                return Prims.assign(left.type, loc[0], loc[1], combined, t.line());
            }
            return new CompoundAssign(left, t.type(), value, t.line());
        }
        return left;
    }

    /**
     * Value for a primitive variable of type t: if it is `a op b` (+ - *) where one side already has type t
     * and the other is dynamic, compute in type t (the dynamic side is converted on read). The result would be
     * converted to t anyway, so the semantics are the same, but without boxing intermediate values.
     */
    private Expr adaptToPrim(Expr e, VarType t) {
        if (!t.isPrimitive() || t == VarType.BOOLEAN || e.type != VarType.DYN || !(e instanceof Binary b)) return e;
        if (b.op != PLUS && b.op != MINUS && b.op != STAR && b.op != PLUSEQ && b.op != MINUSEQ && b.op != STAREQ) return e;
        Expr l = adaptToPrim(b.left, t), r = adaptToPrim(b.right, t);
        if (!((l.type == t && r.type == VarType.DYN) || (l.type == VarType.DYN && r.type == t))) return e;
        int code = Prims.opcode(b.op);
        return switch (t) {
            case INT -> new IntBin(code, l, r, e.line);
            case LONG -> new LongBin(code, l, r, e.line);
            default -> new DoubleBin(code, l, r, e.line);
        };
    }

    private Expr makeAssign(Expr target, Expr value, Token at) {
        checkLvalue(target, at);
        VarInfo tvi = varOf.get(target);
        if (tvi != null && tvi.javaType != null) value = checkedJava(value, tvi.javaType, at);   // `Random r` keeps its type
        if (target instanceof GlobalGet g) {
            // a typed top-level name keeps its type after the declaration too: `int n = 1; n = "s";`
            VarType gt = globalType.getOrDefault(g, VarType.DYN);
            value = adaptToPrim(value, gt);
            checkAssignable(gt, value, at);
            return new GlobalSet(g.vars, g.name, gt, toSlot(gt, value), at.line());
        }
        if (target instanceof ThisField tf) {
            checkAssignable(tf.ftype, value, at);
            value = toSlot(tf.ftype, value);
            if (tf.staticClass != null) value = checked(value, tf.staticClass, at);
            return new ThisFieldSet(tf, value, at.line());
        }
        if (target instanceof StaticField sf) {
            value = adaptToPrim(value, sf.ftype);
            checkAssignable(sf.ftype, value, at);
            value = toSlot(sf.ftype, value);
            if (sf.staticClass != null) value = checked(value, sf.staticClass, at);
            return new StaticFieldSet(sf, value, at.line());
        }
        int[] loc = Prims.location(target);
        if (loc != null) {
            value = adaptToPrim(value, target.type);
            checkAssignable(target.type, value, at);
            return Prims.assign(target.type, loc[0], loc[1], toSlot(target.type, value), at.line());
        }
        if (target instanceof Local l) {
            VarType st = slotType(0, l.slot);
            checkAssignable(st, value, at);
            if (target.staticClass != null) value = checked(value, target.staticClass, at);
            return new AssignLocal(l.slot, st, value, at.line()).typed(st);
        }
        if (target instanceof Upvalue u) {
            VarType st = slotType(u.depth, u.slot);
            checkAssignable(st, value, at);
            if (target.staticClass != null) value = checked(value, target.staticClass, at);
            return new AssignUpvalue(u.depth, u.slot, st, value, at.line()).typed(st);
        }
        return new Assign(target, value, at.line());
    }

    /**
     * Bind a call to a declared function. `fixedTarget` is a promise to Tier 1 that the variable
     * will always hold exactly this function: then it calls the body directly, without a check. The
     * promise is withdrawn if the name is assigned anywhere (see checkLvalue and finishFixedTargets),
     * and never given to REPL/ScriptEngine top-level names - the host replaces those via put().
     */
    private void bindCall(VarInfo vi, Call call) {
        call.target = vi.fn;
        call.staticClass = vi.fn.returnClass;
        if (vi.global) return;
        call.fixedTarget = true;
        if (vi.boundCalls == null) vi.boundCalls = new ArrayList<>();
        vi.boundCalls.add(call);
    }

    /** After the whole source is parsed: drop the direct binding where the name was reassigned after all. */
    private void finishFixedTargets() {
        for (VarInfo v : funcVars) {
            if (!v.reassigned || v.boundCalls == null) continue;
            for (Call c : v.boundCalls) c.fixedTarget = false;
        }
        // The same promise and the same caveat for the array element type: read nodes
        // parsed BEFORE the assignment must lose it too.
        for (VarInfo v : arrayVars) {
            if (!v.reassigned || v.arrayReads == null) continue;
            for (Expr e : v.arrayReads) e.arrayComp = null;
        }
    }

    private void checkLvalue(Expr e, Token at) {
        VarInfo vi = varOf.get(e);
        if (vi != null && vi.fn != null) vi.reassigned = true;   // the function name is reassigned: the direct binding is cancelled
        if (vi != null && vi.cls != null)
            throw new ParseError("Cannot assign to class '" + vi.cls.name + "'", at.line(), at.col());
        if (Prims.location(e) != null) return;
        if (e instanceof Local || e instanceof Upvalue || e instanceof Member || e instanceof ThisField
                || e instanceof StaticField || e instanceof Index || e instanceof GlobalGet) return;
        if (e instanceof Literal) throw new ParseError("Cannot assign to a constant or builtin", at.line(), at.col());
        throw new ParseError("Invalid assignment target", at.line(), at.col());
    }

    /** Type of an Object slot: look up the non-primitive VarInfo by (depth, slot). */
    private VarType slotType(int depth, int slot) {
        FuncState s = fs;
        for (int i = 0; i < depth; i++) s = s.enclosing;
        for (HashMap<String, VarInfo> scope : s.scopes)
            for (VarInfo v : scope.values()) if (!v.prim() && !v.isConst && v.slot == slot) return v.type;
        return VarType.DYN;
    }

    /** Binary operator: a specialized primitive node when the static types allow it. */
    private Expr makeBinary(TokenType op, Expr l, Expr r, Token at) {
        int line = at.line();
        int cmp = Prims.cmpcode(op);
        // double "dominates": double op X for - * / % and < <= > >= gives double/boolean for any numeric X
        // (otherwise a type error on both paths), so a DYN operand can be read as double without losing semantics
        boolean doubleDominant = (l.type == VarType.DOUBLE && r.type == VarType.DYN) || (l.type == VarType.DYN && r.type == VarType.DOUBLE);
        if (cmp >= 0) {
            if (l.type.isNumeric() && r.type.isNumeric()) {
                return switch (VarType.arith(l.type, r.type)) {
                    case INT -> new IntCmp(cmp, l, r, line);
                    case LONG -> new LongCmp(cmp, l, r, line);
                    default -> new DoubleCmp(cmp, l, r, line);
                };
            }
            if (doubleDominant && op != EQEQ && op != NE) return new DoubleCmp(cmp, l, r, line);
            return new Binary(op, l, r, line);
        }
        int code = Prims.opcode(op);
        VarType t = VarType.arith(l.type, r.type);
        // a shift has the type of its left operand (as in Java and as Ops.shl computes it): `int << long`
        // is an int shift. The typed nodes read both operands in one type, so it stays a dynamic node
        // with the static type int.
        if ((op == SHL || op == SHR || op == USHR) && l.type == VarType.INT && r.type == VarType.LONG)
            return new Binary(op, l, r, line).typed(VarType.INT);
        boolean bitwise = code >= Prims.opcode(AMP);
        if (doubleDominant && !bitwise && op != PLUS && op != PLUSEQ) return new DoubleBin(code, l, r, line);
        if (t == VarType.INT) return new IntBin(code, l, r, line);
        if (t == VarType.LONG) return new LongBin(code, l, r, line);
        if (t == VarType.DOUBLE) {
            if (bitwise) throw new ParseError("Operator " + at.text() + " requires integers, got double", at.line(), at.col());
            return new DoubleBin(code, l, r, line);
        }
        if ((op == PLUS || op == PLUSEQ) && (l.type == VarType.STRING || r.type == VarType.STRING))
            return new Binary(op, l, r, line).typed(VarType.STRING);
        if (l.type == VarType.BOOLEAN || r.type == VarType.BOOLEAN)
            throw new ParseError("Cannot apply '" + at.text() + "' to boolean", at.line(), at.col());
        if (l.type == VarType.STRING && r.type == VarType.STRING)
            throw new ParseError("Cannot apply '" + at.text() + "' to strings", at.line(), at.col());
        return new Binary(op, l, r, line);
    }

    private Expr ternary() {
        int s0 = p;
        return span(ternary0(), s0);
    }

    private Expr ternary0() {
        Expr cond = or();
        if (match(QUESTION)) {
            int line = prev().line();
            Expr a = assignment();
            expect(COLON, "':'");
            Expr b = assignment();
            return new Ternary(cond, a, b, line);
        }
        return cond;
    }

    private Expr or() {
        int s0 = p;
        Expr e = and();
        while (match(OROR)) e = span(new Logical(false, e, and(), prev().line()), s0);
        return e;
    }

    private Expr and() {
        int s0 = p;
        Expr e = bitOr();
        while (match(ANDAND)) e = span(new Logical(true, e, bitOr(), prev().line()), s0);
        return e;
    }

    private Expr bitOr() {
        int s0 = p;
        Expr e = bitXor();
        while (match(PIPE)) { Token op = prev(); e = span(makeBinary(PIPE, e, bitXor(), op), s0); }
        return e;
    }

    private Expr bitXor() {
        int s0 = p;
        Expr e = bitAnd();
        while (match(CARET)) { Token op = prev(); e = span(makeBinary(CARET, e, bitAnd(), op), s0); }
        return e;
    }

    private Expr bitAnd() {
        int s0 = p;
        Expr e = equality();
        while (match(AMP)) { Token op = prev(); e = span(makeBinary(AMP, e, equality(), op), s0); }
        return e;
    }

    private Expr equality() {
        int s0 = p;
        Expr e = comparison();
        while (match(EQEQ, NE)) {
            Token op = prev();
            e = span(makeBinary(op.type(), e, comparison(), op), s0);
        }
        return e;
    }

    private Expr comparison() {
        int s0 = p;
        Expr e = shift();
        while (match(LT, LE, GT, GE)) {
            Token op = prev();
            e = span(makeBinary(op.type(), e, shift(), op), s0);
        }
        return e;
    }

    private Expr shift() {
        int s0 = p;
        Expr e = additive();
        while (match(SHL, SHR, USHR)) {
            Token op = prev();
            e = span(makeBinary(op.type(), e, additive(), op), s0);
        }
        return e;
    }

    private Expr additive() {
        int s0 = p;
        Expr e = multiplicative();
        while (match(PLUS, MINUS)) {
            Token op = prev();
            Expr r = multiplicative();
            if (op.type() == PLUS && e instanceof Local l && r instanceof IntLit lit && e.type == VarType.DYN)
                e = span(new AddLocalConst(l.slot, lit.v, op.line()), s0);
            else if (op.type() == MINUS && e instanceof Local l && r instanceof IntLit lit && e.type == VarType.DYN)
                e = span(new AddLocalConst(l.slot, -lit.v, op.line()), s0);
            else e = span(makeBinary(op.type(), e, r, op), s0);
        }
        return e;
    }

    private Expr multiplicative() {
        int s0 = p;
        Expr e = unary();
        while (match(STAR, SLASH, PERCENT)) {
            Token op = prev();
            e = span(makeBinary(op.type(), e, unary(), op), s0);
        }
        return e;
    }

    private Expr unary() {
        int s0 = p;
        return span(unary0(), s0);
    }

    private Expr unary0() {
        Token t = peek();
        if (match(NOT)) return new Not(unary(), t.line());
        if (match(MINUS)) {
            if (check(INT) && peek().value() instanceof Long) { p++; return new IntLit(Integer.MIN_VALUE, t.line()); }
            if (check(LONG) && peek().value() instanceof java.math.BigInteger) { p++; return new LongLit(Long.MIN_VALUE, t.line()); }
            Expr e = unary();
            if (e instanceof IntLit lit) return new IntLit(-lit.v, t.line());
            if (e instanceof LongLit lit) return new LongLit(-lit.v, t.line());
            if (e instanceof DoubleLit lit) return new DoubleLit(-lit.v, t.line());
            return switch (e.type) {
                case INT -> new IntNeg(e, t.line());
                case LONG -> new LongNeg(e, t.line());
                case DOUBLE -> new DoubleNeg(e, t.line());
                default -> new Neg(e, t.line());
            };
        }
        if (match(PLUS)) return unary();
        if (match(PLUSPLUS, MINUSMINUS)) {
            Expr target = unary();
            checkLvalue(target, t);
            return makeInc(target, t.type() == PLUSPLUS ? 1 : -1, true, t);
        }
        return postfix();
    }

    private Expr makeInc(Expr target, int delta, boolean prefix, Token at) {
        int[] loc = Prims.location(target);
        if (loc != null) {
            if (target.type == VarType.INT) return new IntInc(loc[0], loc[1], delta, prefix, at.line());
            if (target.type == VarType.BOOLEAN) throw new ParseError("Cannot increment boolean", at.line(), at.col());
            return Prims.assign(target.type, loc[0], loc[1], makeBinary(PLUS, target, new IntLit(delta, at.line()), at), at.line());
        }
        if (target instanceof Local l) return new IncLocal(l.slot, delta, prefix, slotType(0, l.slot), at.line());
        if (target instanceof StaticField sf) {
            if (sf.ftype == VarType.INT) return new StaticFieldInc(sf, delta, prefix, at.line());
            if (sf.ftype == VarType.BOOLEAN || sf.ftype == VarType.STRING) throw new ParseError("Cannot increment " + sf.ftype.name().toLowerCase(java.util.Locale.ROOT) + " field", at.line(), at.col());
            if (sf.ftype.isPrimitive()) return new StaticFieldSet(sf, makeBinary(PLUS, target, new IntLit(delta, at.line()), at), at.line());
        }
        if (target instanceof ThisField tf) {
            if (tf.ftype == VarType.INT) return new ThisFieldInc(tf, delta, prefix, at.line());
            if (tf.ftype == VarType.BOOLEAN || tf.ftype == VarType.STRING) throw new ParseError("Cannot increment " + tf.ftype.name().toLowerCase(java.util.Locale.ROOT) + " field", at.line(), at.col());
            if (tf.ftype.isPrimitive()) return new ThisFieldSet(tf, makeBinary(PLUS, target, new IntLit(delta, at.line()), at), at.line());
        }
        return new IncGeneric(target, delta, prefix, at.line());
    }

    private Expr postfix() {
        int s0 = p;
        return span(postfix0(), s0);
    }

    private Expr postfix0() {
        int s0 = p;
        Expr e = primary();
        while (true) {
            e = span(e, s0);   // each link of a.b(c)[d] covers the chain up to it
            Token t = peek();
            if (match(DOT)) {
                if (check(CLASS)) { p++; e = new Member(e, "class", t.line()); continue; }
                // a keyword is a valid member name after a dot: cfg.groups.default, t.new
                Token name = isKeyword(peek().type()) ? toks.get(p++) : expect(IDENT, "member name");
                if (check(LPAREN)) {
                    ClassNode cr = classRefOf(e);
                    if (cr != null && cr.staticOwner(name.text()) != null) { // Cls.m(args): a static method
                        Expr[] sargs = arguments();
                        Call sc = new Call(staticRef(cr, name.text(), name.line()), sargs, name.line());
                        FunctionNode sfn = cr.staticMethodNode(name.text());
                        if (sfn != null && sfn.nparams == sargs.length) { sc.target = sfn; sc.staticClass = sfn.returnClass; }
                        e = sc;
                        continue;
                    }
                    Expr[] args = arguments();
                    MethodCall mc = new MethodCall(e, name.text(), args, name.line());
                    ClassNode rc = e.staticClass;
                    if (rc == null && thisRefs.contains(e) && !classStack.isEmpty()) rc = classStack.peek();
                    if (rc != null && rc.layoutKnown()) mc.recvClass = rc;
                    e = mc;
                } else e = member(e, name.text(), name.line());
            } else if (check(LPAREN)) {
                Expr[] args = arguments();
                if (e instanceof Member m) { e = new MethodCall(m.obj, m.name, args, t.line()); continue; }
                if (e instanceof Literal lit && args.length == 1 && lit.value instanceof JFunction bf) {
                    int kind = bf == Builtins.LEN ? BuiltinCall.LEN : bf == Builtins.STR ? BuiltinCall.STR : bf == Builtins.TYPE ? BuiltinCall.TYPE : -1;
                    // int(x)/long(x)/double(x) on a numeric argument is a conversion, not a call
                    if (kind < 0 && args[0].type.isNumeric()) {
                        if (bf == Builtins.INT) kind = BuiltinCall.TO_INT;
                        else if (bf == Builtins.LONG) kind = BuiltinCall.TO_LONG;
                        else if (bf == Builtins.DOUBLE) kind = BuiltinCall.TO_DOUBLE;
                    } else if (kind < 0 && args[0].type == VarType.DYN) {
                        // over a dyn argument: a node too, so Tier 1 can make it a cast under a guard
                        if (bf == Builtins.INT) kind = BuiltinCall.DYN_INT;
                        else if (bf == Builtins.LONG) kind = BuiltinCall.DYN_LONG;
                        else if (bf == Builtins.DOUBLE) kind = BuiltinCall.DYN_DOUBLE;
                    }
                    if (kind >= 0) { e = new BuiltinCall(kind, args[0], t.line()); continue; }
                }
                VarInfo vi = varOf.get(e);
                Call call = new Call(e, args, t.line());
                if (vi != null) {
                    if (vi.fn != null) { if (args.length == vi.fn.nparams) bindCall(vi, call); }
                    else if (vi.hoisted) {
                        if (vi.pendingCalls == null) vi.pendingCalls = new ArrayList<>();
                        vi.pendingCalls.add(call);
                    }
                }
                e = call;
            } else if (match(LBRACKET)) {
                Expr key = expression();
                expect(RBRACKET, "']'");
                e = new Index(e, key, t.line());
            } else if (match(PLUSPLUS, MINUSMINUS)) {
                checkLvalue(e, t);
                e = makeInc(e, t.type() == PLUSPLUS ? 1 : -1, false, t);
            } else break;
        }
        return e;
    }

    /** After `new T[`: a size or `]{...}`. */
    private Expr newArrayRest(Class<?> comp, int line) {
        expect(LBRACKET, "'['");
        if (match(RBRACKET)) {
            expect(LBRACE, "'{'");
            List<Expr> items = new ArrayList<>();
            if (!check(RBRACE)) {
                do { if (check(RBRACE)) break; items.add(expression()); } while (match(COMMA));
            }
            expect(RBRACE, "'}'");
            return new NewArray(comp, null, items.toArray(new Expr[0]), line);
        }
        Expr size = expression();
        expect(RBRACKET, "']'");
        return new NewArray(comp, size, null, line);
    }

    /** Does `[...]` look like indexing followed by a call (`new classes[i](...)`)? */
    private boolean scanIndexThenCall(int lbracketPos) {
        int depth = 0;
        for (int i = lbracketPos; i < toks.size(); i++) {
            TokenType t = toks.get(i).type();
            if (t == LBRACKET) depth++;
            else if (t == RBRACKET) { if (--depth == 0) return i + 1 < toks.size() && toks.get(i + 1).type() == LPAREN; }
            else if (t == EOF || t == SEMI) return false;
        }
        return false;
    }

    /** Java component class for a JMP type (int -> int.class etc.). */
    private static Class<?> javaComponent(VarType t) {
        return switch (t) {
            case INT -> int.class;
            case LONG -> long.class;
            case DOUBLE -> double.class;
            case BOOLEAN -> boolean.class;
            case STRING -> String.class;
            default -> Object.class;
        };
    }

    private Expr[] arguments() {
        expect(LPAREN, "'('");
        List<Expr> args = new ArrayList<>();
        if (!check(RPAREN)) {
            do args.add(expression()); while (match(COMMA));
        }
        expect(RPAREN, "')'");
        return args.toArray(new Expr[0]);
    }

    private Expr primary() {
        int s0 = p;
        return span(primary0(), s0);
    }

    private Expr primary0() {
        Token t = peek();
        int line = t.line();
        switch (t.type()) {
            case INT, LONG, DOUBLE, STRING -> {
                if (t.type() == INT && t.value() instanceof Long) throw error("Integer literal too large (use L suffix): " + t.text());
                if (t.type() == LONG && t.value() instanceof java.math.BigInteger) throw error("Long literal too large: " + t.text());
                p++;
                return constant(t.value(), line);
            }
            case TRUE -> { p++; return new BoolLit(true, line); }
            case FALSE -> { p++; return new BoolLit(false, line); }
            case NULL -> { p++; return new Literal(null, line); }
            case LBRACKET -> { return arrayLiteral(); }
            case LBRACE -> { return tableLiteral(); }
            case NEW -> {
                p++;
                if (isTypeKeyword(peek().type()) && checkAt(1, LBRACKET)) {   // new int[n], new double[]{...}
                    Class<?> comp = javaComponent(typeOf(toks.get(p++).type()));
                    return newArrayRest(comp, line);
                }
                Token cn = expect(IDENT, "class name");
                Expr cls = variableOrQualifiedClass(cn);
                while (true) { // new registry.cat(...), new classes[i](...)
                    if (match(DOT)) { Token mn = expect(IDENT, "member name"); cls = new Member(cls, mn.text(), mn.line()); }
                    else if (check(LBRACKET)) {
                        // new Foo[n] is a Java array; new classes[i](...) is a class from an expression
                        if (cls instanceof Literal lit && lit.value instanceof JavaClass jc && !scanIndexThenCall(p))
                            return newArrayRest(jc.cls(), line);
                        expect(LBRACKET, "'['");
                        Expr k = expression();
                        expect(RBRACKET, "']'");
                        cls = new Index(cls, k, line);
                    }
                    else break;
                }
                Expr[] args = arguments();
                New n = new New(cls, args, line);
                VarInfo ci = varOf.get(cls);
                if (ci != null && ci.cls != null) {
                    n.staticClass = ci.cls;
                    pendingNews.add(new PendingNew(ci.cls, args.length, line, t.col()));
                }
                return n;
            }
            case SWITCH -> { return switchExpr(); }
            case THIS -> { p++; return variable(new Token(IDENT, "this", null, line, t.col())); }
            case SUPER -> {
                p++;
                if (classStack.isEmpty()) throw new ParseError("'super' is only valid inside a class", t.line(), t.col());
                if (resolve("this") == null)
                    throw new ParseError("'super' is not available in a static method or static initializer", t.line(), t.col());
                ClassNode cn = classStack.peek();
                Expr self = variable(new Token(IDENT, "this", null, line, t.col()));
                if (check(LPAREN)) {
                    sawSuperCall = true;
                    return new SuperCall(cn, self, arguments(), line);
                }
                expect(DOT, "'(' or '.' after super");
                Token m = expect(IDENT, "method name");
                return new SuperMethodCall(cn, m.text(), self, arguments(), line);
            }
            case KW_INT, KW_LONG, KW_DOUBLE -> { // int(x), double(x): builtin converters
                if (checkAt(1, LPAREN)) { p++; return new Literal(globals.get(t.text()), line); }
                throw error("Unexpected type keyword '" + t.text() + "'");
            }
            case KW_STRING -> { // String.valueOf(...), String.format(...)
                p++;
                return new Literal(new JavaClass(String.class), line);
            }
            case IDENT -> {
                if (checkAt(1, ARROW)) { // x -> ...
                    p++;
                    return new Lambda(lambdaRest(List.of(t), List.of(VarType.DYN), java.util.Collections.singletonList(null), java.util.Collections.singletonList(null), line), line);
                }
                p++;
                return variableOrQualifiedClass(t);
            }
            case LPAREN -> {
                if (isLambdaAhead()) return lambda();
                p++;
                Expr e = expression();
                expect(RPAREN, "')'");
                return e;
            }
            default -> throw error("Unexpected token '" + t.text() + "'");
        }
    }

    /** ( [type] id, [type] id ) -> */
    private boolean isLambdaAhead() {
        int k = 1;
        if (checkAt(k, RPAREN)) return checkAt(k + 1, ARROW);
        while (true) {
            if (isTypeKeyword(peek(k).type()) || classTypeAt(k) != null || javaTypeAt(k) != null) k++;
            if (!checkAt(k, IDENT)) return false;
            k++;
            if (checkAt(k, COMMA)) { k++; continue; }
            return checkAt(k, RPAREN) && checkAt(k + 1, ARROW);
        }
    }

    private Expr lambda() {
        int line = line();
        expect(LPAREN, "'('");
        List<Token> params = new ArrayList<>();
        List<VarType> types = new ArrayList<>();
        List<ClassNode> classes = new ArrayList<>();
        List<Class<?>> javas = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                VarType pt = paramType(classes, javas);
                params.add(expect(IDENT, "parameter name"));
                types.add(pt);
            } while (match(COMMA));
        }
        expect(RPAREN, "')'");
        return new Lambda(lambdaRest(params, types, classes, javas, line), line);
    }

    private Expr arrayLiteral() {
        int line = line();
        expect(LBRACKET, "'['");
        List<Expr> items = new ArrayList<>();
        if (!check(RBRACKET)) {
            do {
                if (check(RBRACKET)) break; // trailing comma
                items.add(expression());
            } while (match(COMMA));
        }
        expect(RBRACKET, "']'");
        return new ArrayLit(items.toArray(new Expr[0]), line);
    }

    /** After `{`: does a `key:` come first (a table), or a plain element (an array `{a, b}`)? */
    private boolean tableEntryAhead() {
        TokenType t = peek().type();
        if (t == IDENT || t == STRING || t == INT || t == LONG || t == DOUBLE || isKeyword(t)) return checkAt(1, COLON);
        if (t == LBRACKET) {   // [expr]: v - a computed key; [a, b] - an array element
            int depth = 0;
            for (int i = p; i < toks.size(); i++) {
                TokenType u = toks.get(i).type();
                if (u == LBRACKET || u == LPAREN || u == LBRACE) depth++;
                else if (u == RBRACKET || u == RPAREN || u == RBRACE) {
                    if (--depth == 0) return i + 1 < toks.size() && toks.get(i + 1).type() == COLON;
                } else if (u == EOF) return false;
            }
        }
        return false;
    }

    /** A reserved word - usable as a table key and a member name, where it cannot be anything else. */
    private static boolean isKeyword(TokenType t) {
        return t.ordinal() >= DYN.ordinal() && t.ordinal() <= DEFAULT.ordinal();
    }

    private Expr tableLiteral() {
        int line = line();
        expect(LBRACE, "'{'");
        if (!check(RBRACE) && !tableEntryAhead()) {
            // `{"ye", 123, isDev}` - elements without keys: an array, as a Java array initializer
            List<Expr> items = new ArrayList<>();
            do {
                if (check(RBRACE)) break; // trailing comma
                items.add(expression());
            } while (match(COMMA));
            expect(RBRACE, "'}'");
            return new ArrayLit(items.toArray(new Expr[0]), line);
        }
        List<Expr> keys = new ArrayList<>(), values = new ArrayList<>();
        if (!check(RBRACE)) {
            do {
                if (check(RBRACE)) break;
                Token k = peek();
                Expr key;
                if (match(IDENT)) key = new Literal(k.text(), k.line());
                else if (isKeyword(k.type()) && checkAt(1, COLON)) { p++; key = new Literal(k.text(), k.line()); }   // { default: ..., class: ... }
                else if (match(STRING)) key = new Literal(k.value(), k.line());
                else if (check(INT) && k.value() instanceof Long) throw error("Integer literal too large (use L suffix): " + k.text());
                else if (check(LONG) && k.value() instanceof java.math.BigInteger) throw error("Long literal too large: " + k.text());
                else if (match(INT, LONG, DOUBLE)) key = new Literal(k.value(), k.line());
                else if (match(LBRACKET)) { key = expression(); expect(RBRACKET, "']'"); }
                else throw error("Expected table key");
                expect(COLON, "':'");
                keys.add(key);
                values.add(expression());
            } while (match(COMMA));
        }
        expect(RBRACE, "'}'");
        return new TableLit(keys.toArray(new Expr[0]), values.toArray(new Expr[0]), line);
    }
}
