package me.padej.jumper.interp;

import me.padej.jumper.runtime.JArray;
import me.padej.jumper.runtime.JTable;
import me.padej.jumper.runtime.Ops;
import me.padej.jumper.runtime.Shape;

/**
 * The fast path of {@link Config}: reads a config in one pass over its characters and evaluates it as it
 * goes - no tokens, no syntax tree, no Interpreter, no JIT classes. Covers what configs are made of:
 * <pre>
 * int a = 1;
 * boolean isDev = false;
 * int permLevel = isDev ? 10 : 0;
 * dyn obj = {"ye", 123, isDev};
 * if (isDev) { permLevel = 99; } else { a = 2; }
 * dyn server = { host: "0.0.0.0", port: 25565 + a };
 * </pre>
 * or {@code return <value>;}. Declarations (dyn, int, long, double, boolean, String) at the top level,
 * assignments, if/else, expressions with ?:, ||, &&, ==, !=, <, <=, >, >=, +, -, *, /, %, unary - and !,
 * parentheses, references to variables declared above, table and array literals ({@code {k: v}},
 * {@code [a, b]}, {@code {a, b}}).
 *
 * <p>The values are exactly the full path's. Literals are read by the rules of {@code Lexer}, operators
 * are the runtime's own ({@code Ops.add}, {@code Ops.eq}, ...: what the interpreter calls, and what the
 * typed nodes agree with for these operand kinds), a typed variable takes only what the parser would
 * let into it (a widening int -> long -> double included), a table literal gets the same keys in the
 * same order (its slots stay plain references - no typed slots, no generated layout class - which the
 * language cannot tell apart). Whatever is outside this - a function call, a member,
 * an index, a loop, a bit operator, a condition that is not a boolean, a declaration in a block, any
 * error at all - returns {@link #FALLBACK}: the file goes the full way, which gives the proper result or
 * error message. Nothing is ever partially applied: a config has no side effects.
 */
final class ConfigReader {
    static final Object FALLBACK = new Object();

    /**
     * The text, copied into a char array owned by this thread's reader: plain array reads instead of
     * String.charAt (a bounds check and a Latin-1/UTF-16 test on every character), two '\0' after the end.
     */
    private char[] cs = new char[1024];
    /** The text itself, for the searches the JDK vectorizes (String.indexOf: SSE/AVX/NEON intrinsics). */
    private String src;
    private int n;
    private int p;
    /** False while skipping the branch not taken: parse, do not evaluate. */
    private boolean exec = true;

    // top-level variables in declaration order
    private String[] names = new String[16];
    private Object[] vals = new Object[16];
    private byte[] kinds = new byte[16];
    private int nvars;

    private static final byte DYN = 0, INT = 1, LONG = 2, DOUBLE = 3, BOOL = 4, STR = 5;

    /**
     * Static type of the expression just read - the parser's (Expr.type), which it checks at every
     * declaration and assignment and for some operators, in every branch, taken or not. Kept here by
     * the same rules, so a config the parser rejects is never accepted by the fast path.
     */
    private byte ty;

    private static boolean num(byte k) { return k == INT || k == LONG || k == DOUBLE; }

    private static byte arith(byte a, byte b) {
        if (!num(a) || !num(b)) return DYN;
        if (a == DOUBLE || b == DOUBLE) return DOUBLE;
        if (a == LONG || b == LONG) return LONG;
        return INT;
    }

    /** Parser.checkAssignable. */
    private static void checkAssignable(byte target, byte vt) {
        if (vt == DYN || target == DYN) return;
        boolean ok = switch (target) {
            case INT -> vt == INT;
            case LONG -> vt == INT || vt == LONG;
            case DOUBLE -> num(vt);
            case BOOL -> vt == BOOL;
            case STR -> vt == STR;
            default -> true;
        };
        if (!ok) throw BAIL;   // "Cannot assign ..." - the full path says it
    }

    /** Parser.makeBinary for + - * / %: the result's static type, or a bail where it throws a ParseError. */
    private static byte arithType(char op, byte l, byte r) {
        if (num(l) && num(r)) return arith(l, r);
        boolean doubleDominant = (l == DOUBLE && r == DYN) || (l == DYN && r == DOUBLE);
        if (doubleDominant && op != '+') return DOUBLE;
        if (op == '+' && (l == STR || r == STR)) return STR;
        if (l == BOOL || r == BOOL) throw BAIL;
        if (l == STR && r == STR) throw BAIL;
        return DYN;
    }

    /** A numeric value as the static type k holds it (Ternary of an int and a double is a double). */
    private static Object widen(byte k, Object v) {
        if (k == LONG && v instanceof Integer i) return (long) i;
        if (k == DOUBLE && v instanceof Integer i) return (double) i;
        if (k == DOUBLE && v instanceof Long l) return (double) l;
        return v;
    }

