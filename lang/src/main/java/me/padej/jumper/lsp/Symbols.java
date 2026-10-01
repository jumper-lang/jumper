package me.padej.jumper.lsp;

import me.padej.jumper.lexer.Lexer;
import me.padej.jumper.lexer.Token;
import me.padej.jumper.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static me.padej.jumper.lexer.TokenType.*;

/**
 * The names of a Jumper text and where each is used, from its tokens - for the outline (document symbols),
 * references, highlights and rename. Tolerant: a text that does not parse still has its symbols.
 *
 * <p>The scoping follows the language: a block is a scope, functions and classes are hoisted to the start
 * of theirs, a variable is visible after its declarator (`dyn x = x;` - the inner `x` is the outer one), a
 * parameter in its function (or lambda, or catch block), a class member inside the class body - after the
 * method's own locals, before the variables outside. `this.f` and `Cls.f` name members of a class of this
 * file (and of the classes it extends). A name that is not declared in the file - a host global, a
 * built-in, a name from a module - has no symbol here.
 */
final class Symbols {
    enum Kind { CLASS, FUNCTION, METHOD, CONSTRUCTOR, FIELD, VARIABLE, PARAMETER }

    static final class Sym {
        final String name;
        final Kind kind;
        final int nameTok;
        int startTok, endTok;
        final Sym owner;             // the class of a member, the function a nested symbol is in
        final List<Sym> children = new ArrayList<>();
        final Map<String, Sym> members = new LinkedHashMap<>();   // a class's
        String parentClass;          // `extends P`
        boolean isStatic;
        String detail;               // the type, or the parameter list of a function
        int activeFrom;              // a variable is visible from this token on (after its declarator)

        Sym(String name, Kind kind, int nameTok, Sym owner) {
            this.name = name;
            this.kind = kind;
            this.nameTok = nameTok;
            this.owner = owner;
        }

        @Override
        public String toString() {
            return kind + " " + name;
        }
    }

    private static final Set<TokenType> TYPE_KEYWORDS = Set.of(DYN, KW_INT, KW_LONG, KW_DOUBLE, KW_BOOLEAN, KW_STRING, VOID);
    private static final Set<TokenType> TABLE_OPENERS = Set.of(EQ, PLUSEQ, MINUSEQ, STAREQ, SLASHEQ, PERCENTEQ, LPAREN, COMMA,
            LBRACKET, COLON, RETURN, QUESTION, EQEQ, NE, ANDAND, OROR, NOT, PLUS, THROW);

    final List<Token> toks;
    private final int[] match;
    /** The symbol each token names (its declaration or a use), or null. */
    private final Sym[] refOf;
    private final Map<Sym, List<Integer>> uses = new IdentityHashMap<>();
    /** The symbols of the outline: classes, functions, top-level variables, in text order. */
    final List<Sym> top = new ArrayList<>();
    final List<Sym> all = new ArrayList<>();
    private final Set<Sym> inTop = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Sym> classes = new HashMap<>();
    /** The brackets open at the token the walk is at (their token indexes, innermost first). */
    private final Deque<Integer> open = new ArrayDeque<>();

    private static final class Scope {
        final Map<String, Sym> names = new HashMap<>();
        final int end;           // the last token of the scope
        final Sym cls;           // a class body: its class
        final Sym fn;            // the function whose body or parameters this is (for the outline)

        Scope(int end, Sym cls, Sym fn) {
            this.end = end;
            this.cls = cls;
            this.fn = fn;
        }
    }

    private final Deque<Scope> scopes = new ArrayDeque<>();
    /** Tokens already declared (a parameter, a hoisted function): the walk records them, does not look them up. */
    private final Map<Integer, Sym> declAt = new HashMap<>();
    /** `{` tokens of table literals (and arrays): no scope, keys are not names. */
    private final boolean[] tableBrace;

    Symbols(String text) {
        this.toks = Lexer.tokenizeTolerant(text, new ArrayList<>());
        this.match = matching();
        this.refOf = new Sym[toks.size()];
        this.tableBrace = new boolean[toks.size()];
        walk();
    }

    // ------------------------------------------------------------------ queries

