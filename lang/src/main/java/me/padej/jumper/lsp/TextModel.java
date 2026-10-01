package me.padej.jumper.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an editor request needs from the text of a buffer, read straight from the text: it is asked about
 * while being typed, so it may not parse. The word at the cursor, the receiver chain before it
 * (`server.world().na|` -> server, world(), then "na"), imports, and declarations of names.
 */
final class TextModel {
    final String text;
    private final int[] lineStarts;

    TextModel(String text) {
        this.text = text;
        List<Integer> s = new ArrayList<>();
        s.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') s.add(i + 1);
        lineStarts = s.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Offset of a 0-based LSP position (UTF-16 units - Java chars). */
    int offset(int line, int character) {
        if (line >= lineStarts.length) return text.length();
        int start = lineStarts[Math.max(0, line)];
        int end = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : text.length();
        return Math.min(start + Math.max(0, character), end);
    }

    /** 0-based [line, character] of an offset. */
    int[] position(int offset) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        return new int[] {lo, offset - lineStarts[lo]};
    }

    static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** [start, end) of the identifier at or just before the offset, or null. */
    int[] wordAt(int offset) {
        int s = Math.min(offset, text.length()), e = s;
        while (s > 0 && word(text.charAt(s - 1))) s--;
        while (e < text.length() && word(text.charAt(e))) e++;
        if (s == e || Character.isDigit(text.charAt(s))) return null;
        return new int[] {s, e};
    }

    /** One link of a receiver chain: a name, called or not. */
    record Link(String name, boolean call) {}

    /**
     * The receiver chain that ends just before `end` when a dot precedes it: for `server.world().na` with end at
     * "na" - [server, world()]; empty when there is no dot. Arguments are skipped (their parentheses balanced);
     * an index (`a[0]`) or anything else unreadable ends the chain as unknown (null).
     */
    /** Where the chain the last {@link #receiverBefore} returned begins (its root, or `new`). */
    int chainStart = -1;

    /** An expression that is a chain as a whole (`server.world()`, `new X(1).y`, `w`), or null. */
    static List<Link> chainOf(String expr) {
        TextModel t = new TextModel(expr.strip() + ".");
        List<Link> links = t.receiverBefore(t.text.length());
        return links != null && !links.isEmpty() && t.chainStart == 0 ? links : null;
    }

    List<Link> receiverBefore(int end) {
        int i = skipSpaceBack(end);
        if (i <= 0 || text.charAt(i - 1) != '.') return List.of();
        List<Link> links = new ArrayList<>();
        i--;
        while (true) {
            i = skipSpaceBack(i);
            boolean call = false;
            if (i > 0 && text.charAt(i - 1) == ')') {
                int depth = 0, j = i - 1;
                for (; j >= 0; j--) {
                    char c = text.charAt(j);
                    if (c == ')') depth++;
                    else if (c == '(' && --depth == 0) break;
                }
                if (j < 0) return null;
                i = skipSpaceBack(j);
                call = true;
            }
            int e = i;
            while (i > 0 && word(text.charAt(i - 1))) i--;
            if (i == e) return null;   // `a[0].`, `"s".`, `(x).`: not followed statically
            links.add(0, new Link(text.substring(i, e), call));
            int k = skipSpaceBack(i);
            if (k > 0 && text.charAt(k - 1) == '.') { i = k - 1; continue; }
            // `new X(...)` as the root: an instance of X
            chainStart = i;
            if (call && k >= 3 && text.startsWith("new", k - 3) && (k - 3 == 0 || !word(text.charAt(k - 4)))
                    && k < i) {
                links.set(0, new Link("new " + links.get(0).name(), true));
                chainStart = k - 3;
            }
            return links;
        }
    }

    private int skipSpaceBack(int i) {
        while (i > 0 && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) i--;
        return i;
    }

