package me.padej.jumper;

import me.padej.jumper.workspace.ClassFiles;
import me.padej.jumper.workspace.ClassInfo;
import me.padej.jumper.workspace.FileContext;
import me.padej.jumper.workspace.HostDescriptor;
import me.padej.jumper.workspace.IndexCache;
import me.padej.jumper.workspace.JarIndex;
import me.padej.jumper.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How tools find the context of a Jumper file in a server folder (workspace package): host descriptors in
 * plugin jars, the convention without them, the class index and its cache, and `jmp --check` using all of it.
 */
class WorkspaceTest {

    // ------------------------------------------------------------------ building a server folder

    /** A jar with the given entries (name -> bytes). */
    private static Path jar(Path file, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return file;
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    /** Compiles Java sources (class name -> source) and returns their class files (entry name -> bytes). */
    private static Map<String, byte[]> compile(Path work, Map<String, String> sources) throws Exception {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        org.junit.jupiter.api.Assumptions.assumeTrue(javac != null, "needs a JDK");
        Path src = work.resolve("src"), out = work.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "21"));
        for (var e : sources.entrySet()) args.add(write(src.resolve(e.getKey().replace('.', '/') + ".java"), e.getValue()).toString());
        assertEquals(0, javac.run(null, null, null, args.toArray(new String[0])));
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (var s = Files.walk(out)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".class")).toList())
                classes.put(out.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        return classes;
    }

    private static final String HOST = """
            String name = "Jumper";
            dyn scripts = ["scripts/*.jmp"];
            String access = "scripts.jma";
            dyn configs = ["config.jmc"];
            dyn globals = { server: "srv.api.World" };
            """;

    /** server/{server.jar (srv.api.World), plugins/host.jar (descriptor), scripts.jma, config.jmc, scripts/a.jmp}. */
    private static Path server(Path dir, boolean withHost) throws Exception {
        Path server = dir.resolve("server");
        jar(server.resolve("server.jar"), compile(dir.resolve("javac"), Map.of("srv.api.World", """
                package srv.api;
                public class World {
                    public static int size() { return 3; }
                    public void log(String s) { }
                    private int hidden;
                }
                """)));
        if (withHost) jar(server.resolve("plugins/host.jar"), Map.of(JarIndex.DESCRIPTOR, utf8(HOST)));
        write(server.resolve("scripts.jma"), "Policy.allowPackage(\"srv.api\");\nPolicy.allowPackage(\"java.util\");\n");
        write(server.resolve("config.jmc"), "int port = 25565;\n");
        write(server.resolve("scripts/a.jmp"), "import srv.api.World;\nserver.log(\"size \" + World.size());\n");
        return server;
    }

    // ------------------------------------------------------------------ host descriptors

    @Test
    void aHostDescriptorClaimsItsScriptsConfigsAndPolicy(@TempDir Path dir) throws Exception {
        Path server = server(dir, true).toRealPath();
        FileContext s = Workspace.contextFor(server.resolve("scripts/a.jmp"));
        assertEquals(FileContext.Kind.SCRIPT, s.kind());
        assertEquals(server, s.root());
        assertEquals("Jumper", s.host().name());
        assertEquals(server.resolve("scripts.jma"), s.access());
        assertEquals(Map.of("server", "srv.api.World"), s.globals());
        assertEquals(List.of(server.resolve("plugins/host.jar"), server.resolve("server.jar")), s.classpath());
        assertEquals(List.of(), s.notes());

        FileContext c = Workspace.contextFor(server.resolve("config.jmc"));
        assertEquals(FileContext.Kind.CONFIG, c.kind());
        assertEquals("Jumper", c.host().name());
        assertNull(c.access());
        assertEquals(Map.of(), c.globals());

        FileContext p = Workspace.contextFor(server.resolve("scripts.jma"));
        assertEquals(FileContext.Kind.POLICY, p.kind());
        assertEquals("Jumper", p.host().name());

        // a file the host does not claim: found by convention, not by the host
        FileContext other = Workspace.contextFor(write(server.resolve("scripts/deep/x.jmp"), "return 1;"));
        assertNull(other.host());
        assertEquals(server.resolve("scripts.jma"), other.access());
    }

    @Test
    void twoHostsShareOneServer(@TempDir Path dir) throws Exception {
        Path server = dir.resolve("server");
        jar(server.resolve("plugins/alpha.jar"), Map.of(JarIndex.DESCRIPTOR, utf8("""
                dyn scripts = "scripts/alpha/**.jmp"; String access = "alpha.jma"; dyn globals = { alpha: "dyn" };
                """), "alpha/Api.class", new byte[0]));
        jar(server.resolve("plugins/beta.jar"), Map.of(JarIndex.DESCRIPTOR, utf8("""
                dyn scripts = ["scripts/beta/*.jmp", "beta-extra/*.jmp"]; String access = "beta.jma"; dyn globals = { beta: "dyn" };
                """)));
        // a third host claims every script - the more specific pattern of the other two wins
        jar(server.resolve("plugins/greedy.jar"), Map.of(JarIndex.DESCRIPTOR, utf8("dyn scripts = \"scripts/**.jmp\";")));
        write(server.resolve("alpha.jma"), "");
        write(server.resolve("beta.jma"), "");
        server = server.toRealPath();

        FileContext a = Workspace.contextFor(write(server.resolve("scripts/alpha/sub/x.jmp"), ""));
        assertEquals("alpha", a.host().name());
        assertEquals(server.resolve("alpha.jma"), a.access());
        assertEquals(Map.of("alpha", "dyn"), a.globals());
        // one plugin's scripts do not see another plugin's classes
        assertEquals(List.of(server.resolve("plugins/alpha.jar")), a.classpath());
        assertTrue(a.notes().stream().anyMatch(n -> n.contains("claimed by two hosts")), a.notes().toString());

        FileContext b = Workspace.contextFor(write(server.resolve("beta-extra/y.jmp"), ""));
        assertEquals("beta", b.host().name());
        assertEquals(server.resolve("beta.jma"), b.access());

        FileContext g = Workspace.contextFor(write(server.resolve("scripts/misc/z.jmp"), ""));
        assertEquals("greedy", g.host().name());
        assertNull(g.access());
    }

    @Test
    void withoutDescriptorsThePolicyIsFoundByConvention(@TempDir Path dir) throws Exception {
        Path server = dir.resolve("server");
        write(server.resolve("alpha.jma"), "");
        write(server.resolve("beta.jma"), "");
        server = server.toRealPath();
        // scripts/<name>/... -> <name>.jma
        assertEquals(server.resolve("alpha.jma"), Workspace.contextFor(write(server.resolve("scripts/alpha/x.jmp"), "")).access());
        assertEquals(server.resolve("beta.jma"), Workspace.contextFor(write(server.resolve("scripts/beta/deep/y.jmp"), "")).access());
        // no folder named like a policy: ambiguous - none, and a note says why
        FileContext z = Workspace.contextFor(write(server.resolve("scripts/z.jmp"), ""));
        assertNull(z.access());
        assertTrue(z.notes().get(0).contains("several policies"), z.notes().toString());
        // a single policy up the tree is the one
        Path solo = dir.resolve("solo");
        write(solo.resolve("only.jma"), "");
        assertEquals(solo.toRealPath().resolve("only.jma"), Workspace.contextFor(write(solo.resolve("a/b/c.jmp"), "")).access());
    }

    @Test
    void aBrokenDescriptorIsANoteNotAFailure(@TempDir Path dir) throws Exception {
        Path server = dir.resolve("server");
        jar(server.resolve("plugins/bad.jar"), Map.of(JarIndex.DESCRIPTOR, utf8("dyn scripts = 5; String acess = \"x.jma\"; dyn globals = 1;")));
        jar(server.resolve("plugins/worse.jar"), Map.of(JarIndex.DESCRIPTOR, utf8("dyn scripts = [;")));
        FileContext c = Workspace.contextFor(write(server.resolve("scripts/a.jmp"), ""));
        String notes = String.join("\n", c.notes());
        assertTrue(notes.contains("scripts: expected a string or an array of strings"), notes);
        assertTrue(notes.contains("unknown key 'acess'"), notes);
        assertTrue(notes.contains("globals: expected a table"), notes);
        assertTrue(notes.contains("worse (worse.jar): " + JarIndex.DESCRIPTOR), notes);

        HostDescriptor d = HostDescriptor.parse(dir.resolve("x/z/y.jar"), "String root = \"../..\";");
        assertEquals(dir.toAbsolutePath().normalize(), d.root());
        assertEquals("y", d.name());
    }

    // ------------------------------------------------------------------ class files and the index

    @Test
    void classFilesReadLikeReflectionSeesThem() throws Exception {
        for (Class<?> c : List.of(String.class, java.util.HashMap.class, java.util.concurrent.ConcurrentHashMap.class,
                java.util.concurrent.TimeUnit.class, java.lang.invoke.MethodHandles.class, Thread.class,
                me.padej.jumper.parser.Parser.class, me.padej.jumper.workspace.FileContext.class)) {
            byte[] bytes;
            try (var in = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class")) { bytes = in.readAllBytes(); }
            ClassInfo ci = ClassFiles.read(bytes);
            assertEquals(c.getName(), ci.name());
            assertEquals(c.getSuperclass() == null ? null : c.getSuperclass().getName(), ci.superName());
            Set<String> methods = new TreeSet<>(), reflected = new TreeSet<>();
            for (ClassInfo.Member m : ci.methods()) if (!m.name().startsWith("<")) methods.add(m.name() + m.descriptor());
            for (var m : c.getDeclaredMethods())
                reflected.add(m.getName() + java.lang.invoke.MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString());
            assertEquals(reflected, methods, c.getName());
            Set<String> fields = new TreeSet<>(), rf = new TreeSet<>();
            for (ClassInfo.Member f : ci.fields()) fields.add(f.name() + f.descriptor());
            for (var f : c.getDeclaredFields()) rf.add(f.getName() + f.getType().descriptorString());
            assertEquals(rf, fields, c.getName());
        }
        assertThrows(java.io.IOException.class, () -> ClassFiles.read(new byte[] {1, 2, 3, 4}));
        assertThrows(java.io.IOException.class, () -> ClassFiles.read(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0}));
    }

    @Test
    void aJarIndexHasItsClassesAndItsDescriptor(@TempDir Path dir) throws Exception {
        Path server = server(dir, true);
        JarIndex api = JarIndex.build(server.resolve("server.jar"));
        ClassInfo world = api.find("srv.api.World");
        assertNotNull(world);
        assertTrue(world.isPublic());
        assertEquals("World", world.simpleName());
        assertEquals("srv.api", world.packageName());
        assertTrue(world.methods().contains(new ClassInfo.Member("size", "()I", ClassInfo.ACC_PUBLIC | ClassInfo.ACC_STATIC)));
        assertTrue(world.methods().contains(new ClassInfo.Member("log", "(Ljava/lang/String;)V", ClassInfo.ACC_PUBLIC)));
        assertEquals(List.of(new ClassInfo.Member("hidden", "I", ClassInfo.ACC_PRIVATE)), world.fields());
        assertNull(api.descriptor());

        JarIndex host = JarIndex.build(server.resolve("plugins/host.jar"));
        assertEquals(HOST, host.descriptor());
        assertTrue(host.classes().isEmpty());

        // a broken class file is counted, not fatal
        JarIndex broken = JarIndex.build(jar(dir.resolve("broken.jar"), Map.of("a/B.class", new byte[] {1, 2, 3}, "a/C.class", new byte[0])));
        assertEquals(2, broken.unreadable());
    }

    @Test
    void theCacheRebuildsOnlyWhatChanged(@TempDir Path dir) throws Exception {
        Path server = server(dir, true);
        Path cacheDir = dir.resolve("cache");
        Path serverJar = server.resolve("server.jar"), hostJar = server.resolve("plugins/host.jar");

        IndexCache c1 = new IndexCache(cacheDir);
        JarIndex first = c1.get(serverJar);
        c1.get(hostJar);
        assertSame(first, c1.get(serverJar));   // the same session: from memory
        assertEquals(2, c1.builds());
        assertEquals(1, c1.hits());

        // a new session (the editor restarted): everything from the cache directory, nothing read anew
        IndexCache c2 = new IndexCache(cacheDir);
        JarIndex again = c2.get(serverJar);
        c2.get(hostJar);
        assertEquals(0, c2.builds());
        assertEquals(first.classes(), again.classes());

        // the plugin is rebuilt: only its entry is read anew
        jar(hostJar, Map.of(JarIndex.DESCRIPTOR, utf8(HOST + "// v2\n")));
        Files.setLastModifiedTime(hostJar, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        IndexCache c3 = new IndexCache(cacheDir);
        assertTrue(c3.get(hostJar).descriptor().endsWith("// v2\n"));
        c3.get(serverJar);
        assertEquals(1, c3.builds());
        assertEquals(1, c3.hits());

        // a damaged cache entry is rebuilt, not an error
        try (var s = Files.list(cacheDir)) {
            for (Path p : s.toList()) Files.write(p, new byte[] {0, 0, 0, 7, 1});
        }
        IndexCache c4 = new IndexCache(cacheDir);
        assertEquals(first.classes(), c4.get(serverJar).classes());
        assertEquals(1, c4.builds());

        // without a directory: memory only
        IndexCache mem = new IndexCache(null);
        mem.get(serverJar);
        mem.get(serverJar);
        assertEquals(1, mem.builds());

        // the context gives the index of its classpath
        FileContext ctx = Workspace.contextFor(server.resolve("scripts/a.jmp"));
        List<JarIndex> idx = ctx.index(new IndexCache(cacheDir));
        assertTrue(idx.stream().anyMatch(j -> j.find("srv.api.World") != null));
    }

    // ------------------------------------------------------------------ jmp --check in context

    private static List<String> check(String... files) {
        java.io.PrintStream out = System.out, err = System.err;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buf, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            Main.check(files);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String s = buf.toString(StandardCharsets.UTF_8);
        return s.isEmpty() ? List.of() : List.of(s.split("\\R"));
    }

    @Test
    void checkSeesTheScriptTheWayItsHostRunsIt(@TempDir Path dir) throws Exception {
        Path server = server(dir, true);
        String a = server.resolve("scripts/a.jmp").toString();
        // the host's global and the server's class resolve, under the host's policy
        assertEquals(List.of(), check(a));
        // what the policy closes is an error already when checked
        Path denied = write(server.resolve("scripts/denied.jmp"), "import java.io.File;\nserver.log(\"x\");\n");
        List<String> d = check(denied.toString());
        assertEquals(1, d.size(), d.toString());
        assertTrue(d.get(0).startsWith(denied + ":1:1: error: Access denied"), d.toString());
        // the config and the policy are checked as what they are
        assertEquals(List.of(), check(server.resolve("config.jmc").toString(), server.resolve("scripts.jma").toString()));
        Path badPolicy = write(server.resolve("scripts.jma"), "Policy.allowPackage(\"srv.api\"\n");
        List<String> p = check(badPolicy.toString());
        assertTrue(p.get(0).startsWith(badPolicy + ":2:1: error: "), p.toString());
        // ... and a script whose policy is broken is an error: its host would not load it
        List<String> s = check(a);
        assertTrue(s.get(0).startsWith(a + ":1:1: error: its access policy cannot be loaded"), s.toString());
    }

    @Test
    void checkReadsAnUnsavedBufferFromStdin(@TempDir Path dir) throws Exception {
        Path server = server(dir, true);
        Path a = server.resolve("scripts/a.jmp");
        String buffer = "import java.io.File;\nserver.log(\"x\");\ndyn y = 1 +;\n";   // not on disk
        java.io.PrintStream out = System.out, err = System.err;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buf, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        int code;
        try {
            code = Main.check(new String[] {"--stdin", a.toString()}, new java.io.ByteArrayInputStream(utf8(buffer)));
            // the file on disk is fine; the buffer is checked in the file's context (its policy, `server`)
            assertEquals(2, Main.check(new String[] {"--stdin"}, java.io.InputStream.nullInputStream()));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        assertEquals(1, code);
        assertEquals(List.of(a + ":1:1: error: Access denied: java.io.File", a + ":3:12: error: Unexpected token ';'"),
                List.of(buf.toString(StandardCharsets.UTF_8).split("\\R")));
        // a new file that is not saved yet: its context comes from where it will be
        Path fresh = server.resolve("scripts/new.jmp");
        System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            assertEquals(0, Main.check(new String[] {"--stdin", fresh.toString()}, new java.io.ByteArrayInputStream(utf8("server.log(\"hi\");"))));
        } finally {
            System.setOut(out);
        }
        assertFalse(Files.exists(fresh));
    }

    @Test
    void anImportedModuleIsParsedNotRun(@TempDir Path dir) throws Exception {
        // a module that prints and never finishes: a check of a file importing it neither prints nor hangs
        write(dir.resolve("p/loop.jmp"), "println(\"MODULE RAN\");\nint helper(int a) { return a + 1; }\nint n = 0;\nwhile (true) { n++; }\n");
        Path a = write(dir.resolve("p/a.jmp"), "import \"loop.jmp\";\nint r = helper(1);\nnope();\ndyn x = 1 +;\n");
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(printed, true, java.nio.charset.StandardCharsets.UTF_8));
        try (me.padej.jumper.workspace.Checker checker = new me.padej.jumper.workspace.Checker(0, 500)) {
            long t0 = System.nanoTime();
            var r = me.padej.jumper.interp.Interpreter.runWithBigStack(() -> checker.check(a, Files.readString(a)));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 400, "took " + ms + " ms - nothing waited for the watchdog");
            // the module's function is known; the file's own errors are all there; nothing about the import
            assertEquals(List.of("3:1 Undefined variable 'nope'", "4:12 Unexpected token ';'"), r.problems().stream()
                    .filter(p -> p.severity() != me.padej.jumper.workspace.Checker.Severity.NOTE)
                    .map(p -> p.line() + ":" + p.col() + " " + p.message()).toList());
        } finally {
            System.setOut(out);
        }
        assertEquals("", printed.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void checkWithoutAHostSaysWhatItDidNotKnow(@TempDir Path dir) throws Exception {
        Path server = server(dir, false);
        String a = server.resolve("scripts/a.jmp").toString();
        List<String> out = check(a);
        // the policy is found by convention and the server's classes are there, but nobody declared `server`
        assertEquals(List.of(a + ":2:1: error: Undefined variable 'server'"), out, out.toString());
    }

    @Test
    void checkOfAPolicyFileSeesOnlyPolicy(@TempDir Path dir) throws Exception {
        Path jma = write(dir.resolve("p/x.jma"), "import java.io.File;\nPolicy.allowPackage(\"java.util\");\n");
        List<String> out = check(jma.toString());
        assertTrue(out.stream().anyMatch(l -> l.startsWith(jma + ":1:1: error: Access denied")), out.toString());
    }
}
