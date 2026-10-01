package me.padej.jumper.lsp;

import me.padej.jumper.workspace.Checker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static me.padej.jumper.lsp.Json.obj;

/**
 * The Jumper language server: LSP over stdin/stdout ({@code jmp --lsp}), no dependencies.
 *
 * <p>What it does now: diagnostics for .jmp, .jmc and .jma while you type - every error of the file, each
 * checked in its context ({@link me.padej.jumper.workspace.Workspace}: the host's policy, globals and the
 * server's classes). Notes on how the context was found (no policy, two hosts) come as hints on line 1.
 *
 * <p>How: one reader thread takes messages off stdin; everything else runs in order on one worker thread
 * with a big stack (the parser recurses on nesting). A change is checked {@value #DEBOUNCE_MS} ms after the
 * last keystroke, so a burst of typing is one check. The file's context is looked up again at most every
 * {@value #CONTEXT_TTL_MS} ms and at once after a save (a new policy, a rebuilt plugin).
 */
public final class LspServer {
    static final long DEBOUNCE_MS = 120, CONTEXT_TTL_MS = 3000, CHECK_TIMEOUT_MS = 3000;

    private final InputStream in;
    private final OutputStream out;
    private final Checker checker = new Checker(CONTEXT_TTL_MS, CHECK_TIMEOUT_MS);
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(null, r, "jumper-lsp", me.padej.jumper.interp.Interpreter.SCRIPT_STACK_BYTES);
        t.setDaemon(true);
        return t;
    });
    // owned by the worker thread
    private final Map<String, String> docs = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> pending = new HashMap<>();
    private volatile boolean shutdown;

    public LspServer(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** Serves until `exit` or the end of input; the exit code LSP asks for (0 after `shutdown`, else 1). */
    public int run() throws IOException {
        try {
            while (true) {
                String body = readMessage();
                if (body == null) break;
                Map<String, Object> msg;
                try {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) Json.parse(body);
                    msg = m;
                } catch (RuntimeException e) {
                    send(obj("jsonrpc", "2.0", "id", null, "error", obj("code", -32700, "message", "Parse error: " + e.getMessage())));
                    continue;
                }
                if ("exit".equals(msg.get("method"))) break;
                worker.execute(() -> handle(msg));
            }
        } finally {
            worker.shutdown();
            try { worker.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            checker.close();
        }
        return shutdown ? 0 : 1;
    }

    // ------------------------------------------------------------------ messages

    @SuppressWarnings("unchecked")
    private void handle(Map<String, Object> msg) {
        String method = (String) msg.get("method");
        Object id = msg.get("id");
        Map<String, Object> params = msg.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        try {
            if (method == null) return;   // a response to something we sent: nothing to do
            if (msg.containsKey("id")) {
                switch (method) {
                    case "initialize" -> reply(id, obj(
                            "capabilities", obj(
                                    "textDocumentSync", obj("openClose", true, "change", 1, "save", obj("includeText", false)),
                                    "hoverProvider", true,
                                    "completionProvider", obj("triggerCharacters", List.of(".")),
                                    "definitionProvider", true,
                                    "documentSymbolProvider", true,
                                    "codeActionProvider", obj("codeActionKinds", List.of("quickfix")),
                                    "documentFormattingProvider", true,
                                    "foldingRangeProvider", true,
                                    "inlayHintProvider", true,
                                    "referencesProvider", true,
                                    "documentHighlightProvider", true,
                                    "renameProvider", obj("prepareProvider", true),
                                    "signatureHelpProvider", obj("triggerCharacters", List.of("(", ","), "retriggerCharacters", List.of(")")),
                                    "semanticTokensProvider", obj(
                                            "legend", obj("tokenTypes", SemanticTokens.TYPES, "tokenModifiers", SemanticTokens.MODIFIERS),
                                            "full", true)),
                            "serverInfo", obj("name", "jumper", "version", me.padej.jumper.script.JmpScriptEngineFactory.VERSION)));
                    case "shutdown" -> { shutdown = true; reply(id, null); }
                    case "textDocument/hover" -> reply(id, at(params, (f, off, file) -> {
                        String md = f.hover(off);
                        return md == null ? null : obj("contents", obj("kind", "markdown", "value", md));
                    }));
                    case "textDocument/completion" -> {
                        Object items = at(params, (f, off, file) -> f.complete(off, index()));
                        reply(id, obj("isIncomplete", false, "items", items == null ? List.of() : items));
                    }
                    case "textDocument/definition" -> reply(id, at(params, (f, off, file) -> {
                        Features.Location loc = f.definition(off, file, sources());
                        if (loc == null) return null;
                        String target = loc.file() == null ? null : loc.file().toUri().toString();
                        TextModel tm = loc.file() != null && loc.file().equals(file) ? f.text()
                                : new TextModel(java.nio.file.Files.readString(loc.file()));
                        int[] a = tm.position(loc.offset()), b = tm.position(loc.offset() + loc.length());
                        return obj("uri", target, "range", obj("start", pos(a[0], a[1]), "end", pos(b[0], b[1])));
                    }));
                    case "textDocument/codeAction" -> {
                        String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
                        Map<String, Object> ctx = (Map<String, Object>) params.getOrDefault("context", Map.of());
                        List<Map<String, Object>> diags = (List<Map<String, Object>>) ctx.getOrDefault("diagnostics", List.of());
                        Object actions = whole(params, f -> f.codeActions(uri, diags, index()));
                        reply(id, actions == null ? List.of() : actions);
                    }
                    case "textDocument/formatting" -> {
                        Map<String, Object> opts = (Map<String, Object>) params.getOrDefault("options", Map.of());
                        int size = opts.get("tabSize") instanceof Number n ? n.intValue() : 4;
                        String indent = Boolean.FALSE.equals(opts.get("insertSpaces")) ? "\t" : " ".repeat(Math.max(1, size));
                        Object edits = whole(params, f -> {
                            String now = f.text().text, formatted = f.formatted(indent);
                            if (formatted.equals(now)) return List.of();
                            int[] end = f.text().position(now.length());
                            return List.of(obj("range", obj("start", pos(0, 0), "end", pos(end[0], end[1])), "newText", formatted));
                        });
                        reply(id, edits == null ? List.of() : edits);
                    }
                    case "textDocument/foldingRange" -> {
                        Object r = whole(params, Features::foldingRanges);
                        reply(id, r == null ? List.of() : r);
                    }
                    case "textDocument/inlayHint" -> {
                        Map<String, Object> range = (Map<String, Object>) params.get("range");
                        int from = range == null ? 0 : ((Number) ((Map<String, Object>) range.get("start")).get("line")).intValue();
                        int to = range == null ? Integer.MAX_VALUE : ((Number) ((Map<String, Object>) range.get("end")).get("line")).intValue();
                        Object r = whole(params, f -> f.inlayHints(from, to));
                        reply(id, r == null ? List.of() : r);
                    }
                    case "textDocument/documentSymbol" -> {
                        Object syms = whole(params, Features::documentSymbols);
                        reply(id, syms == null ? List.of() : syms);
                    }
                    case "textDocument/references" -> reply(id, at(params, (f, off, file) -> {
                        Object ctx = params.get("context");
                        boolean decl = !(ctx instanceof Map<?, ?> c) || !Boolean.FALSE.equals(c.get("includeDeclaration"));
                        List<Map<String, Object>> rs = f.occurrences(off, decl);
                        if (rs == null) return null;
                        String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
                        List<Object> out = new java.util.ArrayList<>();
                        for (Map<String, Object> r : rs) out.add(obj("uri", uri, "range", r));
                        return out;
                    }));
                    case "textDocument/documentHighlight" -> reply(id, at(params, (f, off, file) -> {
                        List<Map<String, Object>> rs = f.occurrences(off, true);
                        if (rs == null) return null;
                        List<Object> out = new java.util.ArrayList<>();
                        for (Map<String, Object> r : rs) out.add(obj("range", r, "kind", 1));
                        return out;
                    }));
                    case "textDocument/prepareRename" -> reply(id, at(params, (f, off, file) -> f.prepareRename(off)));
                    case "textDocument/rename" -> {
                        String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
                        String newName = String.valueOf(params.get("newName"));
                        String[] problem = new String[1];
                        Object edits = at(params, (f, off, file) -> {
                            try {
                                return f.rename(off, newName);
                            } catch (IllegalArgumentException e) {
                                problem[0] = e.getMessage();
                                return null;
                            }
                        });
                        if (problem[0] != null) send(obj("jsonrpc", "2.0", "id", id, "error", obj("code", -32602, "message", problem[0])));
                        else reply(id, edits == null ? null : obj("changes", obj(uri, edits)));
                    }
                    case "textDocument/signatureHelp" -> reply(id, at(params, (f, off, file) -> {
                        f.file = file;
                        return f.signatureHelp(off);
                    }));
                    case "textDocument/semanticTokens/full" -> {
                        String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
                        String text = docs.get(uri);
                        Path file = path(uri);
                        int[] data = new int[0];
                        if (text != null && file != null) {
                            try {
                                data = new Features(checker.env(file), text).semanticTokens();
                            } catch (RuntimeException e) {
                                System.err.println("[jumper-lsp] semantic tokens: " + e);
                            }
                        }
                        List<Integer> list = new java.util.ArrayList<>(data.length);
                        for (int v : data) list.add(v);
                        reply(id, obj("data", list));
                    }
                    default -> send(obj("jsonrpc", "2.0", "id", id, "error", obj("code", -32601, "message", "Method not found: " + method)));
                }
                return;
            }
            switch (method) {
                case "textDocument/didOpen" -> {
                    Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
                    String uri = (String) td.get("uri");
                    docs.put(uri, (String) td.get("text"));
                    schedule(uri, 0);
                }
                case "textDocument/didChange" -> {
                    String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
                    List<Object> changes = (List<Object>) params.get("contentChanges");
                    if (changes == null || changes.isEmpty()) return;
                    // full sync: the last change holds the whole text
                    docs.put(uri, (String) ((Map<String, Object>) changes.get(changes.size() - 1)).get("text"));
                    schedule(uri, DEBOUNCE_MS);
                }
                case "textDocument/didSave", "workspace/didChangeWatchedFiles" -> {
                    // a saved .jma, a rebuilt plugin, a new descriptor: look at the contexts again, re-check all
                    checker.invalidate();
                    for (String uri : docs.keySet()) schedule(uri, 0);
                }
                case "textDocument/didClose" -> {
                    String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
                    docs.remove(uri);
                    ScheduledFuture<?> f = pending.remove(uri);
                    if (f != null) f.cancel(false);
                    publish(uri, List.of());
                }
                default -> { }   // initialized, $/cancelRequest, $/setTrace...: nothing to do
            }
        } catch (RuntimeException e) {
            System.err.println("[jumper-lsp] " + method + ": " + e);
            if (msg.containsKey("id") && method != null)
                send(obj("jsonrpc", "2.0", "id", id, "error", obj("code", -32603, "message", String.valueOf(e))));
        }
    }

    /** A request about a whole document: `what` of its Features, or null (not open; a failure is logged). */
    @SuppressWarnings("unchecked")
    private Object whole(Map<String, Object> params, java.util.function.Function<Features, Object> what) {
        String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
        String text = docs.get(uri);
        Path file = path(uri);
        if (text == null || file == null) return null;
        try {
            Features f = new Features(checker.env(file), text);
            f.file = file;
            return what.apply(f);
        } catch (RuntimeException e) {
            System.err.println("[jumper-lsp] " + e);
            return null;
        }
    }

    private interface At {
        Object apply(Features f, int offset, Path file) throws Exception;
    }

    /** A position request on an open document: the features of its text at that offset; null when there is nothing. */
    @SuppressWarnings("unchecked")
    private Object at(Map<String, Object> params, At what) {
        String uri = (String) ((Map<String, Object>) params.get("textDocument")).get("uri");
        String text = docs.get(uri);
        Path file = path(uri);
        if (text == null || file == null) return null;
        Map<String, Object> p = (Map<String, Object>) params.get("position");
        Features f = new Features(checker.env(file), text);
        int off = f.text().offset(((Number) p.get("line")).intValue(), ((Number) p.get("character")).intValue());
        try {
            return what.apply(f, off, file);
        } catch (Exception e) {
            System.err.println("[jumper-lsp] " + e);
            return null;
        }
    }

    private Sources sources;

    private Sources sources() {
        if (sources == null) sources = new Sources(Sources.defaultDir());
        return sources;
    }

    private me.padej.jumper.workspace.IndexCache index;

    private me.padej.jumper.workspace.IndexCache index() {
        if (index == null) index = me.padej.jumper.workspace.IndexCache.atDefault();
        return index;
    }

    private void schedule(String uri, long delayMs) {
        ScheduledFuture<?> f = pending.remove(uri);
        if (f != null) f.cancel(false);
        pending.put(uri, worker.schedule(() -> diagnose(uri), delayMs, TimeUnit.MILLISECONDS));
    }

    // ------------------------------------------------------------------ diagnostics

    private void diagnose(String uri) {
        pending.remove(uri);
        String text = docs.get(uri);
        if (text == null) return;
        Path file = path(uri);
        if (file == null) { publish(uri, List.of()); return; }
        List<Object> diags = new ArrayList<>();
        try {
            Checker.Report r = checker.check(file, text);
            String[] lines = text.split("\n", -1);
            for (Checker.Problem p : r.problems()) {
                boolean note = p.severity() == Checker.Severity.NOTE;
                diags.add(obj(
                        "range", range(lines, p.line(), p.col(), note),
                        "severity", note ? 4 : p.severity() == Checker.Severity.WARNING ? 2 : 1,
                        "source", "jumper",
                        "message", p.message()));
            }
        } catch (Throwable t) {   // a bug in the checker must not take the server down
            System.err.println("[jumper-lsp] check of " + uri + " failed: " + t);
            t.printStackTrace();
            diags.add(obj("range", range(new String[] {""}, 1, 1, true), "severity", 1, "source", "jumper",
                    "message", "internal error of the Jumper checker: " + t));
        }
        publish(uri, diags);
    }

    /**
     * The range of a problem at 1-based line:col: the word that starts there (an identifier or a number),
     * at least one character. LSP positions are 0-based UTF-16 units - what String indexes, like the lexer.
     */
    static Map<String, Object> range(String[] lines, int line, int col, boolean note) {
        int l = Math.max(0, Math.min(line - 1, lines.length - 1));
        String s = lines[l].endsWith("\r") ? lines[l].substring(0, lines[l].length() - 1) : lines[l];
        int c = Math.max(0, Math.min(col - 1, s.length()));
        if (note) return obj("start", pos(l, 0), "end", pos(l, 0));
        if (c == s.length() && c > 0) c--;   // at the end of the line (an error at EOF): mark the last character
        int e = c;
        while (e < s.length() && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_')) e++;
        if (e == c && c < s.length()) e = c + 1;
        return obj("start", pos(l, c), "end", pos(l, e));
    }

    private static Map<String, Object> pos(int line, int ch) {
        return obj("line", line, "character", ch);
    }

    private void publish(String uri, List<Object> diagnostics) {
        send(obj("jsonrpc", "2.0", "method", "textDocument/publishDiagnostics",
                "params", obj("uri", uri, "diagnostics", diagnostics)));
    }

    static Path path(String uri) {
        if (uri == null || !uri.startsWith("file:")) return null;
        try {
            return Path.of(java.net.URI.create(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ framing

    private void reply(Object id, Object result) {
        Map<String, Object> m = obj("jsonrpc", "2.0", "id", id);
        m.put("result", result);
        send(m);
    }

    private void send(Map<String, Object> msg) {
        byte[] body = Json.write(msg).getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            try {
                out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                System.err.println("[jumper-lsp] cannot write: " + e);
            }
        }
    }

    /** One message body, or null at the end of input. */
    private String readMessage() throws IOException {
        int length = -1;
        while (true) {
            String header = readLine();
            if (header == null) return null;
            if (header.isEmpty()) {
                if (length >= 0) break;
                continue;   // stray blank line
            }
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length"))
                length = Integer.parseInt(header.substring(colon + 1).trim());
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        return new String(body, StandardCharsets.UTF_8);
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.US_ASCII);
    }
}
