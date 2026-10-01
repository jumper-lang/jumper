package me.padej.jumper.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON for the language server: objects are {@code Map<String, Object>} (insertion order), arrays
 * {@code List<Object>}, numbers {@code Long} when integral else {@code Double}, plus String, Boolean and
 * null. Small on purpose - the language has no dependencies, and LSP messages need nothing more.
 */
public final class Json {
    private Json() {}

    public static Object parse(String s) {
        Json.Reader r = new Json.Reader(s);
        r.ws();
        Object v = r.value();
        r.ws();
        if (r.i != s.length()) throw r.fail("trailing characters");
        return v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) string(sb, s);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) sb.append(v);
        else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else string(sb, String.valueOf(v));
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** Builds an object: {@code Json.obj("a", 1, "b", "x")}. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) { this.s = s; }

        IllegalArgumentException fail(String what) {
            return new IllegalArgumentException("JSON: " + what + " at " + i);
        }

        void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        Object value() {
            if (i >= s.length()) throw fail("unexpected end");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) throw fail("bad literal");
            i += word.length();
            return v;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw fail("expected a key");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw fail("expected ':'");
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw fail("unexpected end");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw fail("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw fail("unexpected end");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw fail("expected ',' or ']'");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw fail("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw fail("unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw fail("bad \\u escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw fail("bad escape");
                }
            }
        }

        Object number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            boolean integral = true;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { integral = false; i++; }
                else break;
            }
            String t = s.substring(start, i);
            if (t.isEmpty() || t.equals("-")) throw fail("unexpected character");
            try {
                return integral ? (Object) Long.parseLong(t) : (Object) Double.parseDouble(t);
            } catch (NumberFormatException ex) {
                throw fail("bad number " + t);
            }
        }
    }
}
