package me.padej.jumper.lsp;

import me.padej.jumper.lexer.Lexer;
import me.padej.jumper.lexer.Token;
import me.padej.jumper.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static me.padej.jumper.lexer.TokenType.*;

/**
 * Semantic tokens (LSP `textDocument/semanticTokens/full`): what each word of a Jumper text is, for an
 * editor to color it the way Java is colored - plus `dyn` and the keys of table literals `{ a: 1 }`.
 *
 * <p>From the tokens alone, never by running anything, and tolerant: a text that does not parse is
 * still colored. What it tells apart:
 * <ul>
 *   <li>keywords (with `dyn`), comments, strings, numbers;</li>
 *   <li>classes - script classes, Java classes (an interface as `interface`), `String`; packages of an import;</li>
 *   <li>declarations: functions, methods, fields, variables, parameters (also of lambdas) - and the
 *       uses of a parameter inside its function;</li>
 *   <li>members: `a.method()`, `a.property`; keys of a table literal `{ key: v }` as properties;</li>
 *   <li>what comes from outside the file: the host's globals and the built-in functions (`defaultLibrary`).</li>
 * </ul>
 */
final class SemanticTokens {
    static final List<String> TYPES = List.of("namespace", "type", "class", "interface", "enum", "parameter", "variable",
            "property", "function", "method", "keyword", "comment", "string", "number", "operator");
    static final List<String> MODIFIERS = List.of("declaration", "static", "readonly", "defaultLibrary");

    static final int NAMESPACE = 0, CLASS = 2, INTERFACE = 3, PARAMETER = 5, VARIABLE = 6, PROPERTY = 7,
            FUNCTION = 8, METHOD = 9, KEYWORD = 10, COMMENT = 11, STRING = 12, NUMBER = 13;
    static final int DECLARATION = 1, STATIC = 2, READONLY = 4, DEFAULT_LIBRARY = 8;

    private static final Set<TokenType> TYPE_KEYWORDS = Set.of(DYN, KW_INT, KW_LONG, KW_DOUBLE, KW_BOOLEAN, KW_STRING, VOID);
    private static final Set<TokenType> TABLE_OPENERS = Set.of(EQ, PLUSEQ, MINUSEQ, STAREQ, SLASHEQ, PERCENTEQ, LPAREN, COMMA,
            LBRACKET, COLON, RETURN, QUESTION, EQEQ, NE, ANDAND, OROR, NOT, PLUS, THROW);

    /** One colored piece of text: 0-based line and column, length in UTF-16 units. */
    record Piece(int line, int col, int length, int type, int modifiers) {}

    private final String text;
    private final List<Token> toks;
    private final Function<String, Class<?>> javaClass;
    private final Set<String> globals, builtins;
    private final Map<String, Class<?>> resolved = new HashMap<>();
    private final Symbols symbols;

    /**
     * @param javaClass a class the text names by a simple name (an import, java.lang), or null
     * @param globals   the host's globals
     * @param builtins  the built-in functions
     */
    SemanticTokens(String text, Function<String, Class<?>> javaClass, Set<String> globals, Set<String> builtins) {
        this.text = text;
        this.toks = Lexer.tokenizeTolerant(text, new ArrayList<>());
        this.symbols = new Symbols(text);   // the same tokens: indexes agree
        this.javaClass = javaClass;
        this.globals = globals;
        this.builtins = builtins;
    }

    /** The LSP encoding: 5 integers per piece, positions relative to the previous piece. */
    int[] encode() {
        List<Piece> ps = pieces();
        int[] out = new int[ps.size() * 5];
        int line = 0, col = 0, k = 0;
        for (Piece p : ps) {
            out[k++] = p.line - line;
            out[k++] = p.line == line ? p.col - col : p.col;
            out[k++] = p.length;
            out[k++] = p.type;
            out[k++] = p.modifiers;
            line = p.line;
            col = p.col;
        }
        return out;
    }