    /** The number of arguments of the call whose name ends at `end` (`f(a, g(b))` - 2), or -1 if it is not a call. */
    int argCount(int end) {
        int i = end;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i >= text.length() || text.charAt(i) != '(') return -1;
        int depth = 0, n = 0;
        boolean any = false;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {   // a string: skip it, commas inside do not count
                for (i++; i < text.length() && text.charAt(i) != c && text.charAt(i) != '\n'; i++) if (text.charAt(i) == '\\') i++;
                any = true;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') { if (--depth == 0) return any ? n + 1 : 0; }
            else if (c == ',' && depth == 1) n++;
            else if (!Character.isWhitespace(c)) any = true;
        }
        return -1;
    }

    boolean calledAt(int end) {
        int i = end;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i < text.length() && text.charAt(i) == '(';
    }

    // ------------------------------------------------------------------ imports and declarations

    private static final Pattern JAVA_IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\s*;");
    private static final Pattern MODULE_IMPORT = Pattern.compile("(?m)^\\s*import\\s+\"([^\"]+)\"\\s*;");

    /** Simple name -> binary name of the Java imports. */
    Map<String, String> javaImports() {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = JAVA_IMPORT.matcher(text);
        while (m.find()) {
            String fq = m.group(1);
            out.put(fq.substring(fq.lastIndexOf('.') + 1), fq);
        }
        return out;
    }

    List<String> moduleImports() {
        List<String> out = new ArrayList<>();
        Matcher m = MODULE_IMPORT.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * The expressions assigned to `name`, in text order: `dyn w = <expr>;`, `w = <expr>;` (not `a.w =`, not
     * `==`). Each up to the `;`, the end of the line or the bracket that closes around it.
     */
    List<String> assignedValues(String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\s*=(?!=)").matcher(text);
        while (m.find()) {
            int i = m.end(), depth = 0, start = i;
            for (; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\'') {
                    for (i++; i < text.length() && text.charAt(i) != c && text.charAt(i) != '\n'; i++) if (text.charAt(i) == '\\') i++;
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') { if (depth-- == 0) break; }
                else if ((c == ';' || c == ',') && depth == 0 || c == '\n' && depth == 0) break;
            }
            String v = text.substring(start, Math.min(i, text.length())).strip();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static final String TYPES = "dyn|int|long|double|boolean|String|void|[A-Z][\\w]*(?:\\.[A-Z][\\w]*)*(?:\\[\\])?";

    /** Where `name` is declared: a variable, parameter, function, class, for-each variable. [offset of the name, type or null]. */
    List<Decl> declarations(String name) {
        List<Decl> out = new ArrayList<>();
        Pattern p = Pattern.compile("(?:\\b(" + TYPES + ")\\s+|\\bclass\\s+)(" + Pattern.quote(name) + ")\\b(?=\\s*(?:[=;,:)({]|extends\\b))");
        Matcher m = p.matcher(text);
        while (m.find()) out.add(new Decl(m.start(2), m.group(1)));
        return out;
    }

    /** Every declared name in the text (for completion). */
    Map<String, String> declaredNames() {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern p = Pattern.compile("(?:\\b(" + TYPES + ")\\s+|\\b(class)\\s+)([A-Za-z_]\\w*)\\b(?=\\s*(?:[=;,:)({]|extends\\b))");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String type = m.group(1) != null ? m.group(1) : "class";
            if (type.equals("return") || type.equals("new")) continue;
            out.putIfAbsent(m.group(3), type);
        }
        return out;
    }

    /** `name = new X(` anywhere: the class a `dyn` variable was made from. */
    String newTypeOf(String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*new\\s+([\\w.]+)\\s*\\(").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    record Decl(int offset, String type) {}

    /** A `dyn` (or untyped) parameter `index` of `count` of a top-level function `function`. */
    record Param(String function, int index, int count) {}

    private static final Pattern FUNCTION = Pattern.compile("\\b(?:" + TYPES + ")\\s+([A-Za-z_]\\w*)\\s*\\(");

    /**
     * Where `name` is a `dyn` or untyped parameter of a function declared at the top level of the file
     * (`void onJoin(dyn p) {`): the function, the parameter's place and how many there are - a hook's
     * parameter gets the type its host passes (Features.rootType).
     */
    List<Param> dynParameters(String name) {
        List<Param> out = new ArrayList<>();
        Matcher m = FUNCTION.matcher(text);
        while (m.find()) {
            int open = m.end() - 1;
            if (depthAt(m.start()) != 0) continue;   // a method of a class, a function inside a function
            List<String> params = new ArrayList<>();
            int close = splitParams(open, params);
            if (close < 0) continue;
            int i = close + 1;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length() || text.charAt(i) != '{') continue;   // a call, not a declaration
            for (int k = 0; k < params.size(); k++) {
                String[] w = params.get(k).strip().split("\\s+");
                if (w.length == 0 || !w[w.length - 1].equals(name)) continue;
                if (w.length == 1 || w.length == 2 && w[0].equals("dyn")) out.add(new Param(m.group(1), k, params.size()));
            }
        }
        return out;
    }

    /** The parameters of the list opening at `open` (text of each); the offset of its `)`, or -1. */
    private int splitParams(int open, List<String> out) {
        int depth = 0, start = open + 1;
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth-- == 0) {
                    String last = text.substring(start, i);
                    if (!last.isBlank() || !out.isEmpty()) out.add(last);
                    return i;
                }
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        return -1;
    }

    /** How many braces are open at `offset` (strings and comments skipped). */
    int depthAt(int offset) {
        int depth = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < text.length() && text.charAt(i) != c && text.charAt(i) != '\n'; i++) if (text.charAt(i) == '\\') i++;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int e = text.indexOf("*/", i + 2);
                i = e < 0 ? text.length() : e + 1;
            } else if (c == '{') depth++;
            else if (c == '}' && depth > 0) depth--;
        }
        return depth;
    }

    /** The whole line at an offset, trimmed. */
    String lineAt(int offset) {
        int l = position(offset)[0];
        int s = lineStarts[l], e = l + 1 < lineStarts.length ? lineStarts[l + 1] - 1 : text.length();
        return text.substring(s, e).strip();
    }

    /** The text of the line up to the offset. */
    String lineBefore(int offset) {
        return text.substring(lineStarts[position(offset)[0]], offset);
    }
}
