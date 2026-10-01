package me.padej.jumper.lsp;

import me.padej.jumper.workspace.Checker;
import me.padej.jumper.workspace.ClassInfo;
import me.padej.jumper.workspace.IndexCache;
import me.padej.jumper.workspace.JarIndex;
import me.padej.jumper.workspace.Members;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static me.padej.jumper.lsp.Json.obj;

/**
 * Hover, completion and go-to-definition over the text of a buffer and the environment of its file
 * (host globals, imports, the server's classes, the policy). Java types come from the classes themselves,
 * loaded without being initialized; what the policy closes is not offered and is marked in hovers.
 */
final class Features {
    private final Checker.Env env;
    private final TextModel tm;
    private final Map<String, String> imports;

    Features(Checker.Env env, String text) {
        this.env = env;
        this.tm = new TextModel(text);
        this.imports = tm.javaImports();
    }

    TextModel text() { return tm; }

    private me.padej.jumper.workspace.FileContext.Kind kind() {
        return env.context() == null ? me.padej.jumper.workspace.FileContext.Kind.OTHER : env.context().kind();
    }

    // ------------------------------------------------------------------ types of names and chains

    record Type(Class<?> cls, boolean statics) {}

    /** A class named in source: an import, java.lang, a qualified name. */
    Class<?> classNamed(String name) {
        if (name == null) return null;
        String fq = imports.get(name);
        if (fq != null) return Members.load(fq, env.loader());
        if (name.contains(".")) return Members.load(name, env.loader());
        if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) return Members.load("java.lang." + name, env.loader());
        return null;
    }

    /** The type of a name at the root of a chain, as far as the text tells: a host global, a class, a typed or `new`-made variable. */
    Type rootType(String name) {
        if (name.equals("Policy") && kind() == me.padej.jumper.workspace.FileContext.Kind.POLICY)
            return new Type(me.padej.jumper.security.Policy.class, true);   // what a .jma sees
        if (name.startsWith("new ")) {
            Class<?> c = classNamed(name.substring(4));
            return c == null ? null : new Type(c, false);
        }
        String g = env.context().globals().get(name);
        if (g != null) {
            Class<?> c = Members.load(g, env.loader());
            return c == null ? null : new Type(c, false);
        }
        Class<?> cls = classNamed(name);
        if (cls != null && Character.isUpperCase(name.charAt(0))) return new Type(cls, true);
        for (TextModel.Decl d : tm.declarations(name)) {
            if (d.type() == null || d.type().equals("dyn") || d.type().equals("void")) continue;
            Class<?> c = d.type().equals("String") ? String.class : classNamed(d.type());
            if (c != null) return new Type(c, false);
        }
        Class<?> made = classNamed(tm.newTypeOf(name));
        if (made != null) return new Type(made, false);
        // `void onJoin(dyn p)`: a hook's parameter is what the host passes (ScriptEvents.onJoin(ScriptPlayer))
        Type hooked = hookParameterType(name);
        if (hooked != null) return hooked;
        // `dyn w = server.world();` (or a later `w = ...`): the type of what is assigned
        if (inferring > 4) return null;   // `dyn a = b; dyn b = a;`
        inferring++;
        try {
            for (String value : tm.assignedValues(name)) {
                Type t = valueType(value);
                if (t != null) return t;
            }
        } finally {
            inferring--;
        }
        return null;
    }

    private int inferring;

    /** The type the host passes for `name`, a `dyn` parameter of a top-level function it calls (workspace.Hooks). */
    private Type hookParameterType(String name) {
        if (env.context() == null) return null;
        for (TextModel.Param p : tm.dynParameters(name)) {
            me.padej.jumper.workspace.Hooks.Hook h = me.padej.jumper.workspace.Hooks.find(env.context(), p.function(), env.loader());
            Method m = h == null ? null : h.method(p.count());
            if (m == null || m.getParameterCount() != p.count()) continue;
            Class<?> c = m.getParameterTypes()[p.index()];
            if (!c.isPrimitive() && !c.isArray()) return new Type(c, false);
        }
        return null;
    }

    /** The type of an expression's text, as far as it is a chain of known things: `server.world()`, `new X()`, `w`, `"s"`. */
    private Type valueType(String value) {
        if (value.equals("null")) return null;
        if (value.startsWith("\"") || value.startsWith("'")) return new Type(String.class, false);
        List<TextModel.Link> chain = TextModel.chainOf(value);
        if (chain == null) return null;
        return chainType(chain);
    }

    Type chainType(List<TextModel.Link> chain) {
        if (chain == null || chain.isEmpty()) return null;
        Type t = rootType(chain.get(0).name());
        for (int i = 1; i < chain.size() && t != null; i++) t = member(t, chain.get(i));
        return t;
    }

    private Type member(Type t, TextModel.Link link) {
        Class<?> r;
        if (link.call()) r = Members.returnType(Members.named(Members.methods(t.cls(), t.statics()), link.name()));
        else r = Members.propertyType(t.cls(), link.name());
        if (r == null || r.isPrimitive() || r.isArray()) return null;
        return new Type(r, false);
    }

    // ------------------------------------------------------------------ semantic tokens

    /** The whole text as LSP semantic tokens (see {@link SemanticTokens}). */
    int[] semanticTokens() {
        return new SemanticTokens(tm.text, this::classNamed, env.context().globals().keySet(),
                me.padej.jumper.runtime.Builtins.globals().keySet()).encode();
    }

    /** In a .jmc: a top-level name's line and its value (a config only computes, so it is safe to evaluate). */
    private String configValue(String name) {
        List<TextModel.Decl> ds = tm.declarations(name);
        if (ds.isEmpty()) return null;
        Object v;
        try {
            v = me.padej.jumper.interp.Config.parseFull(tm.text);
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
        if (!(v instanceof me.padej.jumper.runtime.JTable t)) return null;
        Object value = t.get(name);
        String shown = value instanceof String str ? "\"" + str + "\"" : me.padej.jumper.runtime.Ops.str(value);
        if (shown.length() > 200) shown = shown.substring(0, 200) + "...";
        return code(tm.lineAt(ds.get(0).offset())) + "\nValue: `" + shown + "`";
    }

    // ------------------------------------------------------------------ quick fixes

    private static final java.util.regex.Pattern UNDEFINED = java.util.regex.Pattern.compile("Undefined variable '([A-Z]\\w*)'");

    /**
     * Code actions for the diagnostics of a range: "Import a.b.C" for an unknown capitalized name that is a
     * class of the server's jars or of the JDK (and that the policy lets the script see).
     */
    List<Object> codeActions(String uri, List<Map<String, Object>> diagnostics, IndexCache cache) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> d : diagnostics) {
            java.util.regex.Matcher m = UNDEFINED.matcher(String.valueOf(d.get("message")));
            if (!m.find()) continue;
            for (String fq : classesNamed(m.group(1), cache)) {
                Map<String, Object> edit = obj("range", obj("start", obj("line", importLine(), "character", 0),
                        "end", obj("line", importLine(), "character", 0)), "newText", "import " + fq + ";\n");
                out.add(obj("title", "Import " + fq, "kind", "quickfix", "diagnostics", List.of(d), "isPreferred", out.isEmpty(),
                        "edit", obj("changes", obj(uri, List.of(edit)))));
            }
        }
        return out;
    }

    /** The public classes with this simple name, of the server's jars and of the JDK, open to the script. */
    List<String> classesNamed(String simple, IndexCache cache) {
        List<String> names = new ArrayList<>();
        for (Path jar : env.context().classpath()) {
            try {
                for (ClassInfo ci : cache.get(jar).classes().values())
                    if (ci.isPublic() && ci.name().indexOf('$') < 0 && ci.name().endsWith("." + simple)) names.add(ci.name());
            } catch (java.io.IOException e) {
                // an unreadable jar: not offered
            }
        }
        for (String n : JdkClasses.names()) if (n.endsWith("." + simple) && n.indexOf('$') < 0) names.add(n);
        List<String> out = new ArrayList<>();
        for (String n : new LinkedHashSet<>(names)) {
            Class<?> c = Members.load(n, env.loader());
            if (c == null || !java.lang.reflect.Modifier.isPublic(c.getModifiers())) continue;
            if (env.policy() != null && !env.policy().visible(c)) continue;
            out.add(n);
            if (out.size() >= 8) break;
        }
        out.sort(java.util.Comparator.comparing((String n) -> !n.startsWith("java.")).thenComparing(n -> n));
        return out;
    }

    /** Where a new import goes: after the last import at the top, else the first line that is not a comment. */
    private int importLine() {
        String[] lines = tm.text.split("\n", -1);
        int after = -1, firstCode = -1;
        boolean inComment = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].strip();
            if (inComment) { if (l.contains("*/")) inComment = false; continue; }
            if (l.startsWith("/*")) { if (!l.contains("*/")) inComment = true; continue; }
            if (l.isEmpty() || l.startsWith("//")) continue;
            if (l.startsWith("import ")) { after = i + 1; continue; }
            if (firstCode < 0) firstCode = i;
            break;
        }
        return after >= 0 ? after : Math.max(firstCode, 0);
    }

    // ------------------------------------------------------------------ formatting, folding, inlay hints

    /**
     * The text re-indented: each line by the brackets open before it (4 spaces a level, or `indent`), a line
     * that starts with a closing bracket one level less; trailing spaces cut. What is inside a line is not
     * touched, nor the lines of a block comment.
     */
    String formatted(String indent) {
        String[] lines = tm.text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inComment = false;
        for (int li = 0; li < lines.length; li++) {
            String raw = lines[li];
            boolean cr = raw.endsWith("\r");
            if (cr) raw = raw.substring(0, raw.length() - 1);
            String body = raw.strip();
            if (inComment) {
                out.append(raw.stripTrailing());
            } else if (body.isEmpty()) {
                // an empty line stays empty
            } else {
                int lead = 0;
                while (lead < body.length() && "}])".indexOf(body.charAt(lead)) >= 0) lead++;
                int level = Math.max(0, depth - Math.min(lead, depth));
                out.append(indent.repeat(level)).append(body);
            }
            // brackets of the line, outside strings and comments
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (inComment) {
                    if (c == '*' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') { inComment = false; i++; }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    for (i++; i < raw.length() && raw.charAt(i) != c; i++) if (raw.charAt(i) == '\\') i++;
                } else if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '/') break;
                else if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '*') { inComment = true; i++; }
                else if (c == '{' || c == '(' || c == '[') depth++;
                else if (c == '}' || c == ')' || c == ']') depth = Math.max(0, depth - 1);
            }
            if (cr) out.append('\r');
            if (li + 1 < lines.length) out.append('\n');
        }
        return out.toString();
    }

    /** Folding ranges: blocks and tables over more than one line, block comments, the imports at the top. [startLine, endLine, kind]. */
    List<Object> foldingRanges() {
        List<Object> out = new ArrayList<>();
        Symbols s = symbols();
        Deque<me.padej.jumper.lexer.Token> open = new java.util.ArrayDeque<>();
        me.padej.jumper.lexer.Token firstImport = null, lastImport = null;
        for (me.padej.jumper.lexer.Token t : s.toks) {
            switch (t.type()) {
                case LBRACE, LBRACKET, LPAREN -> open.push(t);
                case RBRACE, RBRACKET, RPAREN -> {
                    if (open.isEmpty()) break;
                    me.padej.jumper.lexer.Token o = open.pop();
                    if (t.line() - 1 > o.line()) out.add(obj("startLine", o.line() - 1, "endLine", t.line() - 2));
                }
                case IMPORT -> { if (firstImport == null) firstImport = t; lastImport = t; }
                default -> { }
            }
        }
        if (firstImport != null && lastImport.line() > firstImport.line())
            out.add(obj("startLine", firstImport.line() - 1, "endLine", lastImport.line() - 1, "kind", "imports"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)/\\*.*?\\*/").matcher(tm.text);
        while (m.find()) {
            int a = tm.position(m.start())[0], b = tm.position(m.end())[0];
            if (b > a) out.add(obj("startLine", a, "endLine", b, "kind", "comment"));
        }
        return out;
    }

    /** Inlay hints: the type of each `dyn` variable where the text tells it - `dyn w: World = server.world();`. */
    List<Object> inlayHints(int fromLine, int toLine) {
        List<Object> out = new ArrayList<>();
        Symbols s = symbols();
        for (Symbols.Sym v : s.all) {
            if (v.kind != Symbols.Kind.VARIABLE || !"dyn".equals(v.detail)) continue;
            me.padej.jumper.lexer.Token name = s.toks.get(v.nameTok);
            if (name.line() - 1 < fromLine || name.line() - 1 > toLine) continue;
            Type t = rootType(v.name);
            if (t == null) continue;
            out.add(obj("position", obj("line", name.line() - 1, "character", name.endCol() - 1),
                    "label", ": " + Members.simple(t.cls()), "kind", 1, "paddingLeft", false));
        }
        return out;
    }

    // ------------------------------------------------------------------ symbols: outline, references, rename

    private Symbols symbols;

    Symbols symbols() {
        if (symbols == null) symbols = new Symbols(tm.text);
        return symbols;
    }

    static final int SK_CLASS = 5, SK_METHOD = 6, SK_FIELD = 8, SK_CONSTRUCTOR = 9, SK_FUNCTION = 12, SK_VARIABLE = 13;

    /** The outline (LSP DocumentSymbol[]): classes with their members, functions (a hook says what it implements), top-level variables. */
    List<Object> documentSymbols() {
        Symbols s = symbols();
        return docSymbols(s, s.outline());
    }

    private List<Object> docSymbols(Symbols s, List<Symbols.Sym> syms) {
        List<Object> out = new ArrayList<>();
        for (Symbols.Sym x : syms) {
            int kind = switch (x.kind) {
                case CLASS -> SK_CLASS;
                case METHOD -> SK_METHOD;
                case CONSTRUCTOR -> SK_CONSTRUCTOR;
                case FIELD -> SK_FIELD;
                case FUNCTION -> SK_FUNCTION;
                default -> SK_VARIABLE;
            };
            String detail = x.detail == null ? "" : x.detail;
            if (x.kind == Symbols.Kind.FUNCTION && x.owner == null) {
                me.padej.jumper.workspace.Hooks.Hook h = me.padej.jumper.workspace.Hooks.find(env.context(), x.name, env.loader());
                if (h != null) detail += "  hook: " + h.owner().getSimpleName();
            }
            me.padej.jumper.lexer.Token name = s.toks.get(x.nameTok);
            me.padej.jumper.lexer.Token first = s.toks.get(Math.max(0, Math.min(x.startTok, x.nameTok)));
            me.padej.jumper.lexer.Token last = s.toks.get(Math.max(x.nameTok, Math.min(x.endTok, s.toks.size() - 1)));
            Map<String, Object> sym = obj("name", x.name, "detail", detail, "kind", kind,
                    "range", range(first, last), "selectionRange", range(name, name));
            if (!x.children.isEmpty()) sym.put("children", docSymbols(s, x.children));
            out.add(sym);
        }
        return out;
    }

    private static Map<String, Object> range(me.padej.jumper.lexer.Token from, me.padej.jumper.lexer.Token to) {
        return obj("start", obj("line", from.line() - 1, "character", from.col() - 1),
                "end", obj("line", to.line() - 1, "character", to.endCol() - 1));
    }

    /** The symbol of the file at the offset (its declaration or a use), or null - a name from elsewhere. */
    Symbols.Sym symbolAt(int offset) {
        int[] p = tm.position(offset);
        Symbols.Sym s = symbols().at(p[0] + 1, p[1]);
        if (s == null && p[1] > 0) s = symbols().at(p[0] + 1, p[1] - 1);   // the cursor just after the name
        return s;
    }

    /** Ranges of every occurrence of the symbol at the offset (with its declaration or without); null if none. */
    List<Map<String, Object>> occurrences(int offset, boolean withDeclaration) {
        Symbols.Sym s = symbolAt(offset);
        if (s == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (me.padej.jumper.lexer.Token t : symbols().occurrences(s)) {
            if (!withDeclaration && t == symbols().toks.get(s.nameTok)) continue;
            out.add(range(t, t));
        }
        return out;
    }

    /** `prepareRename`: the range of the name and the name, or null when it is not a name of this file. */
    Map<String, Object> prepareRename(int offset) {
        Symbols.Sym s = symbolAt(offset);
        if (s == null) return null;
        int[] p = tm.position(offset);
        int k = symbols().tokenAt(p[0] + 1, p[1]);
        if (k < 0 || symbols().symbolOf(k) != s) k = symbols().tokenAt(p[0] + 1, p[1] - 1);
        me.padej.jumper.lexer.Token t = symbols().toks.get(k);
        return obj("range", range(t, t), "placeholder", s.name);
    }

    /** The text edits renaming the symbol at the offset; throws IllegalArgumentException with the reason when it cannot be. */
    List<Map<String, Object>> rename(int offset, String newName) {
        Symbols.Sym s = symbolAt(offset);
        if (s == null) throw new IllegalArgumentException("Only a name declared in this file can be renamed");
        if (!newName.matches("[A-Za-z_][A-Za-z0-9_]*") || KEYWORDS.contains(newName))
            throw new IllegalArgumentException("'" + newName + "' is not a valid name");
        List<Map<String, Object>> edits = new ArrayList<>();
        for (me.padej.jumper.lexer.Token t : symbols().occurrences(s)) edits.add(obj("range", range(t, t), "newText", newName));
        return edits;
    }

    // ------------------------------------------------------------------ signature help

    /** The built-in functions, as signatures (a name may have several forms). */
    static final Map<String, List<String>> BUILTIN_SIGNATURES = Map.ofEntries(
            Map.entry("print", List.of("print(dyn... values)")),
            Map.entry("println", List.of("println(dyn... values)")),
            Map.entry("len", List.of("int len(dyn value)")),
            Map.entry("str", List.of("String str(dyn value)")),
            Map.entry("type", List.of("String type(dyn value)")),
            Map.entry("int", List.of("int int(dyn value)")),
            Map.entry("long", List.of("long long(dyn value)")),
            Map.entry("double", List.of("double double(dyn value)")),
            Map.entry("nanoTime", List.of("long nanoTime()")),
            Map.entry("millis", List.of("long millis()")),
            Map.entry("keys", List.of("dyn keys(dyn table)")),
            Map.entry("error", List.of("error(dyn message)")),
            Map.entry("assert", List.of("dyn assert(dyn condition)", "dyn assert(dyn condition, dyn message)")),
            Map.entry("format", List.of("String format(String format, dyn... args)")),
            Map.entry("range", List.of("dyn range(int n)", "dyn range(int from, int to)", "dyn range(int from, int to, int step)")),
            Map.entry("array", List.of("dyn array(int n)", "dyn array(int n, dyn fill)")),
            Map.entry("table", List.of("dyn table()")),
            Map.entry("isa", List.of("boolean isa(dyn value, dyn cls)")));

    /**
     * `f(a, |`: the signatures of what is being called and the argument the cursor is in - a Java method
     * (every overload, those closed by the policy left out), a constructor (`new X(`), a function of the
     * file or of a module, a built-in. Null when the cursor is not inside a call's parentheses.
     */
    Map<String, Object> signatureHelp(int offset) {
        String t = tm.text;
        // back to the '(' that is still open at the cursor, counting the commas of its level
        int depth = 0, commas = 0, i = Math.min(offset, t.length()) - 1;
        for (; i >= 0; i--) {
            char c = t.charAt(i);
            if (c == ')' || c == ']' || c == '}') depth++;
            else if (c == '(' || c == '[' || c == '{') {
                if (depth == 0) { if (c != '(') return null; break; }
                depth--;
            } else if (c == ',' && depth == 0) commas++;
            else if (c == ';' && depth == 0) return null;
        }
        if (i < 0) return null;
        int e = i;
        while (e > 0 && (t.charAt(e - 1) == ' ' || t.charAt(e - 1) == '\t')) e--;
        int[] w = e > 0 ? tm.wordAt(e - 1) : null;
        if (w == null || w[1] != e) return null;
        String name = t.substring(w[0], w[1]);
        List<String> labels = new ArrayList<>();
        List<List<String>> params = new ArrayList<>();
        List<TextModel.Link> chain = tm.receiverBefore(w[0]);
        int back = chain != null && !chain.isEmpty() && tm.chainStart >= 0 ? tm.chainStart : w[0];
        while (back > 0 && Character.isWhitespace(t.charAt(back - 1))) back--;
        boolean isNew = back >= 3 && t.startsWith("new", back - 3) && (back == 3 || !TextModel.word(t.charAt(back - 4)));
        if (chain != null && !chain.isEmpty() && !isNew) {
            Type type = chainType(chain);
            if (type == null) return null;
            List<Method> ms = Members.named(Members.methods(type.cls(), type.statics()), name);
            for (Method m : Members.allowed(ms, type.cls(), env.policy())) {
                List<String> ps = new ArrayList<>();
                var jp = m.getParameters();
                for (int k = 0; k < jp.length; k++) {
                    String tn = m.isVarArgs() && k == jp.length - 1 ? Members.simple(jp[k].getType().getComponentType()) + "..." : Members.simple(jp[k].getType());
                    ps.add(jp[k].isNamePresent() ? tn + " " + jp[k].getName() : tn);
                }
                add(labels, params, Members.simple(m.getReturnType()) + " " + name, ps);
            }
        } else if (isNew) {
            Class<?> c = chain != null && !chain.isEmpty() ? qualified(chain, name) : classNamed(name);
            if (c != null) {
                for (var k : c.getConstructors()) {
                    List<String> ps = new ArrayList<>();
                    for (var jp : k.getParameters()) ps.add(Members.simple(jp.getType()) + (jp.isNamePresent() ? " " + jp.getName() : ""));
                    add(labels, params, c.getSimpleName(), ps);
                }
            } else {
                String ctor = scriptSignature(name, true);   // a script class: its constructor
                if (ctor != null) addText(labels, params, ctor);
                else if (tm.declarations(name).stream().anyMatch(d -> "class".equals(d.type()))) addText(labels, params, name + "()");
            }
        } else {
            String decl = scriptSignature(name, false);
            if (decl != null) addText(labels, params, decl);
            else if (BUILTIN_SIGNATURES.containsKey(name)) for (String b : BUILTIN_SIGNATURES.get(name)) addText(labels, params, b);
        }
        if (labels.isEmpty()) return null;
        // overloads by the number of their parameters (reflection gives them in no particular order)
        Integer[] order = new Integer[labels.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        List<List<String>> unsorted = params;
        java.util.Arrays.sort(order, java.util.Comparator.comparingInt(k -> unsorted.get(k).size()));
        List<String> l2 = new ArrayList<>();
        List<List<String>> p2 = new ArrayList<>();
        for (int k : order) { l2.add(labels.get(k)); p2.add(params.get(k)); }
        labels = l2;
        params = p2;
        List<Object> sigs = new ArrayList<>();
        int active = -1;
        for (int k = 0; k < labels.size(); k++) {
            String label = labels.get(k);
            List<Object> ps = new ArrayList<>();
            int from = label.indexOf('(') + 1;
            for (String p : params.get(k)) {
                int at = label.indexOf(p, from);
                ps.add(obj("label", List.of(at, at + p.length())));
                from = at + p.length();
            }
            sigs.add(obj("label", label, "parameters", ps));
            int n = params.get(k).size();
            boolean varargs = n > 0 && params.get(k).get(n - 1).contains("...");
            if (active < 0 && (commas < n || varargs)) active = k;
        }
        return obj("signatures", sigs, "activeSignature", active < 0 ? labels.size() - 1 : active, "activeParameter", commas);
    }

    private static void add(List<String> labels, List<List<String>> params, String head, List<String> ps) {
        String label = head + "(" + String.join(", ", ps) + ")";
        if (labels.contains(label)) return;
        labels.add(label);
        params.add(ps);
    }

    /** A signature as text - `int add(int a, int b)`: its parameters are what is between the parentheses. */
    private static void addText(List<String> labels, List<List<String>> params, String sig) {
        int open = sig.indexOf('('), close = sig.lastIndexOf(')');
        List<String> ps = new ArrayList<>();
        String inner = sig.substring(open + 1, close).strip();
        if (!inner.isEmpty()) for (String p : inner.split(",")) ps.add(p.strip());
        add(labels, params, sig.substring(0, open), ps);
    }

    /** `int add(int a, int b)` of a function declared in the file or an imported module; `Point(int x)` of a constructor. */
    private String scriptSignature(String name, boolean ctor) {
        String re = (ctor ? "(?<![\\w.])()" : "\\b((?:dyn|int|long|double|boolean|String|void|[A-Z][\\w]*)\\s+)")
                + java.util.regex.Pattern.quote(name) + "\\s*\\(([^()]*)\\)\\s*\\{";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(re);
        List<String> texts = new ArrayList<>();
        texts.add(tm.text);
        for (String spec : tm.moduleImports()) {
            if (file == null) break;
            try {
                texts.add(Files.readString(file.toAbsolutePath().getParent().resolve(spec.endsWith(".jmp") ? spec : spec + ".jmp")));
            } catch (java.io.IOException | RuntimeException ex) {
                // not there
            }
        }
        for (String text : texts) {
            java.util.regex.Matcher m = p.matcher(text);
            while (m.find()) {
                if (ctor && m.start() > 0 && text.startsWith("new", Math.max(0, m.start() - 4))) continue;
                String head = ctor ? name : (m.group(1).strip() + " " + name);
                return head + "(" + m.group(2).strip().replaceAll("\\s+", " ") + ")";
            }
        }
        return null;
    }

    /** The file the text is (for its modules); set by the server before a request that needs it. */
    Path file;

    // ------------------------------------------------------------------ hover

    /** Markdown for the word at the offset, or null. */
    String hover(int offset) {
        int[] w = tm.wordAt(offset);
        if (w == null) return null;
        String word = tm.text.substring(w[0], w[1]);
        List<TextModel.Link> chain = tm.receiverBefore(w[0]);
        if (chain == null) return null;
        if (!chain.isEmpty()) {
            Type t = chainType(chain);
            if (t != null) return memberHover(t, word, tm.calledAt(w[1]));
            // `java.util.HashMap` in an import or a `new`: a qualified class name, not a chain of members
            StringBuilder q = new StringBuilder();
            for (TextModel.Link l : chain) {
                if (l.call()) return null;
                q.append(l.name().startsWith("new ") ? l.name().substring(4) : l.name()).append('.');
            }
            Class<?> c = Members.load(q + word, env.loader());
            return c == null ? null : classHover(c);
        }
        if (kind() == me.padej.jumper.workspace.FileContext.Kind.CONFIG) {
            String v = configValue(word);
            if (v != null) return v;
        }
        String g = env.context().globals().get(word);
        if (g != null) {
            String host = env.context().host() != null ? env.context().host().name() : "the host";
            return code(g + " " + word) + "\nDefined for scripts by " + host + ".";
        }
        Class<?> cls = Character.isUpperCase(word.charAt(0)) ? classNamed(word) : null;
        if (cls != null) return classHover(cls);
        if (me.padej.jumper.runtime.Builtins.globals().containsKey(word) && tm.declarations(word).isEmpty())
            return code(word) + "\nBuilt-in function of Jumper.";
        TextModel.Decl d = declarationBefore(word, w[0]);
        if (d == null) return null;
        StringBuilder sb = new StringBuilder(code(tm.lineAt(d.offset())));
        Type t = rootType(word);
        if (t != null && (d.type() == null || d.type().equals("dyn"))) sb.append("\nType: `").append(t.cls().getName()).append('`');
        me.padej.jumper.workspace.Hooks.Hook hook = hookAt(word, d.offset() + word.length());
        if (hook != null) {
            Method m = hook.method(declaredParams(d.offset() + word.length()));
            String host = env.context().host() != null ? env.context().host().name() : "the host";
            sb.append("\nImplements ").append(m == null ? "`" + hook.owner().getSimpleName() + "`"
                    : "`" + hook.owner().getSimpleName() + "." + m.getName() + "`:\n" + code(me.padej.jumper.workspace.Hooks.signature(m)))
              .append("\n").append(host).append(" calls it; gd on the name opens the API method.");
        }
        return sb.toString();
    }

    private String memberHover(Type t, String name, boolean called) {
        StringBuilder sb = new StringBuilder();
        List<Method> all = Members.named(Members.methods(t.cls(), t.statics()), name);
        List<Method> open = Members.allowed(all, t.cls(), env.policy());
        if (!all.isEmpty() && (called || Members.propertyType(t.cls(), name) == null)) {
            StringBuilder code = new StringBuilder();
            for (Method m : all) {
                code.append(Members.signature(m));
                if (!open.contains(m)) code.append("   // closed by the access policy");
                code.append('\n');
            }
            sb.append("```java\n").append(code).append("```\n");
        } else {
            Field f = field(t.cls(), name);
            Class<?> pt = Members.propertyType(t.cls(), name);
            if (f != null) sb.append(code(Members.signature(f)));
            else if (pt != null) sb.append(code(Members.simple(pt) + " " + name)).append("\nProperty: `get").append(cap(name)).append("()`.\n");
            else return null;
        }
        sb.append("\nIn `").append(t.cls().getName()).append('`');
        return sb.toString();
    }

    private String classHover(Class<?> c) {
        StringBuilder sb = new StringBuilder("```java\n");
        sb.append(c.isInterface() ? "interface " : c.isEnum() ? "enum " : c.isRecord() ? "record " : "class ").append(c.getName());
        if (c.getSuperclass() != null && c.getSuperclass() != Object.class) sb.append(" extends ").append(Members.simple(c.getSuperclass()));
        sb.append("\n```");
        if (env.policy() != null && !env.policy().visible(c)) sb.append("\nClosed by the access policy.");
        return sb.toString();
    }

    private static Field field(Class<?> c, String name) {
        try {
            for (Field f : c.getFields()) if (f.getName().equals(name)) return f;
        } catch (LinkageError e) {
            // none
        }
        return null;
    }

    private static String code(String s) {
        return "```java\n" + s + "\n```\n";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ completion

    static final int METHOD = 2, FIELD = 5, VARIABLE = 6, CLASS = 7, MODULE = 9, PROPERTY = 10, KEYWORD = 14, FUNCTION = 3;

    private static final List<String> KEYWORDS = List.of("dyn", "int", "long", "double", "boolean", "String", "void", "class", "extends",
            "static", "new", "return", "if", "else", "while", "for", "do", "break", "continue", "switch", "case", "default", "true",
            "false", "null", "import", "this", "super", "try", "catch", "finally", "throw");

    /** What a config (.jmc) may use: data, branching, `return` - no loops, functions, classes, imports. */
    private static final Set<String> CONFIG_KEYWORDS = Set.of("dyn", "int", "long", "double", "boolean", "String", "if", "else",
            "switch", "case", "default", "true", "false", "null", "return");

    /** Object's thread-monitor methods: callable, never what a script wants. */
    private static final Set<String> OBJECT_NOISE = Set.of("wait", "notify", "notifyAll");

    List<Object> complete(int offset, IndexCache cache) {
        int s = offset;
        while (s > 0 && TextModel.word(tm.text.charAt(s - 1))) s--;
        List<Object> items = new ArrayList<>();
        String line = tm.lineBefore(s).strip();
        if (line.startsWith("import") && !line.contains("\"")) return importItems(line.substring(6).strip() + tm.text.substring(s, offset), cache);
        if (kind() == me.padej.jumper.workspace.FileContext.Kind.POLICY) {
            // `Policy.allowPackage("java.u|")`, `allowClass("srv.api.W|")`, `denyMethod("srv.api.World", ...)`: packages and classes
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:allow|deny)(Package|Class|Method|Field)\\s*\\(\\s*\"([\\w.$]*)$")
                    .matcher(tm.text.substring(tm.text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1, offset));
            if (m.find()) {
                List<Object> found = importItems(m.group(2), cache, false);
                if (m.group(1).equals("Package")) found.removeIf(o -> !Integer.valueOf(MODULE).equals(((Map<?, ?>) o).get("kind")));
                return found;
            }
        }
        List<TextModel.Link> chain = tm.receiverBefore(s);
        if (chain == null) return items;
        if (!chain.isEmpty()) {
            Type t = chainType(chain);
            if (t == null) return items;
            Set<String> seen = new LinkedHashSet<>();
            for (Method m : Members.allowed(Members.methods(t.cls(), t.statics()), t.cls(), env.policy())) {
                if (m.getDeclaringClass() == Object.class && OBJECT_NOISE.contains(m.getName())) continue;
                if (!t.statics() && java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;   // on an instance: its methods
                if (!seen.add(Members.signature(m))) continue;
                items.add(obj("label", m.getName(), "kind", METHOD, "detail", Members.signature(m),
                        "sortText", (m.getDeclaringClass() == Object.class ? "z" : "a") + m.getName()));
            }
            for (Field f : Members.fields(t.cls(), t.statics(), env.policy()))
                items.add(obj("label", f.getName(), "kind", FIELD, "detail", Members.signature(f), "sortText", "a" + f.getName()));
            return items;
        }
        if (line.matches("(void|dyn|int|long|double|boolean|String|[A-Z]\\w*)") && topLevel(s)) {
            // naming a new function: the ones the host calls and the script does not declare yet
            List<Object> hooks = hookItems();
            if (!hooks.isEmpty()) return hooks;
        }
        Map<String, Object[]> names = new LinkedHashMap<>();   // label -> kind, detail
        for (var d : tm.declaredNames().entrySet())
            names.put(d.getKey(), new Object[] {d.getValue().equals("class") ? CLASS : VARIABLE, d.getValue()});
        for (var g : env.context().globals().entrySet()) names.putIfAbsent(g.getKey(), new Object[] {VARIABLE, g.getValue()});
        for (var i : imports.entrySet()) names.putIfAbsent(i.getKey(), new Object[] {CLASS, i.getValue()});
        for (String b : me.padej.jumper.runtime.Builtins.globals().keySet()) names.putIfAbsent(b, new Object[] {FUNCTION, "built-in"});
        boolean config = kind() == me.padej.jumper.workspace.FileContext.Kind.CONFIG;
        if (kind() == me.padej.jumper.workspace.FileContext.Kind.POLICY) names.putIfAbsent("Policy", new Object[] {CLASS, "me.padej.jumper.security.Policy"});
        for (String k : KEYWORDS) if (!config || CONFIG_KEYWORDS.contains(k)) names.putIfAbsent(k, new Object[] {KEYWORD, null});
        for (var e : names.entrySet()) {
            Map<String, Object> item = obj("label", e.getKey(), "kind", e.getValue()[0]);
            if (e.getValue()[1] != null) item.put("detail", e.getValue()[1]);
            items.add(item);
        }
        return items;
    }

    /** Is the offset outside every `{ }` (strings and comments skipped)? */
    private boolean topLevel(int offset) {
        String t = tm.text;
        int depth = 0;
        for (int i = 0; i < offset; i++) {
            char c = t.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < offset && t.charAt(i) != c; i++) if (t.charAt(i) == '\\') i++;
            } else if (c == '/' && i + 1 < offset && t.charAt(i + 1) == '/') {
                while (i < offset && t.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < offset && t.charAt(i + 1) == '*') {
                int e = t.indexOf("*/", i + 2);
                i = e < 0 ? offset : e + 1;
            } else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth <= 0;
    }

    /** `void on|`: each hook not declared yet, completed with its parameters - `onJoin(dyn player)`. */
    private List<Object> hookItems() {
        List<Object> items = new ArrayList<>();
        Map<String, String> declared = tm.declaredNames();
        for (String name : me.padej.jumper.workspace.Hooks.names(env.context(), env.loader())) {
            if (declared.containsKey(name)) continue;
            me.padej.jumper.workspace.Hooks.Hook h = me.padej.jumper.workspace.Hooks.find(env.context(), name, env.loader());
            Method m = h == null ? null : h.method(-1);
            StringBuilder params = new StringBuilder();
            if (m != null) {
                var ps = m.getParameters();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) params.append(", ");
                    params.append("dyn ").append(ps[i].isNamePresent() ? ps[i].getName() : "a" + (i + 1));
                }
            }
            items.add(obj("label", name, "kind", METHOD, "insertText", name + "(" + params + ")",
                    "detail", m == null ? h == null ? "hook" : h.owner().getSimpleName()
                            : h.owner().getSimpleName() + ": " + me.padej.jumper.workspace.Hooks.signature(m)));
        }
        return items;
    }

    /** `import java.util.Ha|`: classes of the server's jars (and of the policy's java packages) that start so. */
    private List<Object> importItems(String typed, IndexCache cache) {
        return importItems(typed, cache, true);
    }

    private List<Object> importItems(String typed, IndexCache cache, boolean underPolicy) {
        List<Object> items = new ArrayList<>();
        int dot = typed.lastIndexOf('.');
        String pkg = dot < 0 ? "" : typed.substring(0, dot);
        Set<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (Path jar : env.context().classpath()) {
            JarIndex idx;
            try { idx = cache.get(jar); } catch (java.io.IOException e) { continue; }
            for (ClassInfo ci : idx.classes().values()) if (ci.isPublic()) names.add(ci.name());
        }
        names.addAll(JdkClasses.names());
        for (String name : names) {
            if (name.indexOf('$') >= 0 || !name.startsWith(typed)) continue;
            // one level at a time: the next package segment, or a class of this package
            String rest = name.substring(dot + 1);
            int next = rest.indexOf('.');
            String label = next < 0 ? rest : rest.substring(0, next);
            if (seen.contains(label)) continue;
            if (next < 0) {
                Class<?> c = Members.load(name, env.loader());
                if (c == null || !java.lang.reflect.Modifier.isPublic(c.getModifiers())) continue;
                if (underPolicy && env.policy() != null && !env.policy().visible(c)) continue;   // what the policy closes is not offered
                seen.add(label);
                items.add(obj("label", label, "kind", CLASS, "detail", name));
            } else {
                seen.add(label);
                items.add(obj("label", label, "kind", MODULE, "detail", (pkg.isEmpty() ? "" : pkg + ".") + label));
            }
            if (items.size() >= 500) return items;
        }
        return items;
    }

    // ------------------------------------------------------------------ definition

    record Location(Path file, int offset, int length) {}

    /**
     * Where the name at the offset is declared: in this text, in an imported module file, or - for a Java class
     * or member - in the class's source (the JDK's src.zip, a `-sources.jar`, or an outline of the class).
     */
    Location definition(int offset, Path file, Sources sources) throws java.io.IOException {
        int[] w = tm.wordAt(offset);
        if (w == null) return null;
        String word = tm.text.substring(w[0], w[1]);
        List<TextModel.Link> chain = tm.receiverBefore(w[0]);
        if (chain == null) return null;
        if (!chain.isEmpty()) {
            Type t = chainType(chain);
            if (t != null) return javaMember(t, word, tm.argCount(w[1]), sources);
            Class<?> c = qualified(chain, word);
            return c == null ? null : java(sources.locate(c, null, -1));
        }
        String g = env.context().globals().get(word);
        if (g != null) {
            Class<?> c = Members.load(g, env.loader());
            return c == null ? null : java(sources.locate(c, null, -1));
        }
        TextModel.Decl d = declarationBefore(word, w[0]);
        if (d != null && d.offset() == w[0]) {
            // on the declaration itself: a function the host calls goes to where the host calls it
            Location h = hook(word, w[1], sources);
            if (h != null) return h;
        }
        if (d != null) return new Location(file, d.offset(), word.length());
        Class<?> cls = Character.isUpperCase(word.charAt(0)) ? classNamed(word) : null;
        if (cls != null) return java(sources.locate(cls, null, -1));
        if (file == null) return null;
        for (String spec : tm.moduleImports()) {
            Path m = file.toAbsolutePath().getParent().resolve(spec.endsWith(".jmp") ? spec : spec + ".jmp").normalize();
            try {
                TextModel mt = new TextModel(Files.readString(m));
                List<TextModel.Decl> ds = mt.declarations(word);
                if (!ds.isEmpty()) return new Location(m, ds.get(0).offset(), word.length());
            } catch (java.io.IOException | RuntimeException e) {
                // an unreadable module: not there
            }
        }
        return null;
    }

    /** `recv.name`: the method (the overload with that many arguments, if any), else the field or the property getter. */
    private Location javaMember(Type t, String name, int args, Sources sources) throws java.io.IOException {
        List<Method> ms = Members.named(Members.methods(t.cls(), t.statics()), name);
        if (!ms.isEmpty()) {
            Method m = ms.get(0);
            for (Method k : ms) if (k.getParameterCount() == args) { m = k; break; }
            return java(sources.locate(m.getDeclaringClass(), name, args >= 0 ? args : m.getParameterCount()));
        }
        Field f = field(t.cls(), name);
        if (f != null) return java(sources.locate(f.getDeclaringClass(), name, -1));
        for (String getter : new String[] {"get" + cap(name), "is" + cap(name)}) {
            List<Method> gs = Members.named(Members.methods(t.cls(), false), getter);
            if (!gs.isEmpty()) return java(sources.locate(gs.get(0).getDeclaringClass(), getter, 0));
        }
        return null;
    }

    /**
     * `void onJoin(...)` where the host calls onJoin: the method of its API the function implements (the
     * overload with as many parameters), or the class the descriptor names.
     */
    private Location hook(String name, int end, Sources sources) throws java.io.IOException {
        me.padej.jumper.workspace.Hooks.Hook h = hookAt(name, end);
        if (h == null) return null;
        Method m = h.method(declaredParams(end));
        return m == null ? java(sources.locate(h.owner(), null, -1))
                : java(sources.locate(m.getDeclaringClass(), m.getName(), m.getParameterCount()));
    }

    /** The hook `name` when the text after it opens a parameter list (a declaration); else null. */
    private me.padej.jumper.workspace.Hooks.Hook hookAt(String name, int end) {
        if (declaredParams(end) < 0) return null;
        return me.padej.jumper.workspace.Hooks.find(env.context(), name, env.loader());
    }

    /** How many parameters the list opening after `end` has; -1 if no `(` follows. */
    private int declaredParams(int end) {
        String t = tm.text;
        int i = end;
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
        if (i >= t.length() || t.charAt(i) != '(') return -1;
        int depth = 0, commas = 0;
        boolean any = false;
        for (i++; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') { if (depth-- == 0) break; }
            else if (c == ',' && depth == 0) commas++;
            else if (!Character.isWhitespace(c)) any = true;
        }
        return any ? commas + 1 : 0;
    }

    private Class<?> qualified(List<TextModel.Link> chain, String word) {
        StringBuilder q = new StringBuilder();
        for (TextModel.Link l : chain) {
            if (l.call()) return null;
            q.append(l.name().startsWith("new ") ? l.name().substring(4) : l.name()).append('.');
        }
        return Members.load(q + word, env.loader());
    }

    private static Location java(Sources.Place p) {
        return new Location(p.file(), p.offset(), p.length());
    }

    /** The declaration of `name` nearest before `offset`; else the first after it (functions and classes are hoisted). */
    private TextModel.Decl declarationBefore(String name, int offset) {
        TextModel.Decl before = null, after = null;
        for (TextModel.Decl d : tm.declarations(name)) {
            if (d.offset() <= offset) before = d;
            else if (after == null) after = d;
        }
        return before != null ? before : after;
    }
}