    List<Piece> pieces() {
        List<Piece> out = new ArrayList<>();
        int n = toks.size();
        int[] match = matching();
        boolean[] tableBrace = new boolean[n], classBrace = new boolean[n], paramParen = new boolean[n];
        Set<String> scriptClasses = new HashSet<>();
        for (int i = 0; i + 1 < n; i++) if (toks.get(i).type() == TokenType.CLASS && toks.get(i + 1).type() == IDENT) scriptClasses.add(toks.get(i + 1).text());

        // parameters: names -> the token range where a use means the parameter
        List<int[]> scopes = new ArrayList<>();
        List<Set<String>> scopeNames = new ArrayList<>();
        Deque<Integer> braces = new ArrayDeque<>();
        boolean inImport = false;

        for (int i = 0; i < n; i++) {
            Token t = toks.get(i);
            TokenType ty = t.type();
            if (ty == EOF) break;
            Token prev = i > 0 ? toks.get(i - 1) : null, next = i + 1 < n ? toks.get(i + 1) : null;
            TokenType pt = prev == null ? null : prev.type(), nt = next == null ? EOF : next.type();

            if (ty == LBRACE) {
                boolean parentTable = !braces.isEmpty() && tableBrace[braces.peek()];
                tableBrace[i] = pt == null ? false : TABLE_OPENERS.contains(pt) || parentTable && (pt == LBRACE || pt == COMMA || pt == COLON);
                if (pt == ARROW) tableBrace[i] = false;
                classBrace[i] = isClassBody(i);
                braces.push(i);
                continue;
            }
            if (ty == RBRACE) { if (!braces.isEmpty()) braces.pop(); continue; }
            if (ty == LPAREN && pt == IDENT && (isDeclaredName(i - 1)
                    || scriptClasses.contains(prev.text()) && i >= 2 && toks.get(i - 2).type() != NEW && toks.get(i - 2).type() != DOT))
                paramParen[i] = true;
            if (ty == LPAREN && match[i] > 0 && match[i] + 1 < n && toks.get(match[i] + 1).type() == ARROW) paramParen[i] = true;
            if (ty == LPAREN && paramParen[i]) {
                Set<String> names = paramNames(i, match[i]);
                int[] range = bodyRange(match[i], match);
                if (!names.isEmpty() && range != null) { scopes.add(range); scopeNames.add(names); }
            }
            if (ty == IDENT && nt == ARROW) {   // x -> ...
                int[] range = bodyRange(i, match);
                if (range != null) { scopes.add(range); scopeNames.add(Set.of(t.text())); }
            }

            if (ty == IMPORT) { inImport = true; out.add(piece(t, KEYWORD, 0)); continue; }
            if (inImport && ty == SEMI) { inImport = false; continue; }
            if (ty == TokenType.STRING) { out.add(piece(t, STRING, 0)); continue; }
            if (ty == INT || ty == LONG || ty == DOUBLE) { out.add(piece(t, NUMBER, 0)); continue; }
            if (ty == KW_STRING) { out.add(piece(t, CLASS, DEFAULT_LIBRARY)); continue; }
            if (ty != IDENT) {
                if (isKeyword(ty)) out.add(piece(t, KEYWORD, 0));
                continue;
            }

            String name = t.text();
            if (inImport) {   // import a.b.C;
                boolean last = nt == SEMI || nt == EOF;
                out.add(piece(t, last ? classKind(name) : NAMESPACE, 0));
                continue;
            }
            int inBrace = braces.isEmpty() ? -1 : braces.peek();
            boolean inClassBody = inBrace >= 0 && classBrace[inBrace];
            boolean isStatic = pt == TokenType.STATIC || i >= 2 && toks.get(i - 2).type() == TokenType.STATIC && isTypeAt(i - 1);

            // after a dot: a member
            if (pt == DOT) {
                if (nt == LPAREN) out.add(piece(t, METHOD, 0));
                else if (looksLikeClass(name) && !allCaps(name)) out.add(piece(t, classKind(name), 0));
                else out.add(piece(t, PROPERTY, 0));
                continue;
            }
            // `{ key: v }`: a key of a table literal
            if (nt == COLON && inBrace >= 0 && tableBrace[inBrace] && (pt == LBRACE || pt == COMMA)) {
                out.add(piece(t, PROPERTY, DECLARATION));
                continue;
            }
            // class C / extends C / new C
            if (pt == TokenType.CLASS) { out.add(piece(t, CLASS, DECLARATION)); continue; }
            if (pt == EXTENDS || pt == NEW) { out.add(piece(t, scriptClasses.contains(name) ? CLASS : classKind(name), 0)); continue; }
            // a declaration: `T name (`, `T name =`, `T name;` ...
            if (isTypeAt(i - 1) && !(pt == IDENT && i >= 2 && toks.get(i - 2).type() == DOT)) {
                if (nt == LPAREN) {
                    out.add(piece(t, inClassBody ? METHOD : FUNCTION, DECLARATION | (isStatic ? STATIC : 0)));
                    continue;
                }
                if (nt == EQ || nt == SEMI || nt == COMMA || nt == RPAREN || nt == COLON || nt == EOF) {
                    boolean param = inParamList(i, match, paramParen);
                    int kind = param ? PARAMETER : inClassBody ? PROPERTY : VARIABLE;
                    out.add(piece(t, kind, DECLARATION | (isStatic ? STATIC : 0)));
                    continue;
                }
            }
            // a constructor in its class: `C(...) {`
            if (inClassBody && nt == LPAREN && scriptClasses.contains(name) && pt != NEW) { out.add(piece(t, CLASS, DECLARATION)); continue; }
            // a lambda parameter: `x ->`, `(a, b) ->`
            if (nt == ARROW || (inParamList(i, match, paramParen) && (nt == COMMA || nt == RPAREN))) {
                out.add(piece(t, PARAMETER, DECLARATION));
                continue;
            }
            // a type: a class name (before a variable name or alone)
            if (scriptClasses.contains(name)) { out.add(piece(t, CLASS, 0)); continue; }
            if (looksLikeClass(name) && !allCaps(name) && (nt == IDENT || nt == DOT || resolve(name) != null)) {
                out.add(piece(t, classKind(name), 0));
                continue;
            }
            if (nt == LPAREN) {
                out.add(piece(t, FUNCTION, builtins.contains(name) && !declared(name) ? DEFAULT_LIBRARY : 0));
                continue;
            }
            // what the name is bound to in this file: a field used without `this.`, a parameter
            Symbols.Sym bound = symbols.symbolOf(i);
            if (bound != null && bound.kind == Symbols.Kind.FIELD) { out.add(piece(t, PROPERTY, bound.isStatic ? STATIC : 0)); continue; }
            if (bound != null && bound.kind == Symbols.Kind.PARAMETER) { out.add(piece(t, PARAMETER, 0)); continue; }
            if (inScope(i, name, scopes, scopeNames)) { out.add(piece(t, PARAMETER, 0)); continue; }
            if (globals.contains(name) && !declared(name)) { out.add(piece(t, VARIABLE, DEFAULT_LIBRARY | READONLY)); continue; }
            out.add(piece(t, VARIABLE, 0));
        }
        comments(out);
        out.sort((a, b) -> a.line != b.line ? Integer.compare(a.line, b.line) : Integer.compare(a.col, b.col));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Piece piece(Token t, int type, int mods) {
        return new Piece(t.line() - 1, t.col() - 1, Math.max(1, t.endCol() - t.col()), type, mods);
    }

    private static boolean isKeyword(TokenType ty) {
        return ty.ordinal() >= DYN.ordinal() && ty.ordinal() <= DEFAULT.ordinal();
    }

    /** Is the token at k a type: a type keyword, or a class name (`Point`, `Random`) that is not a member? */
    private boolean isTypeAt(int k) {
        if (k < 0) return false;
        Token t = toks.get(k);
        if (TYPE_KEYWORDS.contains(t.type())) return true;
        if (t.type() != IDENT) return false;
        if (k > 0 && toks.get(k - 1).type() == DOT) return false;
        return looksLikeClass(t.text());
    }

    /** `name` at k is declared here: `T name (` - a function, method or constructor. */
    private boolean isDeclaredName(int k) {
        return isTypeAt(k - 1) && !(k >= 2 && toks.get(k - 2).type() == DOT);
    }

    /** The class body `{` at i: `class C [extends X] {`. */
    private boolean isClassBody(int i) {
        for (int k = i - 1; k >= 0 && k >= i - 6; k--) {
            TokenType ty = toks.get(k).type();
            if (ty == TokenType.CLASS) return true;
            if (ty != IDENT && ty != EXTENDS && ty != DOT) return false;
        }
        return false;
    }

    /** Is the token at i directly inside a parameter list (a paren marked as one)? */
    private boolean inParamList(int i, int[] match, boolean[] paramParen) {
        int depth = 0;
        for (int k = i - 1; k >= 0; k--) {
            TokenType ty = toks.get(k).type();
            if (ty == RPAREN || ty == RBRACKET || ty == RBRACE) depth++;
            else if (ty == LPAREN || ty == LBRACKET || ty == LBRACE) {
                if (depth == 0) return ty == LPAREN && paramParen[k];
                depth--;
            } else if (ty == SEMI && depth == 0) return false;
        }
        return false;
    }

    /** The names a parameter list declares: `(int a, dyn b, c)` -> a, b, c. */
    private Set<String> paramNames(int open, int close) {
        Set<String> out = new HashSet<>();
        if (close < 0) return out;
        for (int k = open + 1; k < close; k++) {
            Token t = toks.get(k);
            TokenType nt = toks.get(k + 1).type();
            if (t.type() == IDENT && (nt == COMMA || nt == RPAREN) && k + 1 <= close) out.add(t.text());
        }
        return out;
    }

    /** Where a parameter is in scope: the `{ body }` after `)` (or `->`), or an expression body up to its end. */
    private int[] bodyRange(int from, int[] match) {
        int k = from + 1;
        if (k < toks.size() && toks.get(k).type() == ARROW) k++;
        if (k >= toks.size()) return null;
        if (toks.get(k).type() == LBRACE) return match[k] > 0 ? new int[] {k, match[k]} : new int[] {k, toks.size() - 1};
        if (toks.get(from + 1).type() != ARROW) return null;
        // an expression body: to the comma, `)`, `;` or `}` that ends it
        int depth = 0;
        for (int j = k; j < toks.size(); j++) {
            TokenType ty = toks.get(j).type();
            if (ty == LPAREN || ty == LBRACKET || ty == LBRACE) depth++;
            else if (ty == RPAREN || ty == RBRACKET || ty == RBRACE) { if (depth-- == 0) return new int[] {k, j}; }
            else if ((ty == COMMA || ty == SEMI) && depth == 0) return new int[] {k, j};
            else if (ty == EOF) return new int[] {k, j};
        }
        return new int[] {k, toks.size() - 1};
    }

    private static boolean inScope(int i, String name, List<int[]> scopes, List<Set<String>> names) {
        for (int s = scopes.size() - 1; s >= 0; s--) {
            int[] r = scopes.get(s);
            if (i >= r[0] && i < r[1] && names.get(s).contains(name)) return true;
        }
        return false;
    }

    /** For each bracket token its partner (-1 if none). */
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

    private Set<String> declaredNames;

    /** Is the name declared in this text (then it is not the builtin or the global of the same name)? */
    private boolean declared(String name) {
        if (declaredNames == null) {
            declaredNames = new HashSet<>();
            for (int i = 1; i + 1 < toks.size(); i++) {
                Token t = toks.get(i);
                if (t.type() == IDENT && isTypeAt(i - 1) && !(toks.get(i - 1).type() == IDENT && i >= 2 && toks.get(i - 2).type() == DOT))
                    declaredNames.add(t.text());
            }
        }
        return declaredNames.contains(name);
    }

    private Class<?> resolve(String name) {
        return resolved.computeIfAbsent(name, n -> {
            try { return javaClass.apply(n); } catch (RuntimeException | LinkageError e) { return null; }
        });
    }

    private int classKind(String name) {
        Class<?> c = resolve(name);
        return c != null && c.isInterface() ? INTERFACE : CLASS;
    }

    private static boolean looksLikeClass(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static boolean allCaps(String name) {
        for (int i = 0; i < name.length(); i++) if (Character.isLowerCase(name.charAt(i))) return false;
        return name.length() > 1;
    }

    /** `// ...` and block comments (the lexer skips them), one piece per line. */
    private void comments(List<Piece> out) {
        int i = 0, len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < len && text.charAt(j) != c && text.charAt(j) != '\n') { if (text.charAt(j) == '\\') j++; j++; }
                i = Math.min(j + 1, len);
            } else if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') {
                int j = text.indexOf('\n', i);
                if (j < 0) j = len;
                span(out, i, j);
                i = j;
            } else if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                int j = text.indexOf("*/", i + 2);
                j = j < 0 ? len : j + 2;
                span(out, i, j);
                i = j;
            } else i++;
        }
    }

    /** A comment from `from` to `to`, split at line ends (a CR before one is not part of it). */
    private void span(List<Piece> out, int from, int to) {
        int s = from;
        while (s < to) {
            int nl = text.indexOf('\n', s);
            int e = nl < 0 || nl >= to ? to : nl;
            int ee = e > s && text.charAt(e - 1) == '\r' ? e - 1 : e;
            if (ee > s) out.add(new Piece(lineOf(s), s - lineStart(s), ee - s, COMMENT, 0));
            s = e + 1;
        }
    }

    private int[] lineStarts;

    private int lineOf(int off) {
        if (lineStarts == null) {
            List<Integer> ls = new ArrayList<>();
            ls.add(0);
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') ls.add(i + 1);
            lineStarts = ls.stream().mapToInt(Integer::intValue).toArray();
        }
        int k = java.util.Arrays.binarySearch(lineStarts, off);
        return k >= 0 ? k : -k - 2;
    }

    private int lineStart(int off) {
        return lineStarts[lineOf(off)];
    }
}