    /** The symbol at a 1-based line and 0-based column (a declaration or a use of it), or null. */
    Sym at(int line, int col0) {
        int k = tokenAt(line, col0);
        return k < 0 ? null : refOf[k];
    }

    int tokenAt(int line, int col0) {
        int c = col0 + 1;
        for (int k = 0; k < toks.size(); k++) {
            Token t = toks.get(k);
            if (t.line() == line && t.type() == IDENT && c >= t.col() && c <= t.endCol()) return k;
            if (t.line() > line) break;
        }
        return -1;
    }

    /** Every token naming the symbol, its declaration included, in text order. */
    List<Token> occurrences(Sym s) {
        List<Token> out = new ArrayList<>();
        for (int k : uses.getOrDefault(s, List.of())) out.add(toks.get(k));
        out.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.col(), b.col()));
        return out;
    }

    /** The outline: top-level classes, functions and variables with what is inside them, in text order. */
    List<Sym> outline() {
        sort(top);
        return top;
    }

    private static void sort(List<Sym> syms) {
        syms.sort(java.util.Comparator.comparingInt(x -> x.nameTok));
        for (Sym x : syms) sort(x.children);
    }

    Sym symbolOf(int tok) {
        return tok >= 0 && tok < refOf.length ? refOf[tok] : null;
    }

    // ------------------------------------------------------------------ the walk

    private void walk() {
        int n = toks.size();
        scopes.push(new Scope(n, null, null));
        hoist(0, n - 1, null);
        for (int i = 0; i < n; i++) {
            while (scopes.size() > 1 && scopes.peek().end < i) scopes.pop();
            Token t = toks.get(i);
            TokenType ty = t.type();
            if (ty == EOF) break;
            TokenType pt = i > 0 ? toks.get(i - 1).type() : null;
            if (ty == RBRACE || ty == RPAREN || ty == RBRACKET) { if (!open.isEmpty()) open.pop(); }
            else if (ty == LPAREN || ty == LBRACKET) open.push(i);
            if (ty == IMPORT) {   // `import a.b.C;` - not names of this file
                while (i + 1 < n && toks.get(i + 1).type() != SEMI && toks.get(i + 1).type() != EOF) i++;
                continue;
            }
            if (ty == LBRACE) {
                boolean parentTable = i > 0 && enclosingTable(i);
                tableBrace[i] = pt != null && (TABLE_OPENERS.contains(pt) || parentTable && (pt == LBRACE || pt == COMMA || pt == COLON)) && pt != ARROW;
                open.push(i);
                if (!tableBrace[i] && match[i] > 0 && !isClassBrace(i)) {
                    Scope s = new Scope(match[i], null, currentFn());
                    scopes.push(s);
                    hoist(i + 1, match[i], currentFn());
                }
                continue;
            }
            if (ty == TokenType.CLASS && next(i) == IDENT) { classDecl(i); continue; }
            if (ty == FOR && next(i) == LPAREN && match[i + 1] > 0) {
                scopes.push(new Scope(statementEnd(match[i + 1] + 1), null, currentFn()));
                continue;
            }
            if (ty == CATCH && next(i) == LPAREN && i + 3 < n && toks.get(i + 2).type() == IDENT && toks.get(i + 3).type() == RPAREN) {
                int body = i + 4;
                Scope s = new Scope(body < n && toks.get(body).type() == LBRACE && match[body] > 0 ? match[body] : body, null, currentFn());
                scopes.push(s);
                param(i + 2, s);
                continue;
            }
            // lambdas: `x -> ...`, `(a, b) -> ...`
            if (ty == IDENT && next(i) == ARROW && !declAt.containsKey(i)) {
                Scope s = new Scope(lambdaEnd(i + 1), null, currentFn());
                scopes.push(s);
                param(i, s);
            } else if (ty == LPAREN && match[i] > 0 && match[i] + 1 < n && toks.get(match[i] + 1).type() == ARROW) {
                Scope s = new Scope(lambdaEnd(match[i] + 1), null, currentFn());
                scopes.push(s);
                for (int p : paramNames(i, match[i])) param(p, s);
                continue;
            }
            if (ty != IDENT) continue;

            Sym declared = declAt.get(i);
            if (declared != null && (declared.kind == Kind.FUNCTION || declared.kind == Kind.METHOD || declared.kind == Kind.CONSTRUCTOR)
                    && next(i) == LPAREN && match[i + 1] > 0 && match[i + 1] + 1 < n && toks.get(match[i + 1] + 1).type() == LBRACE) {
                function(declared.kind == Kind.CONSTRUCTOR ? i : i - 1, i);
                continue;
            }
            if (declared != null) { use(i, declared); continue; }
            if (declAt.containsKey(i)) continue;
            // a declaration: `T name (` (a function), `T name =|;|,|)|:` (a variable)
            if (isTypeAt(i - 1) && !(pt == IDENT && i >= 2 && toks.get(i - 2).type() == DOT)) {
                if (next(i) == LPAREN && match[i + 1] > 0 && match[i + 1] + 1 < n && toks.get(match[i + 1] + 1).type() == LBRACE) {
                    function(i - 1, i);
                    continue;
                }
                TokenType nt = next(i);
                if (nt == EQ || nt == SEMI || nt == COMMA || nt == RPAREN || nt == COLON) {
                    variables(i - 1, i);
                    continue;
                }
            }
            // a member: `this.f`, `Cls.f`; anything else after a dot is not ours
            if (pt == DOT) {
                Sym owner = null;
                if (i >= 2 && toks.get(i - 2).type() == THIS) owner = enclosingClass();
                else if (i >= 2 && refOf[i - 2] != null && refOf[i - 2].kind == Kind.CLASS) owner = refOf[i - 2];
                Sym m = owner == null ? null : member(owner, t.text());
                if (m != null) use(i, m);
                continue;
            }
            // `{ key: v }`: a key, not a name
            if (next(i) == COLON && (pt == LBRACE || pt == COMMA) && inTable(i)) continue;
            Sym s = lookup(t.text(), i);
            if (s != null) use(i, s);
        }
    }

    /** Functions and classes of the block [from, to) at its top level: known from its start. */
    private void hoist(int from, int to, Sym fn) {
        Scope s = scopes.peek();
        int depth = 0;
        for (int k = from; k < to && k < toks.size(); k++) {
            TokenType ty = toks.get(k).type();
            if (ty == LBRACE || ty == LPAREN || ty == LBRACKET) depth++;
            else if (ty == RBRACE || ty == RPAREN || ty == RBRACKET) depth--;
            if (depth != 0) continue;
            if (ty == TokenType.CLASS && next(k) == IDENT && !declAt.containsKey(k + 1)) {
                Sym c = new Sym(toks.get(k + 1).text(), Kind.CLASS, k + 1, fn);
                declare(c, s, -1);
                declAt.put(k + 1, c);
            } else if (ty == IDENT && isTypeAt(k - 1) && !(k >= 2 && toks.get(k - 2).type() == DOT) && next(k) == LPAREN
                    && match[k + 1] > 0 && match[k + 1] + 1 < toks.size() && toks.get(match[k + 1] + 1).type() == LBRACE
                    && !declAt.containsKey(k)) {
                Sym f = new Sym(toks.get(k).text(), Kind.FUNCTION, k, fn);
                declare(f, s, -1);
                declAt.put(k, f);
            }
        }
    }

    /** `T name(params) { body }` at `typeTok`, `nameTok`: the function (declared by the hoisting), its parameters and body. */
    private void function(int typeTok, int nameTok) {
        Scope outer = scopes.peek();
        Sym f = declAt.get(nameTok);
        if (f == null) {
            f = new Sym(toks.get(nameTok).text(), outer.cls != null ? Kind.METHOD : Kind.FUNCTION, nameTok, owner());
            declare(f, outer, -1);
        }
        int open = nameTok + 1, close = match[open], body = close + 1;
        f.startTok = staticBefore(typeTok) ? typeTok - 1 : typeTok;
        f.endTok = match[body];
        boolean ctor = f.kind == Kind.CONSTRUCTOR;
        f.detail = text(open, close) + (ctor || toks.get(typeTok).type() == VOID ? "" : ": " + toks.get(typeTok).text());
        use(nameTok, f);
        Scope params = new Scope(match[body], null, f);
        scopes.push(params);
        for (int p : paramNames(open, close)) param(p, params);
    }

    /** A parameter (of a function, a lambda, a catch) at token `p`, in scope `s`. */
    private void param(int p, Scope s) {
        Sym v = new Sym(toks.get(p).text(), Kind.PARAMETER, p, s.fn != null ? s.fn : currentFn());
        declare(v, s, -1);
        declAt.put(p, v);
    }

    /** `T a = 1, b;` at `typeTok`, the first name at `nameTok`: each declarator, visible after itself. */
    private void variables(int typeTok, int nameTok) {
        Scope s = scopes.peek();
        int k = nameTok;
        boolean isStatic = staticBefore(typeTok);
        while (true) {
            Kind kind = s.cls != null ? Kind.FIELD : Kind.VARIABLE;
            Sym v = declAt.get(k);
            int end = declaratorEnd(k);
            if (v == null) {
                v = new Sym(toks.get(k).text(), kind, k, owner());
                v.isStatic = isStatic;
                declare(v, s, next(k) == COLON ? forEnd(k) : end);
            }
            v.startTok = isStatic ? typeTok - 1 : typeTok;
            v.endTok = end < toks.size() && toks.get(end).type() == SEMI ? end : end - 1;
            v.detail = toks.get(typeTok).text();
            use(k, v);
            if (end + 2 < toks.size() && toks.get(end).type() == COMMA && toks.get(end + 1).type() == IDENT) {
                TokenType after = toks.get(end + 2).type();
                if (after == EQ || after == SEMI || after == COMMA) {
                    k = end + 1;
                    declAt.put(k, null);
                    continue;
                }
            }
            break;
        }
    }

    /** `class N [extends P] { members }` at the `class` token. */
    private void classDecl(int at) {
        int name = at + 1;
        Sym c = declAt.get(name);
        if (c == null) {
            c = new Sym(toks.get(name).text(), Kind.CLASS, name, owner());
            declare(c, scopes.peek(), -1);
        }
        use(name, c);
        int k = name + 1;
        if (k < toks.size() && toks.get(k).type() == EXTENDS && k + 1 < toks.size() && toks.get(k + 1).type() == IDENT) {
            c.parentClass = toks.get(k + 1).text();
            Sym p = lookup(c.parentClass, k + 1);
            if (p != null) use(k + 1, p);
            k += 2;
            while (k + 1 < toks.size() && toks.get(k).type() == DOT) k += 2;
        }
        c.startTok = at;
        if (k >= toks.size() || toks.get(k).type() != LBRACE || match[k] < 0) { c.endTok = name; return; }
        c.endTok = match[k];
        Scope body = new Scope(match[k], c, c);
        scopes.push(body);
        // its members are known in the whole body
        int depth = 0;
        for (int j = k + 1; j < match[k]; j++) {
            TokenType ty = toks.get(j).type();
            if (ty == LBRACE || ty == LPAREN || ty == LBRACKET) { depth++; continue; }
            if (ty == RBRACE || ty == RPAREN || ty == RBRACKET) { depth--; continue; }
            if (depth != 0 || ty != IDENT || declAt.containsKey(j)) continue;
            Sym m = null;
            if (toks.get(j).text().equals(c.name) && next(j) == LPAREN && (j == 0 || toks.get(j - 1).type() != NEW)) {
                m = new Sym(c.name, Kind.CONSTRUCTOR, j, c);
            } else if (isTypeAt(j - 1)) {
                TokenType nt = next(j);
                if (nt == LPAREN) m = new Sym(toks.get(j).text(), Kind.METHOD, j, c);
                else if (nt == EQ || nt == SEMI || nt == COMMA) m = new Sym(toks.get(j).text(), Kind.FIELD, j, c);
            } else if (j >= 2 && toks.get(j - 1).type() == COMMA && (next(j) == EQ || next(j) == SEMI || next(j) == COMMA)) {
                // `int a, b;` in a class body: the second field
                m = new Sym(toks.get(j).text(), Kind.FIELD, j, c);
            }
            if (m == null) continue;
            m.isStatic = staticBefore(j - 1);
            if (m.kind == Kind.FIELD) {
                int typeTok = isTypeAt(j - 1) ? j - 1 : j;
                m.startTok = m.isStatic ? typeTok - 1 : typeTok;
                int end = declaratorEnd(j);
                m.endTok = toks.get(end).type() == SEMI ? end : end - 1;
                m.detail = isTypeAt(j - 1) ? toks.get(j - 1).text() : null;
            }
            declAt.put(j, m);
            c.children.add(m);
            all.add(m);
            if (m.kind != Kind.CONSTRUCTOR) {
                c.members.put(m.name, m);
                body.names.put(m.name, m);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void declare(Sym s, Scope scope, int activeFrom) {
        s.activeFrom = activeFrom;
        if (s.kind == Kind.CLASS) classes.putIfAbsent(s.name, s);
        scope.names.put(s.name, s);
        all.add(s);
        if (s.owner == null && s.kind != Kind.PARAMETER && (scopes.size() == 1 || scope == scopes.peekLast())) {
            if (inTop.add(s)) top.add(s);
        } else if (s.owner != null && s.kind != Kind.PARAMETER && s.owner.kind != Kind.CLASS && s.kind != Kind.VARIABLE
                && !s.owner.children.contains(s)) {
            s.owner.children.add(s);   // a function or class declared in a function: in its outline
        }
    }

    private void use(int tok, Sym s) {
        if (s == null || refOf[tok] == s) return;
        refOf[tok] = s;
        uses.computeIfAbsent(s, k -> new ArrayList<>()).add(tok);
    }

    private Sym lookup(String name, int at) {
        for (Scope s : scopes) {
            Sym v = s.names.get(name);
            if (v != null && (v.activeFrom < 0 || at > v.activeFrom)) return v;
            if (s.cls != null) {
                Sym m = member(s.cls, name);
                if (m != null) return m;
            }
        }
        return null;
    }

    /** A member of a class of this file, or of the classes it extends. */
    private Sym member(Sym cls, String name) {
        for (int guard = 0; cls != null && guard < 32; guard++) {
            Sym m = cls.members.get(name);
            if (m != null) return m;
            cls = cls.parentClass == null ? null : classNamed(cls.parentClass);
        }
        return null;
    }

    private Sym classNamed(String name) {
        return classes.get(name);
    }

    private Sym enclosingClass() {
        for (Scope s : scopes) if (s.cls != null) return s.cls;
        return null;
    }

    private Sym currentFn() {
        for (Scope s : scopes) if (s.fn != null) return s.fn;
        return null;
    }

    private Sym owner() {
        Scope s = scopes.peek();
        if (s != null && s.cls != null) return s.cls;
        return currentFn();
    }

    /** Is the innermost bracket open at the walk's position the `{` of a table literal? */
    private boolean enclosingTable(int i) {
        Integer k = open.peek();
        return k != null && toks.get(k).type() == LBRACE && tableBrace[k];
    }

    private boolean inTable(int i) {
        return enclosingTable(i);
    }

    private boolean isClassBrace(int i) {
        for (int k = i - 1; k >= 0 && k >= i - 8; k--) {
            TokenType ty = toks.get(k).type();
            if (ty == TokenType.CLASS) return true;
            if (ty != IDENT && ty != EXTENDS && ty != DOT) return false;
        }
        return false;
    }

    private TokenType next(int i) {
        return i + 1 < toks.size() ? toks.get(i + 1).type() : EOF;
    }

    private boolean isTypeAt(int k) {
        if (k < 0) return false;
        Token t = toks.get(k);
        if (TYPE_KEYWORDS.contains(t.type())) return true;
        if (t.type() != IDENT) return false;
        if (k > 0 && toks.get(k - 1).type() == DOT) return false;
        return !t.text().isEmpty() && Character.isUpperCase(t.text().charAt(0));
    }

    private boolean staticBefore(int typeTok) {
        return typeTok > 0 && toks.get(typeTok - 1).type() == STATIC;
    }

    /** The names of a parameter list `(T a, b, U c)`: the last name of each part. */
    private List<Integer> paramNames(int open, int close) {
        List<Integer> out = new ArrayList<>();
        int depth = 0, last = -1;
        for (int k = open + 1; k < close; k++) {
            TokenType ty = toks.get(k).type();
            if (ty == LPAREN || ty == LBRACKET || ty == LBRACE) depth++;
            else if (ty == RPAREN || ty == RBRACKET || ty == RBRACE) depth--;
            else if (depth == 0 && ty == IDENT) last = k;
            else if (depth == 0 && ty == COMMA) { if (last >= 0) out.add(last); last = -1; }
        }
        if (last >= 0) out.add(last);
        for (int p : out) declAt.putIfAbsent(p, null);
        return out;
    }

    /** The end of a declarator starting at `name`: the `,` or `;` after it, or the bracket that closes around it. */
    private int declaratorEnd(int name) {
        int depth = 0;
        for (int k = name + 1; k < toks.size(); k++) {
            TokenType ty = toks.get(k).type();
            if (ty == LPAREN || ty == LBRACKET || ty == LBRACE) depth++;
            else if (ty == RPAREN || ty == RBRACKET || ty == RBRACE) { if (depth-- == 0) return k; }
            else if ((ty == COMMA || ty == SEMI) && depth == 0) return k;
            else if (ty == EOF) return k;
        }
        return toks.size() - 1;
    }

    /** `for (dyn v : xs)`: the `)` of the header - v is visible after it. */
    private int forEnd(int name) {
        for (int k = name - 1; k >= 0; k--) {
            if (toks.get(k).type() == LPAREN && k > 0 && toks.get(k - 1).type() == FOR) return match[k];
        }
        return name;
    }

    /** The end of the statement starting at `k`: a block's `}` or the `;` that ends it. */
    private int statementEnd(int k) {
        if (k >= toks.size()) return toks.size() - 1;
        if (toks.get(k).type() == LBRACE && match[k] > 0) return match[k];
        int depth = 0;
        for (int j = k; j < toks.size(); j++) {
            TokenType ty = toks.get(j).type();
            if (ty == LPAREN || ty == LBRACKET || ty == LBRACE) depth++;
            else if (ty == RPAREN || ty == RBRACKET || ty == RBRACE) { if (depth-- == 0) return j; }
            else if (ty == SEMI && depth == 0) return j;
            else if (ty == EOF) return j;
        }
        return toks.size() - 1;
    }

    /** A lambda's body after its `->` at `arrow`: a block, or an expression up to the `,` `)` `;` `}` that ends it. */
    private int lambdaEnd(int arrow) {
        int k = arrow + 1;
        if (k < toks.size() && toks.get(k).type() == LBRACE && match[k] > 0) return match[k];
        int depth = 0;
        for (int j = k; j < toks.size(); j++) {
            TokenType ty = toks.get(j).type();
            if (ty == LPAREN || ty == LBRACKET || ty == LBRACE) depth++;
            else if (ty == RPAREN || ty == RBRACKET || ty == RBRACE) { if (depth-- == 0) return j - 1; }
            else if ((ty == COMMA || ty == SEMI) && depth == 0) return j - 1;
            else if (ty == EOF) return j;
        }
        return toks.size() - 1;
    }

    private String text(int open, int close) {
        StringBuilder sb = new StringBuilder("(");
        for (int k = open + 1; k < close; k++) {
            Token t = toks.get(k);
            if (t.type() == COMMA) sb.append(", ");
            else {
                if (sb.length() > 1 && sb.charAt(sb.length() - 1) != ' ' && sb.charAt(sb.length() - 1) != '(') sb.append(' ');
                sb.append(t.type() == TokenType.STRING ? "\"" + t.text() + "\"" : t.text());
            }
        }
        return sb.append(')').toString();
    }

    private int[] matching() {
        int[] m = new int[toks.size()];
        java.util.Arrays.fill(m, -1);
        Deque<Integer> st = new ArrayDeque<>();
        for (int i = 0; i < toks.size(); i++) {
            TokenType ty = toks.get(i).type();
            if (ty == LPAREN || ty == LBRACKET || ty == LBRACE) st.push(i);
            else if ((ty == RPAREN || ty == RBRACKET || ty == RBRACE) && !st.isEmpty()) {
                int o = st.pop();
                m[o] = i;
                m[i] = o;
            }
        }
        return m;
    }
}
