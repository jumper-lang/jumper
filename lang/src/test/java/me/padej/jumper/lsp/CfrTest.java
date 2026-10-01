package me.padej.jumper.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decompiling with CFR when there is no source. The real cfr.jar is not needed here: a stand-in with CFR's
 * command line (`org.benf.cfr.reader.Main <jar> --outputdir <dir> ...`) writes what CFR would write, and
 * records what it was given (the entries of the jar, the arguments).
 */
class CfrTest {
    private static final String FAKE_CFR = """
            package org.benf.cfr.reader;
            import java.nio.file.*; import java.util.*; import java.util.zip.*;
            public class Main {
                public static void main(String[] a) throws Exception {
                    Path jar = Path.of(a[0]), out = Path.of(a[Arrays.asList(a).indexOf("--outputdir") + 1]);
                    List<String> entries = new ArrayList<>();
                    try (ZipFile z = new ZipFile(jar.toFile())) { z.stream().forEach(e -> entries.add(e.getName())); }
                    Collections.sort(entries);
                    for (String n : entries) {
                        if (n.contains("$")) continue;
                        String cls = n.substring(0, n.length() - 6), simple = cls.substring(cls.lastIndexOf('/') + 1);
                        Path f = out.resolve(cls + ".java");
                        Files.createDirectories(f.getParent());
                        Files.writeString(f, "/* Decompiled with CFR (stand-in). */\\npublic class " + simple + " {\\n"
                                + "    public void log(String message) {\\n        System.out.println(message);\\n    }\\n"
                                + "    // entries: " + entries + "\\n    // args: " + String.join(" ", a) + "\\n}\\n");
                    }
                }
            }
            """;