    /** Scratch stack for table keys/values and array items: one buffer for all the literals of a file. */
    private Object[] stack = new Object[64];
    private int top;

    private ConfigReader() {}

    /** One reader per thread, reused: its buffers are the only memory a read needs besides the result. */
    // a subclass, not withInitial(ConfigReader::new): a lambda is an invokedynamic bootstrap (LambdaMetafactory,
    // a hidden class) - a millisecond or more of the first config read in a process
    private static final ThreadLocal<ConfigReader> READER = new ThreadLocal<>() {
        @Override
        protected ConfigReader initialValue() {
            return new ConfigReader();
        }
    };

    static Object read(String src) {
        ConfigReader r = READER.get();
        r.reset(src);
        try {
            return r.file();
        } catch (Bail b) {
            return FALLBACK;
        } catch (RuntimeException e) {   // an Ops error, a number format...: the full path reports it properly
            return FALLBACK;
        } finally {
            r.release();
        }
    }

    private void reset(String src) {
        this.src = src;
        n = src.length();
        if (cs.length < n + 2) cs = new char[Math.max(n + 2, cs.length * 2)];
        src.getChars(0, n, cs, 0);
        cs[n] = 0;
        cs[n + 1] = 0;
        p = 0;
        exec = true;
        nvars = 0;
        top = 0;
        ty = DYN;
        prev = null;
    }

    /** Drop every reference into the result (the reader outlives it), and a buffer grown by a huge file. */
    private void release() {
        java.util.Arrays.fill(vals, 0, nvars, null);
        java.util.Arrays.fill(names, 0, nvars, null);
        java.util.Arrays.fill(stack, 0, used, null);
        used = 0;
        nvars = 0;
        top = 0;
        prev = null;
        src = null;
        if (cs.length > 1 << 16) cs = new char[1024];
    }

    /** Not for the fast path. Preallocated: thrown on the fallback path only, never seen outside. */
    private static final class Bail extends RuntimeException {
        Bail() { super(null, null, false, false); }
    }

    private static final Bail BAIL = new Bail();

    // ------------------------------------------------------------------ statements

    private Object file() {
        ws();
        while (p < n) {
            if (word("return")) {
                Object v = expr();
                ws();
                expect(';');
                ws();
                if (p != n || v == null) throw BAIL;   // code after return; `return null` = the variables
                return v;
            }
            statement(true);
            ws();
        }
        if (nvars > Shape.MAX_KEYS) {   // a dictionary, as the full path's put() makes it
            JTable t = JTable.plain(nvars);
            for (int i = 0; i < nvars; i++) t.put(names[i], vals[i]);
            return t;
        }
        int mark = top;
        for (int i = 0; i < nvars; i++) { push(names[i]); push(vals[i]); }
        return makeTable(mark);
    }

    private void statement(boolean topLevel) {
        if (word("if")) {
            ws();
            expect('(');
            Object c = expr();
            ws();
            expect(')');
            boolean saved = exec;
            boolean cond = saved && bool(c);
            exec = saved && cond;
            body();
            exec = saved;
            ws();
            if (word("else")) {
                exec = saved && !cond;
                body();
                exec = saved;
            }
            return;
        }
        byte kind = typeWord();
        if (kind >= 0) {
            if (!topLevel) throw BAIL;   // a block's own variable: not a top-level one
            String name = ident();
            if (name == null || isKeyword(name) || indexOf(name) >= 0) throw BAIL;
            ws();
            expect('=');
            if (peek() == '=') throw BAIL;
            Object v = expr();
            checkAssignable(kind, ty);
            v = coerce(kind, v);
            ws();
            expect(';');
            declare(name, kind, v);
            return;
        }
        int at = p;
        String name = ident();
        if (name != null && !isKeyword(name)) {
            ws();
            if (peek() == '=' && peek(1) != '=') {
                int i = indexOf(name);
                if (i < 0) throw BAIL;
                p++;
                Object v = expr();
                checkAssignable(kinds[i], ty);
                v = coerce(kinds[i], v);
                ws();
                expect(';');
                if (exec) vals[i] = v;
                return;
            }
        }
        p = at;
        throw BAIL;   // an expression statement, a loop, a call...
    }

    private void body() {
        ws();
        if (peek() == '{') {
            p++;
            ws();
            while (peek() != '}') {
                if (p >= n) throw BAIL;
                statement(false);
                ws();
            }
            p++;
        } else {
            statement(false);
        }
    }

    /** dyn/int/long/double/boolean/String followed by a name: consumed, its kind; -1 (nothing consumed) otherwise. */
    private byte typeWord() {
        int at = p;
        byte k;
        if (word("dyn")) k = DYN;
        else if (word("int")) k = INT;
        else if (word("long")) k = LONG;
        else if (word("double")) k = DOUBLE;
        else if (word("boolean")) k = BOOL;
        else if (word("String")) k = STR;
        else return -1;
        char c = peek();
        if (!(Character.isLetter(c) || c == '_')) { p = at; return -1; }
        return k;
    }

