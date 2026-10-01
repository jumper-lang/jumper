package me.padej.jumper.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static me.padej.jumper.lsp.Json.obj;
import static org.junit.jupiter.api.Assertions.*;

/** The language server driven through pipes, as an editor drives it through stdin/stdout. */
class LspServerTest {

    /** A client: writes framed messages to the server, collects what it sends back. */
    private static final class Client implements AutoCloseable {
        final PipedOutputStream toServer = new PipedOutputStream();
        final PipedInputStream fromServer = new PipedInputStream(1 << 20);
        final BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        final Thread server, reader;
        volatile int exitCode = -1;

        Client() throws IOException {
            PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 20);
            PipedOutputStream serverOut = new PipedOutputStream(fromServer);
            server = new Thread(() -> {
                try {
                    exitCode = new LspServer(serverIn, serverOut).run();
                    serverOut.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            server.start();
            reader = new Thread(() -> {
                try {
                    while (true) {
                        Map<String, Object> m = read(fromServer);
                        if (m == null) return;
                        received.add(m);
                    }
                } catch (IOException e) {
                    // closed
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        void send(Map<String, Object> m) throws IOException {
            byte[] b = Json.write(m).getBytes(StandardCharsets.UTF_8);
            toServer.write(("Content-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            toServer.write(b);
            toServer.flush();
        }

        Map<String, Object> next() throws InterruptedException {
            Map<String, Object> m = received.poll(20, TimeUnit.SECONDS);
            assertNotNull(m, "no message from the server");
            return m;
        }

        /** The next diagnostics published for `uri`, as "line:char-line:char severity message". */
        @SuppressWarnings("unchecked")
        List<String> diagnostics(String uri) throws InterruptedException {
            while (true) {
                Map<String, Object> m = next();
                if (!"textDocument/publishDiagnostics".equals(m.get("method"))) continue;
                Map<String, Object> p = (Map<String, Object>) m.get("params");
                if (!uri.equals(p.get("uri"))) continue;
                return ((List<Object>) p.get("diagnostics")).stream().map(o -> {
                    Map<String, Object> d = (Map<String, Object>) o;
                    Map<String, Object> r = (Map<String, Object>) d.get("range");
                    Map<String, Object> s = (Map<String, Object>) r.get("start"), e = (Map<String, Object>) r.get("end");
                    return s.get("line") + ":" + s.get("character") + "-" + e.get("line") + ":" + e.get("character")
                            + " " + d.get("severity") + " " + d.get("message");
                }).toList();
            }
        }

        @Override
        public void close() throws Exception {
            toServer.close();
            server.join(10_000);
        }
    }

    private static Map<String, Object> read(InputStream in) throws IOException {
        StringBuilder header = new StringBuilder();
        int length = -1;
        while (true) {
            int c = in.read();
            if (c == -1) return null;
            if (c == '\n') {
                String line = header.toString().trim();
                header.setLength(0);
                if (line.isEmpty()) break;
                if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
            } else header.append((char) c);
        }
        byte[] body = in.readNBytes(length);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) Json.parse(new String(body, StandardCharsets.UTF_8));
        return m;
    }

    private static Map<String, Object> notification(String method, Map<String, Object> params) {
        return obj("jsonrpc", "2.0", "method", method, "params", params);
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsFollowTheBufferInItsContext(@TempDir Path dir) throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server.resolve("scripts"));
        Files.writeString(server.resolve("scripts.jma"), "Policy.allowPackage(\"java.util\");\n");
        Path script = server.resolve("scripts/a.jmp");   // never written: the editor's buffer is all there is
        String uri = script.toUri().toString();

        try (Client c = new Client()) {
            c.send(obj("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", obj("capabilities", obj())));
            Map<String, Object> init = c.next();
            assertEquals(1L, init.get("id"));
            Map<String, Object> caps = (Map<String, Object>) ((Map<String, Object>) init.get("result")).get("capabilities");
            assertEquals(1L, ((Map<String, Object>) caps.get("textDocumentSync")).get("change"));
            c.send(notification("initialized", obj()));

            // open: every error, each with its range; the policy of the server folder applies
            c.send(notification("textDocument/didOpen", obj("textDocument", obj("uri", uri, "languageId", "jumper", "version", 1,
                    "text", "import java.io.File;\ndyn a = 1 +;\nint b = \"s\";\ndyn ok = 1;\n"))));
            assertEquals(List.of(
                    "0:0-0:6 1 Access denied: java.io.File",
                    "1:11-1:12 1 Unexpected token ';'",
                    "2:4-2:5 1 Cannot assign string to int"), c.diagnostics(uri));

            // typing: a burst of changes is one check of the last text
            for (int v = 2; v <= 5; v++) {
                String text = "import java.util.List;\ndyn a = 1 + " + v + ";\n";
                c.send(notification("textDocument/didChange", obj("textDocument", obj("uri", uri, "version", v),
                        "contentChanges", List.of(obj("text", text)))));
            }
            assertEquals(List.of(), c.diagnostics(uri));
            c.send(notification("textDocument/didChange", obj("textDocument", obj("uri", uri, "version", 6),
                    "contentChanges", List.of(obj("text", "break;\n")))));
            assertEquals(List.of("0:0-0:5 1 'break' outside of a loop"), c.diagnostics(uri));

            // a request the server does not know: MethodNotFound, and it keeps serving
            c.send(obj("jsonrpc", "2.0", "id", 7, "method", "textDocument/codeLens", "params", obj()));
            Map<String, Object> err;
            do err = c.next(); while (err.get("id") == null);
            assertEquals(-32601L, ((Map<String, Object>) err.get("error")).get("code"));

            // close: its diagnostics are cleared
            c.send(notification("textDocument/didClose", obj("textDocument", obj("uri", uri))));
            assertEquals(List.of(), c.diagnostics(uri));

            c.send(obj("jsonrpc", "2.0", "id", 8, "method", "shutdown"));
            Map<String, Object> down;
            do down = c.next(); while (!Long.valueOf(8).equals(down.get("id")));
            assertTrue(down.containsKey("result"));
            assertNull(down.get("result"));
            c.send(notification("exit", obj()));
            c.server.join(10_000);
            assertEquals(0, c.exitCode);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void hoverCompletionAndDefinitionOverTheWire(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.jmp");
        String uri = file.toUri().toString();
        String text = "import java.util.HashMap;\ndyn m = new HashMap();\nm.put(1, 2);\nm.\n";
        try (Client c = new Client()) {
            c.send(obj("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", obj()));
            Map<String, Object> caps = (Map<String, Object>) ((Map<String, Object>) c.next().get("result")).get("capabilities");
            assertEquals(true, caps.get("hoverProvider"));
            assertEquals(true, caps.get("definitionProvider"));
            assertEquals(List.of("."), ((Map<String, Object>) caps.get("completionProvider")).get("triggerCharacters"));
            c.send(notification("textDocument/didOpen", obj("textDocument", obj("uri", uri, "languageId", "jumper", "version", 1, "text", text))));

            c.send(obj("jsonrpc", "2.0", "id", 2, "method", "textDocument/hover",
                    "params", obj("textDocument", obj("uri", uri), "position", obj("line", 2, "character", 3))));
            Map<String, Object> hover = response(c, 2);
            String md = (String) ((Map<String, Object>) ((Map<String, Object>) hover.get("result")).get("contents")).get("value");
            assertTrue(md.contains("Object put(Object, Object)"), md);

            c.send(obj("jsonrpc", "2.0", "id", 3, "method", "textDocument/completion",
                    "params", obj("textDocument", obj("uri", uri), "position", obj("line", 3, "character", 2))));
            List<Object> items = (List<Object>) ((Map<String, Object>) response(c, 3).get("result")).get("items");
            assertTrue(items.stream().anyMatch(i -> "containsKey".equals(((Map<String, Object>) i).get("label"))), items.toString());

            c.send(obj("jsonrpc", "2.0", "id", 4, "method", "textDocument/definition",
                    "params", obj("textDocument", obj("uri", uri), "position", obj("line", 2, "character", 0))));
            Map<String, Object> loc = (Map<String, Object>) response(c, 4).get("result");
            assertEquals(uri, loc.get("uri"));
            assertEquals(obj("start", obj("line", 1L, "character", 4L), "end", obj("line", 1L, "character", 5L)), loc.get("range"));

            // nothing under the cursor: a null result, not an error
            c.send(obj("jsonrpc", "2.0", "id", 5, "method", "textDocument/hover",
                    "params", obj("textDocument", obj("uri", uri), "position", obj("line", 1, "character", 6))));
            Map<String, Object> none = response(c, 5);
            assertTrue(none.containsKey("result") && none.get("result") == null, none.toString());

            // semantic tokens: the legend in the capabilities, 5 numbers a token; `HashMap` in line 2 is a class
            Map<String, Object> st = (Map<String, Object>) caps.get("semanticTokensProvider");
            List<Object> types = (List<Object>) ((Map<String, Object>) st.get("legend")).get("tokenTypes");
            assertEquals(true, st.get("full"));
            c.send(obj("jsonrpc", "2.0", "id", 6, "method", "textDocument/semanticTokens/full", "params", obj("textDocument", obj("uri", uri))));
            List<Object> data = (List<Object>) ((Map<String, Object>) response(c, 6).get("result")).get("data");
            assertEquals(0, data.size() % 5);
            long line = 0, col = 0;
            List<String> line1 = new java.util.ArrayList<>();
            for (int i = 0; i < data.size(); i += 5) {
                long dl = ((Number) data.get(i)).longValue(), dc = ((Number) data.get(i + 1)).longValue();
                col = dl == 0 ? col + dc : dc;
                line += dl;
                if (line == 1) line1.add(text.split("\n")[1].substring((int) col, (int) (col + ((Number) data.get(i + 2)).longValue()))
                        + ":" + types.get(((Number) data.get(i + 3)).intValue()));
            }
            assertEquals(List.of("dyn:keyword", "m:variable", "new:keyword", "HashMap:class"), line1);
        }
    }

    private static Map<String, Object> pos(int line, int ch) {
        return obj("line", line, "character", ch);
    }

    /** "line:char-line:char" of an LSP range. */
    @SuppressWarnings("unchecked")
    private static String span(Object range) {
        Map<String, Object> r = (Map<String, Object>) range;
        Map<String, Object> s = (Map<String, Object>) r.get("start"), e = (Map<String, Object>) r.get("end");
        return s.get("line") + ":" + s.get("character") + "-" + e.get("line") + ":" + e.get("character");
    }

    @Test
    @SuppressWarnings("unchecked")
    void outlineReferencesAndRenameOverTheWire(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.jmp");
        String uri = file.toUri().toString();
        String text = "dyn total = 0;\nclass Box { int size; int grow() { size++; return size; } }\n"
                + "int add(int n) { total = total + n; return total; }\nadd(2);\nprintln(total);\n";
        try (Client c = new Client()) {
            c.send(obj("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", obj()));
            Map<String, Object> caps = (Map<String, Object>) ((Map<String, Object>) c.next().get("result")).get("capabilities");
            assertEquals(true, caps.get("documentSymbolProvider"));
            assertEquals(true, caps.get("referencesProvider"));
            assertEquals(obj("prepareProvider", true), caps.get("renameProvider"));
            for (String cap : List.of("codeActionProvider", "documentFormattingProvider", "foldingRangeProvider", "inlayHintProvider",
                    "signatureHelpProvider", "documentHighlightProvider")) assertNotNull(caps.get(cap), cap);
            c.send(notification("textDocument/didOpen", obj("textDocument", obj("uri", uri, "languageId", "jumper", "version", 1, "text", text))));
            Map<String, Object> doc = obj("uri", uri);

            c.send(obj("jsonrpc", "2.0", "id", 2, "method", "textDocument/documentSymbol", "params", obj("textDocument", doc)));
            List<Object> syms = (List<Object>) response(c, 2).get("result");
            List<String> outline = new java.util.ArrayList<>();
            for (Object o : syms) {
                Map<String, Object> m = (Map<String, Object>) o;
                outline.add(m.get("name") + "/" + m.get("kind") + " " + span(m.get("range")) + " sel " + span(m.get("selectionRange")));
                for (Object k : (List<Object>) m.getOrDefault("children", List.of())) outline.add("  " + ((Map<String, Object>) k).get("name") + "/" + ((Map<String, Object>) k).get("kind"));
            }
            assertEquals(List.of("total/13 0:0-0:14 sel 0:4-0:9", "Box/5 1:0-1:59 sel 1:6-1:9", "  size/8", "  grow/6",
                    "add/12 2:0-2:51 sel 2:4-2:7"), outline);

            // references of `total` from a use in add(), with and without the declaration
            c.send(obj("jsonrpc", "2.0", "id", 3, "method", "textDocument/references",
                    "params", obj("textDocument", doc, "position", pos(2, 26), "context", obj("includeDeclaration", true))));
            List<String> refs = ((List<Object>) response(c, 3).get("result")).stream().map(o -> span(((Map<String, Object>) o).get("range"))).toList();
            assertEquals(List.of("0:4-0:9", "2:17-2:22", "2:25-2:30", "2:43-2:48", "4:8-4:13"), refs);
            c.send(obj("jsonrpc", "2.0", "id", 4, "method", "textDocument/references",
                    "params", obj("textDocument", doc, "position", pos(2, 26), "context", obj("includeDeclaration", false))));
            assertEquals(4, ((List<Object>) response(c, 4).get("result")).size());

            // highlights of the field `size` in the method
            c.send(obj("jsonrpc", "2.0", "id", 5, "method", "textDocument/documentHighlight", "params", obj("textDocument", doc, "position", pos(1, 36))));
            assertEquals(List.of("1:16-1:20", "1:35-1:39", "1:50-1:54"),
                    ((List<Object>) response(c, 5).get("result")).stream().map(o -> span(((Map<String, Object>) o).get("range"))).toList());

            // rename: prepare, then the edits
            c.send(obj("jsonrpc", "2.0", "id", 6, "method", "textDocument/prepareRename", "params", obj("textDocument", doc, "position", pos(3, 1))));
            Map<String, Object> prep = (Map<String, Object>) response(c, 6).get("result");
            assertEquals("add", prep.get("placeholder"));
            assertEquals("3:0-3:3", span(prep.get("range")));
            c.send(obj("jsonrpc", "2.0", "id", 7, "method", "textDocument/rename", "params", obj("textDocument", doc, "position", pos(3, 1), "newName", "plus")));
            Map<String, Object> edit = (Map<String, Object>) response(c, 7).get("result");
            List<Object> edits = (List<Object>) ((Map<String, Object>) edit.get("changes")).get(uri);
            assertEquals(List.of("2:4-2:7 plus", "3:0-3:3 plus"), edits.stream().map(o -> span(((Map<String, Object>) o).get("range")) + " " + ((Map<String, Object>) o).get("newText")).toList());

            // not a name: an error with the reason; a name not of this file: nothing to rename
            c.send(obj("jsonrpc", "2.0", "id", 8, "method", "textDocument/rename", "params", obj("textDocument", doc, "position", pos(3, 1), "newName", "class")));
            assertEquals("'class' is not a valid name", ((Map<String, Object>) response(c, 8).get("error")).get("message"));
            c.send(obj("jsonrpc", "2.0", "id", 9, "method", "textDocument/prepareRename", "params", obj("textDocument", doc, "position", pos(4, 2))));
            Map<String, Object> none = response(c, 9);
            assertTrue(none.containsKey("result") && none.get("result") == null, none.toString());

            // formatting: one edit with the re-indented text (the text is flat already: nothing)
            c.send(obj("jsonrpc", "2.0", "id", 10, "method", "textDocument/formatting", "params", obj("textDocument", doc, "options", obj("tabSize", 2, "insertSpaces", true))));
            assertEquals(List.of(), response(c, 10).get("result"));
            // folding and inlay hints answer (nothing multi-line, no dyn with a known type here)
            c.send(obj("jsonrpc", "2.0", "id", 11, "method", "textDocument/foldingRange", "params", obj("textDocument", doc)));
            assertEquals(List.of(), response(c, 11).get("result"));
            c.send(obj("jsonrpc", "2.0", "id", 12, "method", "textDocument/codeAction", "params", obj("textDocument", doc,
                    "range", obj("start", pos(0, 0), "end", pos(0, 0)), "context", obj("diagnostics", List.of(obj("message", "Undefined variable 'ArrayList'"))))));
            List<Object> actions = (List<Object>) response(c, 12).get("result");
            assertEquals("Import java.util.ArrayList", ((Map<String, Object>) actions.get(0)).get("title"));
        }
    }

    private static Map<String, Object> response(Client c, long id) throws InterruptedException {
        while (true) {
            Map<String, Object> m = c.next();
            if (Long.valueOf(id).equals(m.get("id"))) return m;
        }
    }

    @Test
    void aSavedPolicyAppliesToOpenScripts(@TempDir Path dir) throws Exception {
        Path server = dir.resolve("server");
        Files.createDirectories(server.resolve("scripts"));
        Path jma = Files.writeString(server.resolve("scripts.jma"), "Policy.allowPackage(\"java.util\");\n");
        String uri = server.resolve("scripts/a.jmp").toUri().toString();
        try (Client c = new Client()) {
            c.send(obj("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", obj()));
            c.next();
            c.send(notification("textDocument/didOpen", obj("textDocument", obj("uri", uri, "languageId", "jumper", "version", 1,
                    "text", "import java.io.File;\n"))));
            assertEquals(List.of("0:0-0:6 1 Access denied: java.io.File"), c.diagnostics(uri));
            // the policy opens java.io and is saved: the open script is checked again under it
            Files.writeString(jma, "Policy.allowPackage(\"java.util\");\nPolicy.allowPackage(\"java.io\");\n");
            Files.setLastModifiedTime(jma, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
            c.send(notification("textDocument/didSave", obj("textDocument", obj("uri", jma.toUri().toString()))));
            assertEquals(List.of(), c.diagnostics(uri));
        }
    }

    @Test
    void ranges() {
        String[] lines = {"dyn abc = 1 +;", "", "x"};
        assertEquals(obj("start", obj("line", 0, "character", 4), "end", obj("line", 0, "character", 7)),
                LspServer.range(lines, 1, 5, false));                 // the word
        assertEquals(obj("start", obj("line", 0, "character", 13), "end", obj("line", 0, "character", 14)),
                LspServer.range(lines, 1, 14, false));                // one character
        assertEquals(obj("start", obj("line", 2, "character", 0), "end", obj("line", 2, "character", 1)),
                LspServer.range(lines, 99, 99, false));               // past the end: the last character
        assertEquals(obj("start", obj("line", 1, "character", 0), "end", obj("line", 1, "character", 0)),
                LspServer.range(lines, 2, 1, false));                 // an empty line
    }

    @Test
    @SuppressWarnings("unchecked")
    void json() {
        Map<String, Object> v = (Map<String, Object>) Json.parse("{\"a\":[1,-2.5,true,false,null,\"x\\n\\u00e9\\\"\"],\"b\":{}}");
        List<Object> a = (List<Object>) v.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(-2.5, a.get(1));
        assertEquals(true, a.get(2));
        assertEquals(false, a.get(3));
        assertNull(a.get(4));
        assertEquals("x\n\u00e9\"", a.get(5));
        assertEquals(Map.of(), v.get("b"));
        assertEquals("{\"a\":[1,-2.5,true,false,null,\"x\\n\u00e9\\\"\"],\"b\":{}}", Json.write(v));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,"));
    }

    /** Standard output is the protocol: a message never has anything but the frame around it. */
    @Test
    void onlyFramesOnTheWire() throws Exception {
        java.io.ByteArrayOutputStream wire = new java.io.ByteArrayOutputStream();
        String init = Json.write(obj("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", obj()));
        String exit = Json.write(obj("jsonrpc", "2.0", "method", "exit"));
        String input = "Content-Length: " + init.length() + "\r\n\r\n" + init + "Content-Length: " + exit.length() + "\r\n\r\n" + exit;
        int code = new LspServer(new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), wire).run();
        assertEquals(1, code);   // exit without shutdown
        String out = wire.toString(StandardCharsets.UTF_8);
        assertTrue(out.startsWith("Content-Length: "), out);
        int body = out.indexOf("\r\n\r\n") + 4;
        assertEquals(Integer.parseInt(out.substring(16, out.indexOf("\r\n")).trim()), out.substring(body).getBytes(StandardCharsets.UTF_8).length);
    }
}