    /** Compiles the stand-in into a jar. */
    private static Path fakeCfr(Path dir) throws Exception {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        org.junit.jupiter.api.Assumptions.assumeTrue(javac != null, "needs a JDK");
        Path src = dir.resolve("cfrsrc/org/benf/cfr/reader/Main.java"), out = dir.resolve("cfrout");
        Files.createDirectories(src.getParent());
        Files.writeString(src, FAKE_CFR);
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), "--release", "21", src.toString()));
        Path jar = dir.resolve("lib/cfr-0.152.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("org/benf/cfr/reader/Main.class"));
            z.write(Files.readAllBytes(out.resolve("org/benf/cfr/reader/Main.class")));
            z.closeEntry();
        }
        return jar;
    }

    /**
     * A server jar with srv/api/World (and a nested class), and a loader over it. Close the loader: on Windows a
     * jar a loader holds open cannot be deleted, and the test's @TempDir would fail to clean up.
     */
    private static java.net.URLClassLoader server(Path dir) throws Exception {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        Path src = dir.resolve("src/srv/api/World.java"), out = dir.resolve("out");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package srv.api;\npublic class World {\n  public void log(String s) { }\n  public static class Inner { }\n}\n");
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), "--release", "21", src.toString()));
        Path jar = dir.resolve("server/server.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String n : List.of("srv/api/World.class", "srv/api/World$Inner.class")) {
                z.putNextEntry(new ZipEntry(n));
                z.write(Files.readAllBytes(out.resolve(n)));
                z.closeEntry();
            }
        }
        return new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, CfrTest.class.getClassLoader());
    }

    /** srv.api.World, loaded without initializing. */
    private static Class<?> world(ClassLoader server) throws Exception {
        return Class.forName("srv.api.World", false, server);
    }

    @Test
    void withoutASourceTheClassIsDecompiled(@TempDir Path dir) throws Exception {
        Path cfr = fakeCfr(dir);
        try (java.net.URLClassLoader server = server(dir)) {
            Class<?> world = world(server);
            Sources.Place log = new Sources(dir.resolve("cache"), cfr).locate(world, "log", 1);
            String text = Files.readString(log.file());
            assertTrue(log.file().toString().contains("decompiled"), log.file().toString());
            assertTrue(text.startsWith("// The source of srv.api.World is not available: decompiled by CFR"), text);
            assertTrue(text.startsWith("log(String message)", log.offset()), text.substring(log.offset()));
            // the nested class went with it, and CFR got the jar for the types of the signatures
            assertTrue(text.contains("srv/api/World$Inner.class"), text);
            assertTrue(text.contains("--extraclasspath " + dir.resolve("server/server.jar")), text);
            // a real source still wins
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dir.resolve("server/server-sources.jar")))) {
                z.putNextEntry(new ZipEntry("srv/api/World.java"));
                z.write(Files.readAllBytes(dir.resolve("src/srv/api/World.java")));
                z.closeEntry();
            }
            Path real = new Sources(dir.resolve("cache2"), cfr).sourceOf(world);
            assertEquals(Files.readString(dir.resolve("src/srv/api/World.java")), Files.readString(real));
        }
    }

    @Test
    void aJdkClassIsDecompiledFromTheRuntimeImage(@TempDir Path dir) throws Exception {
        Path cfr = fakeCfr(dir);
        String saved = System.getProperty("java.home");
        System.setProperty("java.home", dir.resolve("no-jdk-sources").toString());   // no lib/src.zip there
        try {
            Path f = new Sources(dir.resolve("cache"), cfr).sourceOf(java.util.HashMap.class);
            String text = Files.readString(f);
            assertTrue(text.contains("public class HashMap"), text);
            assertTrue(text.contains("java/util/HashMap$Node.class"), "its nested classes from jrt:/");
        } finally {
            System.setProperty("java.home", saved);
        }
    }

    @Test
    void aDecompilerThatHangsOrFailsFallsBackToTheOutline(@TempDir Path dir) throws Exception {
        Path cfr = fakeCfr(dir);
        try (java.net.URLClassLoader server = server(dir)) {
            Class<?> world = world(server);
            // a jar that is not CFR at all stands for "fails", a timeout shorter than a JVM's start for "hangs"
            Path notCfr = Files.writeString(dir.resolve("broken-cfr.jar"), "not a jar");
            Path failed = new Sources(dir.resolve("c1"), notCfr).sourceOf(world);
            assertTrue(failed.toString().contains("outline"), failed.toString());
            long t0 = System.nanoTime();
            Path slow = new Sources(dir.resolve("c2"), cfr, 1).sourceOf(world);   // 1 ms: the JVM alone takes longer
            assertTrue(slow.toString().contains("outline"), slow.toString());
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000);
            assertTrue(Files.readString(slow).contains("The source of srv.api.World is not available"));
        }
    }

    @Test
    void theSettingNamesCfrOrTurnsItOff(@TempDir Path dir) throws Exception {
        Path cfr = fakeCfr(dir);
        String saved = System.getProperty("jmp.cfr");
        try {
            System.setProperty("jmp.cfr", cfr.toString());
            assertEquals(cfr, Cfr.find().get());
            System.setProperty("jmp.cfr", dir.resolve("missing.jar").toString());
            assertNull(Cfr.find().get());
            System.setProperty("jmp.cfr", "off");
            assertNull(Cfr.find().get());
        } finally {
            if (saved == null) System.clearProperty("jmp.cfr"); else System.setProperty("jmp.cfr", saved);
        }
    }

    /** An HTTP server with one file on it, counting the requests. */
    private record Served(com.sun.net.httpserver.HttpServer server, int[] hits) implements AutoCloseable {
        URI url() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cfr.jar");
        }

        @Override
        public void close() { server.stop(0); }
    }

    private static Served serve(byte[] body) throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        int[] hits = {0};
        server.createContext("/cfr.jar", ex -> {
            hits[0]++;
            ex.sendResponseHeaders(200, body.length);
            try (var out = ex.getResponseBody()) { out.write(body); }
        });
        server.start();
        return new Served(server, hits);
    }

    @Test
    void cfrIsDownloadedOnceIntoTheDataFolderWhenAClassNeedsIt(@TempDir Path dir) throws Exception {
        byte[] jar = Files.readAllBytes(fakeCfr(dir));
        try (java.net.URLClassLoader server = server(dir)) {
            Class<?> world = world(server);
            try (Served s = serve(jar)) {
                Cfr.Installer cfr = new Cfr.Installer(dir.resolve("data/cfr"), new Cfr.Pin("0.152", s.url(), Cfr.sha256(jar)));
                Path f = new Sources(dir.resolve("cache"), cfr::jar, Sources.CFR_TIMEOUT_MS).sourceOf(world);
                assertTrue(f.toString().contains("decompiled"), f.toString());
                assertEquals(dir.resolve("data/cfr/cfr-0.152.jar"), cfr.jar());
                assertArrayEquals(jar, Files.readAllBytes(cfr.jar()));
                // installed: the next server (another installer over the same folder) finds it there
                Cfr.Installer next = new Cfr.Installer(dir.resolve("data/cfr"), new Cfr.Pin("0.152", s.url(), Cfr.sha256(jar)));
                assertEquals(cfr.jar(), next.jar());
                assertEquals(1, s.hits()[0]);
                try (var files = Files.list(dir.resolve("data/cfr"))) {
                    assertEquals(List.of("cfr-0.152.jar"), files.map(p -> p.getFileName().toString()).toList(), "no .part left behind");
                }
            }
        }
    }

    @Test
    void aDownloadThatIsNotThePinnedCfrIsNeverRun(@TempDir Path dir) throws Exception {
        byte[] jar = Files.readAllBytes(fakeCfr(dir));
        try (java.net.URLClassLoader server = server(dir)) {
            Class<?> world = world(server);
            try (Served s = serve(jar)) {
                Cfr.Pin other = new Cfr.Pin("0.152", s.url(), Cfr.sha256("another jar".getBytes(StandardCharsets.UTF_8)));
                Cfr.Installer cfr = new Cfr.Installer(dir.resolve("data/cfr"), other);
                Path f = new Sources(dir.resolve("cache"), cfr::jar, Sources.CFR_TIMEOUT_MS).sourceOf(world);
                assertTrue(f.toString().contains("outline"), f.toString());
                assertFalse(Files.exists(dir.resolve("data/cfr/cfr-0.152.jar")));
                // a failed download is not repeated on every request
                assertNull(cfr.jar());
                assertEquals(1, s.hits()[0]);
                // nor is a jar put into the folder by hand run when its hash is not the pinned one
                Files.createDirectories(dir.resolve("data/cfr"));
                Files.write(dir.resolve("data/cfr/cfr-0.152.jar"), jar);
                assertNull(new Cfr.Installer(dir.resolve("data/cfr"), other).jar(), "a jar with another hash is not run");
            }
        }
    }

    @Test
    void thePinPointsAtMavenCentralWithASha256() {
        // the pin is written by the Gradle build (lang/build.gradle.kts, cfrPin); a build without it has none
        Cfr.Pin pin = Cfr.pin();
        if (pin != null) {
            assertTrue(pin.url().toString().startsWith("https://repo1.maven.org/maven2/org/benf/cfr/" + pin.version() + "/"), pin.toString());
            assertTrue(pin.sha256().matches("[0-9a-f]{64}"), pin.toString());
        }
    }
}
