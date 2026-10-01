package me.padej.jumper.lexer;

import me.padej.jumper.parser.ParseError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static me.padej.jumper.lexer.TokenType.*;

/** Hand-written lexer: one pass, no regular expressions. */
public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("dyn", DYN), Map.entry("int", KW_INT), Map.entry("long", KW_LONG),
            Map.entry("double", KW_DOUBLE), Map.entry("boolean", KW_BOOLEAN), Map.entry("String", KW_STRING),
            Map.entry("void", VOID), Map.entry("class", CLASS),
            Map.entry("new", NEW), Map.entry("return", RETURN), Map.entry("if", IF), Map.entry("else", ELSE),
            Map.entry("while", WHILE), Map.entry("for", FOR), Map.entry("do", DO), Map.entry("break", BREAK),
            Map.entry("continue", CONTINUE), Map.entry("true", TRUE), Map.entry("false", FALSE),
            Map.entry("null", NULL), Map.entry("import", IMPORT), Map.entry("this", THIS),
            Map.entry("super", SUPER), Map.entry("extends", EXTENDS), Map.entry("static", STATIC), Map.entry("try", TRY),
            Map.entry("switch", SWITCH), Map.entry("case", CASE), Map.entry("default", DEFAULT),
            Map.entry("catch", CATCH), Map.entry("finally", FINALLY), Map.entry("throw", THROW));

    private final String src;
    private int pos = 0, line = 1, col = 1;
    private final List<Token> tokens = new ArrayList<>();

    public Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String src) {
        return new Lexer(src).run();
    }

    /**
     * For tools: every lexical error goes into {@code errors} and lexing goes on. A bad literal becomes a
     * placeholder token of its kind (so the parser sees `x = <number>;` and adds no errors of its own), a
     * broken string ends at its closing quote or at the end of the line, a stray character is skipped.
     */
    public static List<Token> tokenizeTolerant(String src, List<ParseError> errors) {
        Lexer lx = new Lexer(src);
        lx.errors = errors;
        return lx.run();
    }

    /** Non-null: tolerant mode. */
    private List<ParseError> errors;

    public List<Token> run() {
        while (true) {
            if (errors == null) skipWhitespaceAndComments();
            else {
                try { skipWhitespaceAndComments(); } catch (ParseError e) { errors.add(e); }   // an unclosed comment: at EOF now
            }
            if (pos >= src.length()) {
                emit(new Token(EOF, "", null, line, col));
                return tokens;
            }
            int startLine = line, startCol = col;
            char c = peek();
            if (errors == null) { token(c, startLine, startCol); continue; }
            try {
                token(c, startLine, startCol);
            } catch (ParseError e) {
                errors.add(e);
                recover(c, startLine, startCol);
            }
        }
    }

    private void token(char c, int startLine, int startCol) {
        if (Character.isLetter(c) || c == '_') {
            String word = readWhile(ch -> Character.isLetterOrDigit(ch) || ch == '_');
            TokenType kw = KEYWORDS.get(word);
            emit(new Token(kw != null ? kw : IDENT, word, null, startLine, startCol));
        } else if (Character.isDigit(c)) {
            readNumber(startLine, startCol);
        } else if (c == '"' || c == '\'') {
            readString(c, startLine, startCol);
        } else {
            readOperator(startLine, startCol);
        }
    }

    /** After an error in a token that started with c (tolerant mode): skip its rest and stand in for it. */
    private void recover(char c, int l, int cl) {
        if (Character.isDigit(c)) {
            // the number is read up to its end already; the parser gets a zero of the literal's kind
            emit(new Token(INT, "0", 0, l, cl));
        } else if (c == '"' || c == '\'') {
            // "Newline in string literal" has consumed the line break already: the string ended there
            boolean ended = pos > 0 && src.charAt(pos - 1) == '\n';
            while (!ended && pos < src.length() && peek() != '\n') {
                char ch = advance();
                if (ch == c) break;
                if (ch == '\\' && pos < src.length() && peek() != '\n') advance();
            }
            emit(new Token(STRING, "", "", l, cl));
        }
        // an unexpected character: it is consumed, nothing stands in for it
    }

    /** Adds a token that ends here: the lexer has just consumed its last character. */
    private void emit(Token t) {
        tokens.add(new Token(t.type(), t.text(), t.value(), t.line(), t.col(), t.line() == line ? col : t.col() + t.text().length()));
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char peek(int off) {
        return pos + off < src.length() ? src.charAt(pos + off) : '\0';
    }

    private char advance() {
        char c = src.charAt(pos++);
        if (c == '\n') { line++; col = 1; } else col++;
        return c;
    }

    private interface CharPred { boolean test(char c); }

    private String readWhile(CharPred p) {
        int start = pos;
        while (pos < src.length() && p.test(peek())) advance();
        return src.substring(start, pos);
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') advance();
            else if (c == '/' && peek(1) == '/') { while (pos < src.length() && peek() != '\n') advance(); }
            else if (c == '/' && peek(1) == '*') {
                int l = line, cl = col;
                advance(); advance();
                while (pos < src.length() && !(peek() == '*' && peek(1) == '/')) advance();
                // not silently the rest of the file: an unclosed comment is almost always a typo
                if (pos >= src.length()) throw new ParseError("Unterminated comment", l, cl);
                advance(); advance();
            } else break;
        }
    }

    private void readNumber(int l, int c) {
        int start = pos;
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance(); advance();
            String hex = readWhile(ch -> Character.digit(ch, 16) >= 0 || ch == '_');
            boolean isLong = peek() == 'L' || peek() == 'l';
            if (isLong) advance();
            String text = src.substring(start, pos);
            hex = hex.replace("_", "");
            if (hex.isEmpty()) throw new ParseError("Hex literal without digits: " + text, l, c);
            String digits = hex.replaceFirst("^0+", "");
            // as in Java: up to 8 hex digits fit an int (0xFFFFFFFF is -1), up to 16 a long
            if (digits.length() > (isLong ? 16 : 8))
                throw new ParseError(isLong ? "Hex literal too large for long: " + text
                        : "Hex literal too large for int (use L suffix): " + text, l, c);
            emit(isLong ? new Token(LONG, text, Long.parseUnsignedLong(hex, 16), l, c)
                              : new Token(INT, text, (int) Long.parseLong(hex, 16), l, c));
            return;
        }
        readWhile(ch -> Character.isDigit(ch) || ch == '_');
        boolean isDouble = false;
        if (peek() == '.' && Character.isDigit(peek(1))) {
            isDouble = true;
            advance();
            readWhile(ch -> Character.isDigit(ch) || ch == '_');
        }
        if (peek() == 'e' || peek() == 'E') {
            isDouble = true;
            advance();
            if (peek() == '+' || peek() == '-') advance();
            if (readWhile(Character::isDigit).isEmpty())
                throw new ParseError("Exponent without digits: " + src.substring(start, pos), l, c);
        }
        String text = src.substring(start, pos);
        String clean = text.replace("_", "");
        if (isDouble) {
            if (peek() == 'd' || peek() == 'D') advance();
            emit(new Token(DOUBLE, text, Double.parseDouble(clean), l, c));
        } else if (peek() == 'L' || peek() == 'l') {
            advance();
            try {
                emit(new Token(LONG, text + "L", Long.parseLong(clean), l, c));
            } catch (NumberFormatException e) {
                // 9223372036854775808L is allowed only as -9223372036854775808L: the parser checks the unary minus
                if (clean.equals("9223372036854775808")) emit(new Token(LONG, text + "L", new java.math.BigInteger(clean), l, c));
                else throw new ParseError("Long literal too large: " + text + "L", l, c);
            }
        } else if (peek() == 'd' || peek() == 'D') {
            advance();
            emit(new Token(DOUBLE, text + "d", Double.parseDouble(clean), l, c));
        } else {
            try {
                emit(new Token(INT, text, Integer.parseInt(clean), l, c));
            } catch (NumberFormatException e) {
                // 2147483648 is allowed only as -2147483648: the parser checks the unary minus
                if (clean.equals("2147483648")) emit(new Token(INT, text, 2147483648L, l, c));
                else throw new ParseError("Integer literal too large (use L suffix): " + text, l, c);
            }
        }
    }

    private void readString(char quote, int l, int c) {
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new ParseError("Unterminated string", l, c);
            char ch = advance();
            if (ch == quote) break;
            if (ch == '\n') throw new ParseError("Newline in string literal", l, c);
            if (ch == '\\') {
                int el = line, ec = col - 1;   // the backslash: where the error points
                if (pos >= src.length()) throw new ParseError("Unterminated string", l, c);
                char e = advance();
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    case 'u' -> {
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = pos < src.length() ? Character.digit(peek(), 16) : -1;
                            if (d < 0) throw new ParseError("Bad escape \\u: expected 4 hex digits", el, ec);
                            advance();
                            code = code * 16 + d;
                        }
                        sb.append((char) code);
                    }
                    default -> throw new ParseError("Bad escape \\" + e, el, ec);
                }
            } else sb.append(ch);
        }
        String s = sb.toString();
        emit(new Token(STRING, s, s, l, c));
    }

    private void readOperator(int l, int c) {
        char ch = advance();
        TokenType t;
        switch (ch) {
            case '(' -> t = LPAREN;
            case ')' -> t = RPAREN;
            case '{' -> t = LBRACE;
            case '}' -> t = RBRACE;
            case '[' -> t = LBRACKET;
            case ']' -> t = RBRACKET;
            case ',' -> t = COMMA;
            case '.' -> t = DOT;
            case ';' -> t = SEMI;
            case ':' -> t = COLON;
            case '?' -> t = QUESTION;
            case '^' -> t = CARET;
            case '+' -> t = match('+') ? PLUSPLUS : match('=') ? PLUSEQ : PLUS;
            case '-' -> t = match('-') ? MINUSMINUS : match('=') ? MINUSEQ : match('>') ? ARROW : MINUS;
            case '*' -> t = match('=') ? STAREQ : STAR;
            case '/' -> t = match('=') ? SLASHEQ : SLASH;
            case '%' -> t = match('=') ? PERCENTEQ : PERCENT;
            case '=' -> t = match('=') ? EQEQ : EQ;
            case '!' -> t = match('=') ? NE : NOT;
            case '<' -> t = match('=') ? LE : match('<') ? SHL : LT;
            case '>' -> t = match('=') ? GE : match('>') ? (match('>') ? USHR : SHR) : GT;
            case '&' -> t = match('&') ? ANDAND : AMP;
            case '|' -> t = match('|') ? OROR : PIPE;
            default -> throw new ParseError("Unexpected character '" + ch + "'", l, c);
        }
        emit(new Token(t, src.substring(pos - tokenLen(t), pos), null, l, c));
    }

    private boolean match(char expected) {
        if (peek() == expected) { advance(); return true; }
        return false;
    }

    private static int tokenLen(TokenType t) {
        return switch (t) {
            case PLUSPLUS, MINUSMINUS, PLUSEQ, MINUSEQ, STAREQ, SLASHEQ, PERCENTEQ, EQEQ, NE, LE, GE,
                 ANDAND, OROR, SHL, SHR, ARROW -> 2;
            case USHR -> 3;
            default -> 1;
        };
    }
}