    /** What a variable of that type holds after `T x = v`: only what the parser and VarType.coerce would let in. */
    private Object coerce(byte kind, Object v) {
        if (!exec) return null;
        switch (kind) {
            case DYN: return v;
            case INT: if (v instanceof Integer) return v; break;
            case LONG:
                if (v instanceof Long) return v;
                if (v instanceof Integer i) return (long) i;
                break;
            case DOUBLE:
                if (v instanceof Double) return v;
                if (v instanceof Integer i) return (double) i;
                if (v instanceof Long l) return (double) l;
                break;
            case BOOL: if (v instanceof Boolean) return v; break;
            case STR: if (v instanceof String) return v; break;
            default: break;
        }
        throw BAIL;
    }

    private void declare(String name, byte kind, Object v) {
        if (nvars == names.length) {
            names = java.util.Arrays.copyOf(names, nvars * 2);
            vals = java.util.Arrays.copyOf(vals, nvars * 2);
            kinds = java.util.Arrays.copyOf(kinds, nvars * 2);
        }
        names[nvars] = name;
        vals[nvars] = v;
        kinds[nvars] = kind;
        nvars++;
    }

    private int indexOf(String name) {
        for (int i = nvars - 1; i >= 0; i--) if (names[i].equals(name)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ expressions (Parser's precedence)

    private Object expr() {
        ws();
        // most values are a lone literal: skip the seven precedence levels for them
        int at = p;
        Object lit = literal();
        if (lit != NOT_LITERAL) {
            ws();
            char e = peek();
            if (e == ',' || e == '}' || e == ']' || e == ';' || e == ')' || e == ':') return lit;
            p = at;   // part of an expression: read it again the full way
        }
        Object c = or();
        ws();
        if (peek() != '?') return c;
        p++;
        boolean saved = exec;
        boolean cond = saved && bool(c);
        exec = saved && cond;
        Object a = expr();
        byte ta = ty;
        ws();
        expect(':');
        exec = saved && !cond;
        Object b = expr();
        byte tb = ty;
        exec = saved;
        ty = ta == tb ? ta : arith(ta, tb);
        if (!saved) return null;
        return ta == tb ? (cond ? a : b) : widen(ty, cond ? a : b);
    }

    /** Jumper's || and && return an operand (Exprs.Logical), not necessarily a boolean. */
    private Object or() {
        Object a = and();
        while (true) {
            ws();
            if (!(peek() == '|' && peek(1) == '|')) return a;
            p += 2;
            byte ta = ty;
            boolean saved = exec;
            boolean done = saved && Ops.truthy(a);
            exec = saved && !done;
            Object b = and();
            exec = saved;
            if (saved && !done) a = b;
            ty = ta == BOOL && ty == BOOL ? BOOL : DYN;
        }
    }

    private Object and() {
        Object a = equality();
        while (true) {
            ws();
            if (!(peek() == '&' && peek(1) == '&')) {
                if (peek() == '&' || peek() == '|' || peek() == '^') throw BAIL;   // bit operators
                return a;
            }
            p += 2;
            byte ta = ty;
            boolean saved = exec;
            boolean go = saved && Ops.truthy(a);
            exec = go;
            Object b = equality();
            exec = saved;
            if (go) a = b;
            ty = ta == BOOL && ty == BOOL ? BOOL : DYN;
        }
    }

    private Object equality() {
        Object a = comparison();
        while (true) {
            ws();
            char c = peek();
            if ((c == '=' || c == '!') && peek(1) == '=') {
                p += 2;
                Object b = comparison();
                if (exec) a = Ops.eq(a, b) == (c == '=');
                ty = BOOL;
            } else return a;
        }
    }

    private Object comparison() {
        Object a = additive();
        while (true) {
            ws();
            char c = peek();
            if (c != '<' && c != '>') return a;
            if (peek(1) == c) throw BAIL;   // << >>
            boolean eq = peek(1) == '=';
            p += eq ? 2 : 1;
            Object b = additive();
            if (exec) a = c == '<' ? (eq ? Ops.le(a, b) : Ops.lt(a, b)) : (eq ? Ops.ge(a, b) : Ops.gt(a, b));
            ty = BOOL;
        }
    }

    private Object additive() {
        Object a = multiplicative();
        while (true) {
            ws();
            char c = peek();
            if (c != '+' && c != '-') return a;
            if (peek(1) == '=' || peek(1) == c) throw BAIL;   // += -= ++ --
            p++;
            byte ta = ty;
            Object b = multiplicative();
            byte rt = arithType(c, ta, ty);
            if (exec) a = c == '+' ? Ops.add(a, b) : Ops.sub(a, b);
            ty = rt;
        }
    }

    private Object multiplicative() {
        Object a = unary();
        while (true) {
            ws();
            char c = peek();
            if (c != '*' && c != '/' && c != '%') return a;
            if (peek(1) == '=') throw BAIL;
            p++;
            byte ta = ty;
            Object b = unary();
            byte rt = arithType(c, ta, ty);
            if (exec) a = c == '*' ? Ops.mul(a, b) : c == '/' ? Ops.div(a, b) : Ops.mod(a, b);
            ty = rt;
        }
    }

    private Object unary() {
        ws();
        char c = peek();
        if (c == '-' && peek(1) != '-' && peek(1) != '=') {
            p++;
            ws();
            if (Character.isDigit(peek())) {
                Object v = negate(number());   // -2147483648 is a literal of its own
                ty = kindOf(v);
                postfixCheck(v);
                return exec ? v : null;
            }
            Object v = unary();
            if (!num(ty)) ty = DYN;   // IntNeg/LongNeg/DoubleNeg keep the type, Neg is dyn
            return exec ? Ops.neg(v) : null;
        }
        if (c == '!' && peek(1) != '=') {
            p++;
            Object v = unary();
            ty = BOOL;
            return exec ? (Object) !bool(v) : null;
        }
        return postfixCheck(primary());
    }

    /** A member, index or call after a value: not ours. */
    private Object postfixCheck(Object v) {
        ws();
        char c = peek();
        if (c == '.' || c == '[' || c == '(') throw BAIL;
        return v;
    }

    private static byte kindOf(Object v) {
        if (v instanceof Integer) return INT;
        if (v instanceof Long) return LONG;
        if (v instanceof Double) return DOUBLE;
        return DYN;
    }

    private Object primary() {
        ws();
        if (p >= n) throw BAIL;
        char c = cs[p];
        switch (c) {
            case '{': { Object v = braces(); ty = DYN; return v; }
            case '[': { Object v = array(']'); ty = DYN; return v; }
            case '"': case '\'': { Object v = string(c); ty = STR; return v; }
            case '(': {
                p++;
                Object v = expr();
                ws();
                expect(')');
                return v;
            }
            default:
                if (Character.isDigit(c)) {
                    Object n = number();
                    if (n instanceof Long l && l == 2147483648L && !longSuffix) throw BAIL;   // valid only negated
                    ty = kindOf(n);
                    return n;
                }
                String w = ident();
                if (w == null) throw BAIL;
                switch (w) {
                    case "true": ty = BOOL; return Boolean.TRUE;
                    case "false": ty = BOOL; return Boolean.FALSE;
                    case "null": ty = DYN; return null;
                    default: break;
                }
                if (isKeyword(w)) throw BAIL;
                int i = indexOf(w);
                if (i < 0) throw BAIL;   // a builtin, a global, an undefined name - the full path knows
                ws();
                if (peek() == '=' && peek(1) != '=') throw BAIL;   // an assignment inside an expression
                ty = kinds[i];
                return vals[i];
        }
    }

    private static final Object NOT_LITERAL = new Object();

    /** A literal value at p (with ty set), or NOT_LITERAL with nothing consumed. */
    private Object literal() {
        char c = peek();
        if (c == '{') { Object v = braces(); ty = DYN; return v; }
        if (c == '[') { Object v = array(']'); ty = DYN; return v; }
        if (c == '"' || c == '\'') { Object v = string(c); ty = STR; return v; }
        if (c >= '0' && c <= '9') {
            Object n = number();
            if (n instanceof Long l && l == 2147483648L && !longSuffix) throw BAIL;
            ty = kindOf(n);
            return n;
        }
        if (c == 't' && word("true")) { ty = BOOL; return Boolean.TRUE; }
        if (c == 'f' && word("false")) { ty = BOOL; return Boolean.FALSE; }
        if (c == 'n' && word("null")) { ty = DYN; return null; }
        return NOT_LITERAL;
    }

    private boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        throw BAIL;   // a condition that is not a boolean: the parser's static check or evalBool decides
    }

    // ------------------------------------------------------------------ literals

    /** `{`: a table `{k: v, ...}`, or - when the first element is not `key:` - an array `{a, b}`. */
    private Object braces() {
        int at = p;
        p++;
        ws();
        if (peek() == '}') { p++; return exec ? JTable.literalPlain(Shape.ROOT, new Object[4]) : null; }
        char c = peek();
        int save = p;
        boolean table = false;
        // look past the first key-like thing without making a string of it
        if (c == '"' || c == '\'') {
            p++;
            while (p < n && cs[p] != c && cs[p] != '\\' && cs[p] != '\n') p++;
            if (p < n && cs[p] == '\\') { p = save; string(c); }   // an escape: read it properly
            else if (p >= n || cs[p] != c) throw BAIL;
            else p++;
            ws();
            table = peek() == ':';
        } else if (c < 128 ? IDENT[c] && !(c >= '0' && c <= '9') : Character.isLetter(c)) {
            while (p < n && (cs[p] < 128 ? IDENT[cs[p]] : Character.isLetterOrDigit(cs[p]))) p++;
            ws();
            table = peek() == ':';
        } else if (c == '[') throw BAIL;   // a computed key or an array element: the full path
        p = save;
        if (!table) { p = at; return array('}'); }
        return table();
    }

    private Object table() {
        int mark = top;
        while (true) {
            ws();
            if (peek() == '}') break;
            Object key;
            char c = peek();
            if (c == '"' || c == '\'') key = string(c);
            else {
                key = ident();
                if (key == null) throw BAIL;   // numeric or computed keys
            }
            ws();
            expect(':');
            Object v = expr();
            push(key);
            push(v);
            ws();
            if (peek() == ',') { p++; continue; }
            if (peek() == '}') break;
            throw BAIL;
        }
        p++;   // }
        if (!exec) { top = mark; return null; }
        return makeTable(mark);
    }

    /** The table of the key/value pairs on the stack from mark up: TableLit's shape, or put() when a value is null. */
    private JTable makeTable(int mark) {
        int n = (top - mark) / 2;
        if (n > Shape.MAX_KEYS) throw BAIL;
        boolean anyNull = false;
        for (int i = 0; i < n; i++) if (stack[mark + 2 * i + 1] == null) { anyNull = true; break; }
        JTable t;
        if (!anyNull) {
            Object[] arr = new Object[Math.max(4, n)];
            for (int i = 0; i < n; i++) arr[i] = stack[mark + 2 * i + 1];
            Shape shape = cachedShape(mark, n);
            if (shape == null) {
                // the literal's shape: constant keys in order (the same cached transitions as Exprs.TableLit)
                shape = Shape.ROOT;
                for (int i = 0; i < n; i++) {
                    Object k = stack[mark + 2 * i];
                    if (shape.indexOf(k) >= 0) throw BAIL;   // duplicate key: the full path builds it the generic way
                    shape = shape.child(k);
                }
                rememberShape(mark, n, shape);
            }
            // Slots stay references: the untyped shape, no typeSlots pass and no long[] for primitives.
            // Invisible from the language (as with -Djmp.tableprims=0), and a config is read far more often
            // than any script reads the same table's number in a hot loop.
            t = JTable.plainTyped(shape, arr);
        } else {   // TableLit.build: a null value makes no slot
            Shape shape = Shape.ROOT;
            for (int i = 0; i < n; i++) {
                Object k = stack[mark + 2 * i];
                if (shape.indexOf(k) >= 0) throw BAIL;
                shape = shape.child(k);
            }
            t = JTable.plain(n);
            for (int i = 0; i < n; i++) t.put(stack[mark + 2 * i], stack[mark + 2 * i + 1]);
        }
        top = mark;
        return t;
    }

    private Object array(char close) {
        p++;   // [ or {
        int mark = top;
        while (true) {
            ws();
            if (peek() == close) break;
            push(expr());
            ws();
            if (peek() == ',') { p++; continue; }   // a trailing comma is fine, as in Parser.arrayLiteral
            if (peek() == close) break;
            throw BAIL;
        }
        p++;
        int n = top - mark;
        if (!exec) { top = mark; return null; }
        Object[] items = new Object[Math.max(n, 4)];
        System.arraycopy(stack, mark, items, 0, n);
        top = mark;
        return new JArray(items, n);
    }

    private void push(Object v) {
        if (top == stack.length) stack = java.util.Arrays.copyOf(stack, top * 2);
        stack[top++] = v;
        if (top > used) used = top;
    }

    /** How far the stack has been written since the last release: cleared once, not per literal. */
    private int used;

    private boolean longSuffix;

    private Object number() {
        longSuffix = false;
        int start = p;
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            p += 2;
            int hs = p;
            while (p < n && (Character.digit(cs[p], 16) >= 0 || cs[p] == '_')) p++;
            String hex = new String(cs, hs, p - hs).replace("_", "");
            if (hex.isEmpty()) throw BAIL;
            Object v;
            if (peek() == 'L' || peek() == 'l') { p++; longSuffix = true; v = Long.parseUnsignedLong(hex, 16); }
            else v = (int) Long.parseLong(hex, 16);
            endOfNumber();
            return v;
        }
        while (p < n && (Character.isDigit(cs[p]) || cs[p] == '_')) p++;
        boolean dbl = false;
        if (peek() == '.' && Character.isDigit(peek(1))) {
            dbl = true;
            p++;
            while (p < n && (Character.isDigit(cs[p]) || cs[p] == '_')) p++;
        }
        if (peek() == 'e' || peek() == 'E') {
            dbl = true;
            p++;
            if (peek() == '+' || peek() == '-') p++;
            while (p < n && Character.isDigit(cs[p])) p++;
        }
        // the common case - plain digits, maybe one '.' - without the substring and the library parse
        Object quick = dbl ? quickDouble(start, p) : null;
        if (quick == null && !dbl && peek() != 'L' && peek() != 'l' && peek() != 'd' && peek() != 'D') quick = quickInt(start, p);
        if (quick != null) {
            if (dbl && (peek() == 'd' || peek() == 'D')) p++;
            endOfNumber();
            return quick;
        }
        String clean = new String(cs, start, p - start).replace("_", "");
        Object v;
        if (dbl) {
            if (peek() == 'd' || peek() == 'D') p++;
            v = Double.parseDouble(clean);
        } else if (peek() == 'L' || peek() == 'l') {
            p++;
            longSuffix = true;
            v = Long.parseLong(clean);
        } else if (peek() == 'd' || peek() == 'D') {
            p++;
            v = Double.parseDouble(clean);
        } else {
            long l = Long.parseLong(clean);
            if (l == 2147483648L) v = l;                 // valid only under a minus (checked by the caller)
            else if (l > Integer.MAX_VALUE) throw BAIL;  // "too large" - the full path reports it
            else v = (int) l;
        }
        endOfNumber();
        return v;
    }

    /** Digits only, at most 9 of them: certainly an int. Null otherwise (underscores, longer). */
    private Integer quickInt(int from, int to) {
        if (to - from > 9) return null;
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = cs[i];
            if (c < '0' || c > '9') return null;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static final double[] POW10 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15};

    /**
     * digits '.' digits with at most 15 significant digits: the digits as an exact long divided by an exact
     * power of ten - one correctly rounded IEEE division, so the same double Double.parseDouble gives
     * (the classic fast path of decimal conversion). Null for anything else (exponent, underscores, longer).
     */
    private Double quickDouble(int from, int to) {
        long m = 0;
        int digits = 0, frac = -1;
        for (int i = from; i < to; i++) {
            char c = cs[i];
            if (c == '.') { if (frac >= 0) return null; frac = 0; continue; }
            if (c < '0' || c > '9') return null;
            m = m * 10 + (c - '0');
            if (m != 0 || digits > 0) digits++;
            if (frac >= 0) frac++;
        }
        if (frac < 0 || digits > 15 || frac > 15) return null;
        return m / POW10[frac];
    }

    /** `5x` or `1.2.3` is not a literal the way the lexer would split it into one: let the full path decide. */
    private void endOfNumber() {
        char c = peek();
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.') throw BAIL;
    }

    private Object negate(Object n) {
        if (n instanceof Integer i) return -i;
        if (n instanceof Long l) {
            if (l == 2147483648L && !longSuffix) return Integer.MIN_VALUE;
            return -l;
        }
        return -(Double) n;
    }

    private String string(char quote) {
        int start = ++p;
        // the string that stood here on the previous read (a reload of the same file): if it is there
        // again, followed by the closing quote, it is this string - one vectorized comparison, no scan
        Seen seen = inPlace(AT[start & (AT.length - 1)], start, quote);
        if (seen == null && prev != null) seen = inPlace(prev.next, start, quote);   // an edit above moved it
        if (seen != null) {
            p = start + seen.chars.length + 1;
            follow(seen, start);
            return exec ? seen.s : null;
        }
        char ch;
        int h = 0;
        int stop = start + LONG_STRING;
        while (true) {
            ch = cs[p];
            if (ch == quote || ch == '\\' || ch == '\n' || ch == 0 && p >= n) break;
            if (p == stop) return longString(start, quote);
            h = 31 * h + ch;
            p++;
        }
        if (ch == quote) {
            p++;
            return exec ? at(start, p - 1, h) : null;
        }
        if (ch != '\\') throw BAIL;   // a line break or the end of the text inside a string
        return escaped(start, quote);
    }

    /** The rest of a string from its first backslash at p: unescaped the lexer's way. */
    private String escaped(int start, char quote) {
        char ch;
        StringBuilder sb = new StringBuilder().append(cs, start, p - start);
        while (true) {
            if (p >= n) throw BAIL;
            ch = cs[p++];
            if (ch == quote) break;
            if (ch == '\n') throw BAIL;
            if (ch == '\\') {
                if (p >= n) throw BAIL;
                char e = cs[p++];
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    case 'u' -> {
                        if (p + 4 > n) throw BAIL;
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(cs[p++], 16);
                            if (d < 0) throw BAIL;
                            code = code * 16 + d;
                        }
                        sb.append((char) code);
                    }
                    default -> throw BAIL;
                }
            } else sb.append(ch);
        }
        return exec ? sb.toString() : null;
    }

    /** Past this many characters a string is not a key or a name: its end is found by a vectorized search. */
    private static final int LONG_STRING = 48;

    /**
     * A long string (a text, a message, a MOTD): its closing quote is
     * found with String.indexOf, which the JIT compiles to SIMD instructions (16-32 chars per step, on
     * x86 and ARM, with no flags - the same code the incubating Vector API would give), and the absence of
     * a backslash or a line break before it is checked the same way. The value is copied out of the text
     * as it is stored (a Latin-1 String copies bytes, no char[] -> byte[] compression); up to 256 chars it
     * is hashed and remembered as a short one is (a tight loop with no per-char branches).
     */
    private String longString(int start, char quote) {
        int q = src.indexOf(quote, p, n);
        if (q < 0) throw BAIL;
        int bs = src.indexOf('\\', p, q);
        if (src.indexOf('\n', p, bs < 0 ? q : bs) >= 0) throw BAIL;
        if (bs >= 0) {
            p = bs;
            return escaped(start, quote);
        }
        p = q + 1;
        if (!exec) return null;
        if (q - start <= 256) return at(start, q, hash(start, q));   // still remembered, like a short one
        return src.substring(start, q);
    }

    /**
     * Strings by their position in the text: what stood at this offset on the previous read - a reload of
     * the same file finds every key and value there, checked with one vectorized comparison and no hashing.
     * Anything else goes through the hash cache ({@link #cached}) and takes the place. Racy and harmless like
     * the other caches here: a slot holds a String and its chars, replaced together as one object.
     */
    private static final Seen[] AT = new Seen[8192];

    /**
     * plain: no quote, backslash or line break inside - it can be recognized in place by its end;
     * ident: identifier characters only - it can be recognized as a name.
     */
    private static final class Seen {
        final String s;
        final char[] chars;
        final boolean plain, ident;
        /**
         * The string that came right after this one the last time it was read. After an edit everything
         * below it has moved, but it is still the same strings in the same order: the next one is tried
         * here before anything is scanned or hashed. Written racily; a wrong guess is only a miss.
         */
        Seen next;

        private Seen(String s, char[] chars, boolean plain, boolean ident) {
            this.s = s;
            this.chars = chars;
            this.plain = plain;
            this.ident = ident;
        }

        static Seen of(String s) {
            char[] c = s.toCharArray();
            boolean plain = true, ident = c.length > 0;
            for (char x : c) {
                if (x == '"' || x == '\'' || x == '\\' || x == '\n' || x == 0) plain = false;
                if (!(x < 128 ? IDENT[x] : Character.isLetterOrDigit(x))) ident = false;
            }
            return new Seen(s, c, plain, ident && plain);
        }
    }

    /** The last string or name recognized in this read (the sequence {@link Seen#next} follows). */
    private Seen prev;

    /** c is the string starting at start, closed by quote: all its characters match and the quote follows. */
    private Seen inPlace(Seen c, int start, char quote) {
        if (c == null || !c.plain) return null;
        int end = start + c.chars.length;
        return end < n && cs[end] == quote && same(c.chars, start, end) ? c : null;
    }

    /** c is the name starting at start: all its characters match and no letter continues it. */
    private Seen nameInPlace(Seen c, int start) {
        if (c == null || !c.ident) return null;
        int end = start + c.chars.length;
        if (end > n) return null;
        char d = cs[end];
        return !(d < 128 ? IDENT[d] : Character.isLetterOrDigit(d)) && same(c.chars, start, end) ? c : null;
    }

    /** Record s as found at start: its place for the next read, and the order it came in. */
    private void follow(Seen s, int start) {
        int slot = start & (AT.length - 1);
        if (AT[slot] != s) AT[slot] = s;
        if (prev != null && prev.next != s) prev.next = s;
        prev = s;
    }

    private boolean same(char[] c, int from, int to) {
        int len = to - from;
        if (c.length != len) return false;
        if (len > 1 && c[len - 1] != cs[to - 1]) return false;   // "world_7" against "world_5": the end differs first
        if (len > 24) return java.util.Arrays.equals(cs, from, to, c, 0, len);
        for (int i = 0; i < len; i++) if (cs[from + i] != c[i]) return false;
        return true;
    }

    /** h: String.hashCode of cs[from, to), computed while scanning it. */
    private String at(int from, int to, int h) {
        if (to - from > 256) return new String(cs, from, to - from);   // long text: not worth remembering
        // not where it was, not next in order (a new string, another file): by content, then remember it
        Seen r = seen(from, to, h);
        follow(r, from);
        return r.s;
    }

    private int hash(int from, int to) {
        int h = 0;
        for (int i = from; i < to; i++) h = 31 * h + cs[i];
        return h;
    }

    // ------------------------------------------------------------------ shapes seen before

    /**
     * The shape of a table literal is a function of its keys - found through a chain of synchronized
     * transitions, one per key. A config repeats itself on every reload, and its keys come back as the same
     * String instances ({@link #at}), so the final shape is remembered: a hit checks each key (by identity
     * first). Racy like the string caches, and as harmless.
     */
    private static final Shape[] SHAPES = new Shape[512];

    private int shapeSlot(int mark, int n) {
        int h = n;
        for (int i = 0; i < n; i++) h = h * 31 + stack[mark + 2 * i].hashCode();
        return (h ^ (h >>> 9)) & (SHAPES.length - 1);
    }

    private Shape cachedShape(int mark, int n) {
        Shape c = SHAPES[shapeSlot(mark, n)];
        if (c == null || c.size() != n || c.sealed()) return null;
        for (int i = 0; i < n; i++) {
            Object k = c.keyAt(i), mine = stack[mark + 2 * i];
            if (k != mine && !k.equals(mine)) return null;
        }
        return c;
    }

    private void rememberShape(int mark, int n, Shape shape) {
        SHAPES[shapeSlot(mark, n)] = shape;
    }

    // ------------------------------------------------------------------ strings seen before

    /**
     * The same strings by content: what {@link #AT} falls back to when a string is not where it was - an
     * edit above it shifted the text, or another file is read. A config's strings survive an edit (a key
     * added or removed moves the others, it does not change them), so a String and its Seen are reused
     * rather than allocated again. Direct-mapped by hash; racy and harmless like the other caches here
     * (a Seen is immutable, a reference store is atomic), and string identity is not observable in the
     * language.
     */
    private static final Seen[] STRINGS = new Seen[4096];

    private Seen seen(int from, int to, int h) {
        int len = to - from;
        int slot = (h ^ (h >>> 12)) & (STRINGS.length - 1);
        Seen c = STRINGS[slot];
        if (c != null && c.s.hashCode() == h && same(c.chars, from, to)) return c;
        c = Seen.of(new String(cs, from, len));
        if (len <= 256) STRINGS[slot] = c;
        return c;
    }

    // ------------------------------------------------------------------ lexical helpers

    private char peek() {
        return p < n ? cs[p] : '\0';
    }

    private char peek(int off) {
        return p + off < n ? cs[p + off] : '\0';
    }

    private void expect(char c) {
        if (peek() != c) throw BAIL;
        p++;
    }

    /** Whitespace and comments, as Lexer.skipWhitespaceAndComments. */
    private void ws() {
        while (p < n) {
            char c = cs[p];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') p++;
            else if (c == '/' && peek(1) == '/') {
                // comments - a documented config is mostly comments - end where a vectorized search says
                int e = src.indexOf('\n', p + 2, n);
                p = e < 0 ? n : e;
            } else if (c == '/' && peek(1) == '*') {
                int e = src.indexOf("*/", p + 2, n);
                if (e < 0) throw BAIL;   // never closed - the full path reports it
                p = e + 2;
            } else break;
        }
    }

    /** An identifier or reserved word at p (not consumed if absent). */
    private String ident() {
        if (p >= n) return null;
        char c = cs[p];
        if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c > 127 && Character.isLetter(c))) return null;
        int start = p;
        Seen seen = nameInPlace(AT[start & (AT.length - 1)], start);
        if (seen == null && prev != null) seen = nameInPlace(prev.next, start);
        if (seen != null) {
            p = start + seen.chars.length;
            follow(seen, start);
            return seen.s;
        }
        int h = 0;
        while (true) {
            char d = cs[p];
            if (d < 128 ? !IDENT[d] : !Character.isLetterOrDigit(d)) break;
            h = 31 * h + d;
            p++;
        }
        return at(start, p, h);
    }

    /** ASCII characters that continue an identifier (letters, digits, '_' - as Lexer's isLetterOrDigit). */
    private static final boolean[] IDENT = new boolean[128];
    static {
        for (char c = 'a'; c <= 'z'; c++) IDENT[c] = true;
        for (char c = 'A'; c <= 'Z'; c++) IDENT[c] = true;
        for (char c = '0'; c <= '9'; c++) IDENT[c] = true;
        IDENT['_'] = true;
    }

    /** The whole word w at p, then whitespace; nothing consumed otherwise. */
    private boolean word(String w) {
        int e = p + w.length();
        if (e > n) return false;
        for (int i = 0; i < w.length(); i++) if (cs[p + i] != w.charAt(i)) return false;
        if (e < n && (Character.isLetterOrDigit(cs[e]) || cs[e] == '_')) return false;
        p = e;
        ws();
        return true;
    }

    private static boolean isKeyword(String w) {
        return switch (w) {
            case "dyn", "int", "long", "double", "boolean", "String", "void", "class", "new", "return",
                 "if", "else", "while", "for", "do", "break", "continue", "true", "false", "null", "import", "this",
                 "super", "extends", "static", "try", "catch", "finally", "throw", "switch", "case", "default" -> true;
            default -> false;
        };
    }
}
