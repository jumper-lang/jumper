package me.padej.jumper.lsp;

import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.workspace.Checker;
import me.padej.jumper.workspace.IndexCache;
import me.padej.jumper.workspace.JarIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static me.padej.jumper.lsp.Json.obj;

/**
 * Hover, completion, go-to-definition and the member warnings, on a server folder with a host (global
 * `server: srv.api.World`), the server's classes and a policy that closes World.secret.
 */
class LspFeaturesTest {

    private static Path server(Path dir) throws Exception {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        org.junit.jupiter.api.Assumptions.assumeTrue(javac != null, "needs a JDK");
        Path src = dir.resolve("src/srv/api/World.java"), out = dir.resolve("out");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package srv.api;
                public class World {
                    public int size() { return 3; }
                    public World child() { return this; }
                    public void log(String s) { }
                    public void secret() { }
                    public String getTitle() { return "t"; }
                    public static int count() { return 1; }
                    public int limit = 5;
                }
                """);
        Path events = dir.resolve("src/srv/api/Events.java");
        Files.writeString(events, """
                package srv.api;
                /** What a script may declare for the host to call. */
                public interface Events {
                    void onJoin(World world);
                    void onTick(int tick);
                    void onTick(int tick, int dt);
                    void onQuit();
                }
                """);
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), "--release", "21", "-parameters", src.toString(), events.toString()));
        Path server = dir.resolve("server");
        Files.createDirectories(server.resolve("plugins"));
        Files.createDirectories(server.resolve("scripts"));
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(server.resolve("server.jar")))) {
            z.putNextEntry(new ZipEntry("srv/api/World.class"));
            z.write(Files.readAllBytes(out.resolve("srv/api/World.class")));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("srv/api/Events.class"));
            z.write(Files.readAllBytes(out.resolve("srv/api/Events.class")));
            z.closeEntry();
        }
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(server.resolve("plugins/host.jar")))) {
            z.putNextEntry(new ZipEntry(JarIndex.DESCRIPTOR));
            z.write(("dyn scripts = \"scripts/*.jmp\"; String access = \"scripts.jma\"; dyn globals = { server: \"srv.api.World\" };"
                    + " String hooks = \"srv.api.Events\";")
                    .getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Files.writeString(server.resolve("scripts.jma"), """
                Policy.allowPackage("java.util");
                Policy.allowPackage("java.lang");
                Policy.allowClass("srv.api.World").denyMethod("secret");
                """);
        return server;
    }

    private static final String SCRIPT = """
            import java.util.HashMap;
            dyn visits = new HashMap();
            void onJoin(dyn p) {
                int n = 1;
                server.log("hi " + n);
                server.child().size();
                server.
            }
            """;

    private record Fixture(Checker checker, Features features, Path file) implements AutoCloseable {
        int at(String marker, int delta) {
            int i = features.text().text.indexOf(marker);
            assertTrue(i >= 0, marker);
            return i + delta;
        }

        List<String> labels(int offset) {
            List<String> out = new ArrayList<>();
            for (Object o : features.complete(offset, new IndexCache(null))) out.add((String) ((Map<?, ?>) o).get("label"));
            return out;
        }

        @Override
        public void close() { checker.close(); }
    }

    private final Sources noSources = new Sources(Path.of(System.getProperty("java.io.tmpdir"), "jumper-test-sources-unused"));

    private static Fixture fixture(Path dir, String text) throws Exception {
        Path server = server(dir);
        Path file = server.resolve("scripts/a.jmp");
        Checker checker = new Checker(0, 5000);
        return new Fixture(checker, new Features(checker.env(file), text), file);
    }

    @Test
    void completionOffersWhatThePolicyLetsTheScriptUse(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir, SCRIPT)) {
            List<String> members = f.labels(f.at("    server.\n", 11));
            assertTrue(members.containsAll(List.of("log", "size", "child", "getTitle", "limit", "toString")), members.toString());
            assertFalse(members.contains("secret"), "closed by the policy");
            assertFalse(members.contains("wait"), "Object's monitor methods are noise");
            assertFalse(members.contains("count"), "a static method is not offered on an instance");
            // through a chain: child() returns World
            assertTrue(f.labels(f.at("server.child().", 15)).contains("size"));
            // a variable made by `new`: its class
            assertTrue(f.labels(f.at("dyn visits", 0)).contains("visits"));   // names of the file
            List<String> names = f.labels(f.at("int n", 0));
            assertTrue(names.containsAll(List.of("visits", "onJoin", "p", "server", "HashMap", "println", "while")), names.toString());
        }
    }

    @Test
    void completionOfAClassAndOfImports(@TempDir Path dir) throws Exception {
        String text = "import srv.api.\nimport java.util.HashM\nimport java.io.\nWorld.\nimport srv.api.World;\n";
        try (Fixture f = fixture(dir, text)) {
            assertEquals(List.of("World"), f.labels(f.at("srv.api.\n", 8)));
            assertEquals(List.of("HashMap"), f.labels(f.at("HashM\n", 5)));
            assertEquals(List.of(), f.labels(f.at("java.io.\n", 8)), "java.io is closed by the policy");
            // a class: its static members
            assertEquals(List.of("count"), f.labels(f.at("World.\n", 6)));
        }
    }

    @Test
    void hoverShowsTypesSignaturesAndThePolicy(@TempDir Path dir) throws Exception {
        String text = SCRIPT + "server.secret();\n";
        try (Fixture f = fixture(dir, text)) {
            String global = f.features().hover(f.at("server.log", 2));
            assertTrue(global.contains("srv.api.World server"), global);
            String log = f.features().hover(f.at(".log", 2));
            assertTrue(log.contains("void log(String)"), log);
            String secret = f.features().hover(f.at(".secret", 3));
            assertTrue(secret.contains("void secret()") && secret.contains("closed by the access policy"), secret);
            String visits = f.features().hover(f.at("dyn visits", 5));
            assertTrue(visits.contains("dyn visits = new HashMap();") && visits.contains("java.util.HashMap"), visits);
            String cls = f.features().hover(f.at("HashMap;", 2));
            assertTrue(cls.contains("class java.util.HashMap"), cls);
            assertNull(f.features().hover(f.at("    int", 0)));
        }
    }

    @Test
    void definitionOfNamesInTheFileAndInModules(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir, SCRIPT + "onJoin(1);\nlater();\nvoid later() { }\nhelper();\nimport \"lib\";\n")) {
            Features.Location v = f.features().definition(f.at("visits = ", 2), f.file(), noSources);
            assertEquals(f.at("visits", 0), v.offset());
            Features.Location fn = f.features().definition(f.at("onJoin(1)", 1), f.file(), noSources);
            assertEquals(f.at("onJoin(dyn", 0), fn.offset());
            // a function declared further down (hoisted)
            assertEquals(f.at("later() {", 0), f.features().definition(f.at("later();", 1), f.file(), noSources).offset());
            // in an imported module
            Path lib = Files.writeString(f.file().resolveSibling("lib.jmp"), "// lib\nvoid helper() { }\n");
            Features.Location h = f.features().definition(f.at("helper();", 1), f.file(), noSources);
            assertEquals(lib.toAbsolutePath().normalize(), h.file());
            assertEquals(Files.readString(lib).indexOf("helper"), h.offset());
        }
    }

    @Test
    void definitionOfJavaClassesAndMembersOpensTheirSource(@TempDir Path dir) throws Exception {
        String text = SCRIPT + "visits.put(1, 2);\nserver.getTitle();\nserver.title;\n";
        Path cache = dir.resolve("cache");
        Sources sources = new Sources(cache);
        try (Fixture f = fixture(dir, text)) {
            // the host global: its class. server.jar has no sources: an outline from the class file
            Features.Location g = f.features().definition(f.at("server.log", 1), f.file(), sources);
            String outline = Files.readString(g.file());
            assertTrue(g.file().startsWith(cache), "under the cache, never next to the jar: " + g.file());
            assertTrue(outline.contains("The source of srv.api.World is not available (put server-sources.jar next to server.jar)"), outline);
            assertTrue(outline.startsWith("World", g.offset()) && outline.lastIndexOf("class ", g.offset()) >= 0, outline);
            assertTrue(readOnly(g.file()), "for reading");
            // a method: its declaration in the outline
            Features.Location log = f.features().definition(f.at(".log", 2), f.file(), sources);
            String logText = Files.readString(log.file());
            assertTrue(logText.startsWith("log(String", log.offset()), logText.substring(log.offset()));
            // a property: its getter
            Features.Location title = f.features().definition(f.at(".title", 2), f.file(), sources);
            assertTrue(Files.readString(title.file()).startsWith("getTitle()", title.offset()));
            // a JDK class and a member declared in it (src.zip, or an outline where the JDK has none)
            Features.Location hm = f.features().definition(f.at("HashMap;", 2), f.file(), sources);
            String hmText = Files.readString(hm.file());
            assertTrue(hmText.startsWith("HashMap", hm.offset()), hmText.substring(hm.offset(), hm.offset() + 20));
            Features.Location put = f.features().definition(f.at("visits.put", 8), f.file(), sources);
            assertEquals(hm.file(), put.file());
            assertTrue(hmText.startsWith("put(", put.offset()), hmText.substring(put.offset(), put.offset() + 20));
        }
    }

    @Test
    void aFunctionTheHostCallsGoesToTheApiMethodItImplements(@TempDir Path dir) throws Exception {
        String text = SCRIPT + "void onTick(int t, int dt) { }\nvoid onQuit(dyn extra) { }\nvoid other() { }\nonJoin(1);\ndyn onJoinCount = 0;\n";
        Sources sources = new Sources(dir.resolve("cache"));
        try (Fixture f = fixture(dir, text)) {
            // on the declaration: the interface method (server.jar has no sources - its outline)
            Features.Location j = f.features().definition(f.at("onJoin(dyn", 2), f.file(), sources);
            String src = Files.readString(j.file());
            assertTrue(src.startsWith("onJoin(World world)", j.offset()), src.substring(j.offset()));
            assertTrue(src.contains("interface Events"), src);
            // the overload with as many parameters
            Features.Location t = f.features().definition(f.at("onTick(int t", 2), f.file(), sources);
            assertTrue(Files.readString(t.file()).startsWith("onTick(int tick, int dt)", t.offset()));
            // a call of it inside the script still goes to its declaration; other functions to themselves
            assertEquals(f.at("onJoin(dyn", 0), f.features().definition(f.at("onJoin(1)", 1), f.file(), sources).offset());
            assertEquals(f.at("other()", 0), f.features().definition(f.at("other()", 1), f.file(), sources).offset());
            // hover: what it implements
            String h = f.features().hover(f.at("onJoin(dyn", 2));
            assertTrue(h.contains("Implements `Events.onJoin`") && h.contains("void onJoin(World world)"), h);
            assertFalse(f.features().hover(f.at("other()", 1)).contains("Implements"));
            assertFalse(f.features().hover(f.at("onJoinCount", 1)).contains("Implements"));
            // a declaration the host cannot call right: a warning
            var report = f.checker().check(f.file(), text);
            List<String> warnings = report.problems().stream().filter(p -> p.severity() == me.padej.jumper.workspace.Checker.Severity.WARNING)
                    .map(p -> p.line() + ":" + p.col() + " " + p.message()).toList();
            int quitLine = (int) text.substring(0, text.indexOf("void onQuit")).chars().filter(c -> c == '\n').count() + 1;
            assertEquals(List.of(quitLine + ":6 onQuit takes 1 parameter(s), but host calls it with 0 - it implements Events.onQuit()"), warnings);
        }
    }

    @Test
    void hookCompletionOffersWhatIsNotDeclaredYet(@TempDir Path dir) throws Exception {
        String text = "void onJoin(dyn w) { }\nvoid \nvoid f() {\n    dyn \n}\n";
        try (Fixture f = fixture(dir, text)) {
            List<Object> items = f.features().complete(f.at("void \n", 5), null);
            List<Object> labels = items.stream().map(o -> (Object) ((Map<?, ?>) o).get("label")).toList();
            assertEquals(List.of("onQuit", "onTick"), labels);
            Map<?, ?> tick = (Map<?, ?>) items.get(1);
            assertEquals("onTick(dyn tick)", tick.get("insertText"));
            assertEquals("Events: void onTick(int tick)", tick.get("detail"));
            // inside a function: the usual names
            List<Object> inner = f.features().complete(f.at("    dyn \n", 8), null);
            assertFalse(inner.stream().map(o -> (Object) ((Map<?, ?>) o).get("label")).toList().contains("onTick"));
        }
    }

    @Test
    void aHookParameterHasTheTypeTheHostPasses(@TempDir Path dir) throws Exception {
        // Events.onJoin(World world): `dyn w` of the script's onJoin is a World
        String text = """
                void onJoin(dyn w) {
                    w.size();
                    w.secret();
                    w.nope();
                }
                void onQuit(dyn q, dyn r) {
                    q.nope();
                }
                void other(dyn o) {
                    o.nope();
                }
                """;
        Path cache = dir.resolve("cache");
        try (Fixture f = fixture(dir, text)) {
            // completion, hover, go to definition: as for a World
            String partial = text.replace("    w.nope();", "    w.");
            Fixture g = new Fixture(f.checker(), new Features(f.checker().env(f.file()), partial), f.file());   // shares f's checker
            List<String> members = g.labels(g.at("    w.\n", 6));
            assertTrue(members.containsAll(List.of("size", "log", "child")), members.toString());
            assertFalse(members.contains("secret"), "closed by the policy");
            String hover = f.features().hover(f.at("w.size", 3));
            assertTrue(hover.contains("int size()"), hover);
            Features.Location def = f.features().definition(f.at("w.size", 3), f.file(), new Sources(cache));
            assertTrue(Files.readString(def.file()).startsWith("size(", def.offset()), def.toString());
            // the member warnings: on w (a hook's parameter) - not on q (onQuit takes none), not on o (not a hook)
            List<String> warnings = f.checker().check(f.file(), text).problems().stream()
                    .filter(p -> p.severity() == me.padej.jumper.workspace.Checker.Severity.WARNING)
                    .map(p -> p.line() + ":" + p.col() + " " + p.message()).toList();
            assertEquals(List.of(
                    "3:7 Access denied: srv.api.World.secret (closed by the access policy)",
                    "4:7 No method 'nope' in World",
                    "6:6 onQuit takes 2 parameter(s), but host calls it with 0 - it implements Events.onQuit()"), warnings);
        }
        // the function gives the parameter a value of another type: its type is known only at run time
        String reassigned = "void onJoin(dyn w) {\n    w = \"s\";\n    w.nope();\n}\n";
        try (Fixture f = fixture(dir.resolve("b"), reassigned)) {
            assertTrue(f.checker().check(f.file(), reassigned).problems().stream()
                    .noneMatch(p -> p.message().contains("nope")), "no type, no warning");
        }
    }

    @Test
    void hooksOneByOneInATable() {
        me.padej.jumper.workspace.HostDescriptor d = me.padej.jumper.workspace.HostDescriptor.parse(Path.of("s/plugins/p.jar"),
                "dyn hooks = { onStart: \"java.lang.Thread#run\", onAny: \"java.lang.String\" };");
        assertEquals(List.of(), d.problems());
        assertEquals(Map.of("onStart", "java.lang.Thread#run", "onAny", "java.lang.String"), d.hooks());
        me.padej.jumper.workspace.HostDescriptor bad = me.padej.jumper.workspace.HostDescriptor.parse(Path.of("s/plugins/p.jar"), "int hooks = 1;");
        assertTrue(bad.problems().get(0).startsWith("hooks: expected an interface name"), bad.problems().toString());
        me.padej.jumper.workspace.HostDescriptor two = me.padej.jumper.workspace.HostDescriptor.parse(Path.of("s/plugins/p.jar"),
                "dyn hooks = [\"a.B\", \"c.D\"];");
        assertEquals(List.of("a.B", "c.D"), two.hookTypes());
    }

    /** The read-only attribute itself (root may write anything, so canWrite() says nothing). */
    private static boolean readOnly(Path p) throws java.io.IOException {
        var posix = Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) return !posix.readAttributes().permissions().contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        return (Boolean) Files.getAttribute(p, "dos:readonly");
    }

    @Test
    void theJdkSourcesComeFromItsSrcZip(@TempDir Path dir) throws Exception {
        // a JDK with lib/src.zip (a fake java.home: the real one here may have none)
        Path home = dir.resolve("jdk");
        Files.createDirectories(home.resolve("lib"));
        String src = "package java.util;\n// from src.zip\npublic class HashMap<K,V> extends AbstractMap<K,V> {\n"
                + "    public V put(K key, V value) { return null; }\n    public V put(K key) { return null; }\n}\n";
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(home.resolve("lib/src.zip")))) {
            z.putNextEntry(new ZipEntry("java.base/java/util/HashMap.java"));
            z.write(src.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        String saved = System.getProperty("java.home");
        System.setProperty("java.home", home.toString());
        try {
            Sources.Place put = new Sources(dir.resolve("cache")).locate(java.util.HashMap.class, "put", 2);
            assertEquals(src, Files.readString(put.file()));
            assertEquals(src.indexOf("put(K key, V value)"), put.offset());   // the overload with two parameters
            Sources.Place one = new Sources(dir.resolve("cache")).locate(java.util.HashMap.class, "put", 1);
            assertEquals(src.indexOf("put(K key)"), one.offset());
        } finally {
            System.setProperty("java.home", saved);
        }
    }

    @Test
    void aSourcesJarNextToTheJarIsUsed(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir, SCRIPT)) {
            Path server = f.file().getParent().getParent();
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(server.resolve("server-sources.jar")))) {
                z.putNextEntry(new ZipEntry("srv/api/World.java"));
                z.write(Files.readAllBytes(dir.resolve("src/srv/api/World.java")));
                z.closeEntry();
            }
            Sources sources = new Sources(dir.resolve("cache"));
            Features.Location log = f.features().definition(f.at(".log", 2), f.file(), sources);
            String src = Files.readString(log.file());
            assertEquals(Files.readString(dir.resolve("src/srv/api/World.java")), src);
            assertTrue(src.startsWith("log(String s)", log.offset()), src.substring(log.offset()));
        }
    }

    @Test
    void callsThatWillFailAreWarnings(@TempDir Path dir) throws Exception {
        Path server = server(dir);
        Path file = server.resolve("scripts/a.jmp");
        String text = """
                server.log("ok");
                server.nope();
                server.log();
                server.secret();
                server.child().size();
                server.child().zzz();
                Math.max(1, 2);
                Math.nope();
                new java.util.HashMap().zzz();
                dyn d = server; d.nope();
                """;
        try (Checker checker = new Checker(0, 5000)) {
            Checker.Report r = Interpreter.runWithBigStack(() -> checker.check(file, text));
            List<String> got = new ArrayList<>();
            for (Checker.Problem p : r.problems()) got.add(p.line() + ":" + p.col() + " " + p.severity() + " " + p.message());
            assertEquals(List.of(
                    "2:8 WARNING No method 'nope' in World",
                    "3:8 WARNING World.log takes 1 argument, not 0",
                    "4:8 WARNING Access denied: srv.api.World.secret (closed by the access policy)",
                    "6:16 WARNING No method 'zzz' in World",
                    "8:6 WARNING No static method 'nope' in Math",
                    "9:25 WARNING No method 'zzz' in HashMap",
                    "10:19 WARNING No method 'nope' in World"), got);
            assertEquals(0, r.errors(), "warnings do not fail a check");
        }
    }

    @Test
    void theTypeOfAVariableFollowsWhatIsAssignedToIt(@TempDir Path dir) throws Exception {
        Path file = server(dir).resolve("scripts/a.jmp");
        String text = """
                import java.util.Random;
                import srv.api.World;
                dyn w = server.child();
                w.nope();
                dyn a = w;
                a.size(1);
                dyn later = null;
                later = server.child();
                later.zzz();
                dyn mixed = server.child();
                mixed = 5;
                mixed.whatever();
                Random r = new Random();
                r.nextInt(1, 2, 3);
                String s = "x";
                s.nope();
                void f(World q, dyn d) {
                    q.nope();
                    d.whatever();
                    w.inner();
                }
                dyn lam = () -> { w.inLambda(); };
                for (dyn p : [1]) { p.whatever(); }
                """;
        try (Checker checker = new Checker(0, 5000)) {
            Checker.Report r = Interpreter.runWithBigStack(() -> checker.check(file, text));
            List<String> got = new ArrayList<>();
            for (Checker.Problem p : r.problems()) if (p.severity() == Checker.Severity.WARNING) got.add(p.line() + ":" + p.col() + " " + p.message());
            assertEquals(List.of(
                    "4:3 No method 'nope' in World",
                    "6:3 World.size takes 0 arguments, not 1",
                    "9:7 No method 'zzz' in World",
                    "14:3 Random.nextInt takes 0 or 1 or 2 arguments, not 3",
                    "16:3 No method 'nope' in String",
                    "18:7 No method 'nope' in World",
                    "20:7 No method 'inner' in World",
                    "22:21 No method 'inLambda' in World"), got);
        }
        // the editor side: completion and hover on such a variable
        String typing = "dyn w = server.child();\ndyn a = w;\na.\ndyn b = new java.util.HashMap();\nb.\n";
        try (Fixture f = fixture(dir.resolve("2"), typing)) {
            assertTrue(f.labels(f.at("a.\n", 2)).containsAll(List.of("size", "child", "log")), f.labels(f.at("a.\n", 2)).toString());
            assertTrue(f.labels(f.at("b.\n", 2)).contains("containsKey"));
            String h = f.features().hover(f.at("w =", 0));
            assertTrue(h.contains("Type: `srv.api.World`"), h);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> sigs(Map<String, Object> help) {
        List<String> out = new ArrayList<>();
        if (help == null) return out;
        int active = (Integer) help.get("activeSignature"), param = (Integer) help.get("activeParameter");
        List<Object> ss = (List<Object>) help.get("signatures");
        for (int k = 0; k < ss.size(); k++) {
            Map<String, Object> sg = (Map<String, Object>) ss.get(k);
            String label = (String) sg.get("label");
            List<Object> ps = (List<Object>) sg.get("parameters");
            String cur = "";
            if (param < ps.size()) {
                List<Integer> r = (List<Integer>) ((Map<String, Object>) ps.get(param)).get("label");
                cur = label.substring(r.get(0), r.get(1));
            }
            out.add((k == active ? "*" : "") + label + (cur.isEmpty() ? "" : " [" + cur + "]"));
        }
        return out;
    }

    @Test
    void signatureHelpWhileTypingArguments(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("server/scripts"));
        Files.writeString(dir.resolve("server/scripts/lib.jmp"), "String greet(String who, int times) { return who; }\n");
        String text = """
                import "lib";
                int add(int a, int b) { return a + b; }
                class Point { int x; Point(int x, int y) { this.x = x; } }
                server.log(
                Math.max(1, 
                new java.util.Random(
                add(1, add(2, 
                range(0, 
                greet("a", 
                new Point(1, 
                dyn done = 1;
                """;
        try (Fixture f = fixture(dir, text)) {
            java.util.function.Function<String, List<String>> at = m -> {
                Features ft = f.features();
                ft.file = f.file();
                return sigs(ft.signatureHelp(f.at(m, m.length())));
            };
            assertEquals(List.of("*void log(String s) [String s]"), at.apply("server.log("));
            List<String> max = at.apply("Math.max(1,");
            assertEquals(4, max.size(), max.toString());
            assertTrue(max.stream().filter(x -> x.startsWith("*")).count() == 1 && max.stream().allMatch(x -> x.matches("\\*?\\w+ max\\((\\w+), \\1\\) \\[\\1\\]")), max.toString());
            assertEquals(List.of("Random()", "*Random(long) [long]"), at.apply("new java.util.Random("));
            assertEquals(List.of("*int add(int a, int b) [int b]"), at.apply("add(1, add(2,"), "the innermost call");
            assertEquals(List.of("dyn range(int n)", "*dyn range(int from, int to) [int to]", "dyn range(int from, int to, int step) [int to]"),
                    at.apply("range(0,"));
            assertEquals(List.of("*String greet(String who, int times) [int times]"), at.apply("greet(\"a\","), "from the imported module");
            assertEquals(List.of("*Point(int x, int y) [int y]"), at.apply("new Point(1,"));
            assertEquals(List.of(), sigs(f.features().signatureHelp(f.at("return a", 3))), "not in a call");
        }
    }

    private static Features featuresFor(Checker checker, Path file, String text) {
        Features f = new Features(checker.env(file), text);
        f.file = file;
        return f;
    }

    @Test
    void policyAndConfigFiles(@TempDir Path dir) throws Exception {
        Path server = server(dir);
        try (Checker checker = new Checker(0, 5000)) {
            // .jma: `Policy.` is the policy API; the strings name packages and classes of the server and the JDK
            String jma = "Policy.\nPolicy.allowPackage(\"java.ut\");\nPolicy.allowClass(\"srv.api.W\");\nPolicy.allowClass(\"srv.api.World\").\n";
            Features p = featuresFor(checker, server.resolve("scripts.jma"), jma);
            List<String> api = labels(p.complete(jma.indexOf("Policy.\n") + 7, new IndexCache(dir.resolve("idx"))));
            assertTrue(api.containsAll(List.of("allowPackage", "denyClass", "allowModules", "maxTableSize")), api.toString());
            assertEquals(List.of("util"), labels(p.complete(jma.indexOf("java.ut") + 7, new IndexCache(dir.resolve("idx")))));
            assertEquals(List.of("World"), labels(p.complete(jma.indexOf("srv.api.W") + 9, new IndexCache(dir.resolve("idx")))));
            List<String> rule = labels(p.complete(jma.indexOf("\").\n") + 3, new IndexCache(dir.resolve("idx"))));
            assertTrue(rule.containsAll(List.of("denyMethod", "allowField")), rule.toString());
            // .jmc: only what a config may use; hover shows a key's value
            String jmc = "int port = 25565;\nString motd = \"hi \" + port;\ndyn t = { a: 1 };\n";
            Features c = featuresFor(checker, server.resolve("config.jmc"), jmc + "w");
            List<String> kw = labels(c.complete(jmc.length() + 1, new IndexCache(dir.resolve("idx"))));
            assertTrue(kw.contains("if") && kw.contains("port") && !kw.contains("while") && !kw.contains("class") && !kw.contains("import"), kw.toString());
            Features ok = featuresFor(checker, server.resolve("config.jmc"), jmc);
            assertTrue(ok.hover(jmc.indexOf("motd") + 1).contains("Value: `\"hi 25565\"`"), ok.hover(jmc.indexOf("motd") + 1));
            assertTrue(ok.hover(jmc.indexOf("port") + 1).contains("Value: `25565`"));
        }
    }

    private static List<String> labels(List<Object> items) {
        List<String> out = new ArrayList<>();
        for (Object o : items) out.add(String.valueOf(((Map<?, ?>) o).get("label")));
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void quickFixImportsAnUnknownClass(@TempDir Path dir) throws Exception {
        Path server = server(dir);
        String text = "// a script\nimport java.util.HashMap;\n\nRandom r = null;\nWorld w = null;\nLinkedList l;\n";
        try (Checker checker = new Checker(0, 5000)) {
            Features f = featuresFor(checker, server.resolve("scripts/a.jmp"), text);
            IndexCache cache = new IndexCache(dir.resolve("idx"));
            List<Map<String, Object>> diags = List.of(
                    obj("message", "Undefined variable 'Random'"), obj("message", "Undefined variable 'World'"),
                    obj("message", "Undefined variable 'LinkedList'"), obj("message", "Unexpected token ';'"));
            List<String> got = new ArrayList<>();
            for (Object o : f.codeActions("file:///a.jmp", diags, cache)) {
                Map<String, Object> a = (Map<String, Object>) o;
                List<Object> edits = (List<Object>) ((Map<String, Object>) ((Map<String, Object>) a.get("edit")).get("changes")).get("file:///a.jmp");
                Map<String, Object> e = (Map<String, Object>) edits.get(0);
                got.add(a.get("title") + " @" + ((Map<String, Object>) ((Map<String, Object>) e.get("range")).get("start")).get("line") + " " + e.get("newText").toString().strip());
            }
            // after the last import; the server's class too; a class the policy closes (java.util is open, not java.lang.Thread...) - none here
            assertEquals(List.of("Import java.util.Random @2 import java.util.Random;", "Import srv.api.World @2 import srv.api.World;",
                    "Import java.util.LinkedList @2 import java.util.LinkedList;"), got);
        }
    }

    @Test
    void formattingFoldingAndInlayHints(@TempDir Path dir) throws Exception {
        Path server = server(dir);
        String text = "import java.util.HashMap;\nimport java.util.Random;\n/* two\n   lines */\nvoid f(dyn p) {\nif (p) {\n  dyn t = {\na: 1,\n    b: \"{\"   \n};\n}\n}\ndyn w = server.child();\ndyn m = new HashMap();\ndyn n = 1;\n";
        try (Checker checker = new Checker(0, 5000)) {
            Features f = featuresFor(checker, server.resolve("scripts/a.jmp"), text);
            assertEquals("import java.util.HashMap;\nimport java.util.Random;\n/* two\n   lines */\nvoid f(dyn p) {\n    if (p) {\n        dyn t = {\n"
                    + "            a: 1,\n            b: \"{\"\n        };\n    }\n}\ndyn w = server.child();\ndyn m = new HashMap();\ndyn n = 1;\n", f.formatted("    "));
            assertEquals(f.formatted("    "), featuresFor(checker, server.resolve("scripts/a.jmp"), f.formatted("    ")).formatted("    "), "formatting twice changes nothing");
            List<String> folds = new ArrayList<>();
            for (Object o : f.foldingRanges()) {
                Map<?, ?> m = (Map<?, ?>) o;
                folds.add(m.get("startLine") + "-" + m.get("endLine") + (m.get("kind") == null ? "" : " " + m.get("kind")));
            }
            assertEquals(List.of("6-8", "5-9", "4-10", "0-1 imports", "2-3 comment"), folds);
            List<String> hints = new ArrayList<>();
            for (Object o : f.inlayHints(0, 100)) {
                Map<?, ?> m = (Map<?, ?>) o;
                hints.add(((Map<?, ?>) m.get("position")).get("line") + ":" + ((Map<?, ?>) m.get("position")).get("character") + m.get("label"));
            }
            assertEquals(List.of("12:5: World", "13:5: HashMap"), hints);
        }
    }
}
