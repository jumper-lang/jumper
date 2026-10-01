package me.padej.jumper;

import me.padej.jumper.runtime.Interop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class name means the class of the loader that looks it up: two servers (or a jar rebuilt while a
 * language server runs) may have different classes of the same name - the cache of found classes must
 * not hand one loader's class to another.
 */
class InteropCacheTest {
    private static Path compiled(Path dir, String body) throws Exception {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        org.junit.jupiter.api.Assumptions.assumeTrue(javac != null, "needs a JDK");
        Path src = dir.resolve("src/cachetest/Thing.java"), out = dir.resolve("out");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package cachetest; public class Thing { " + body + " }");
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), src.toString()));
        return out;
    }

    @Test
    void theSameNameUnderTwoLoadersIsTwoClasses(@TempDir Path dir) throws Exception {
        Path a = compiled(dir.resolve("a"), "public static int size() { return 1; }");
        Path b = compiled(dir.resolve("b"), "public int size() { return 2; }");
        Thread t = Thread.currentThread();
        ClassLoader saved = t.getContextClassLoader();
        try (URLClassLoader la = new URLClassLoader(new URL[] {a.toUri().toURL()}, saved);
             URLClassLoader lb = new URLClassLoader(new URL[] {b.toUri().toURL()}, saved)) {
            t.setContextClassLoader(la);
            Class<?> ca = Interop.findClass("cachetest.Thing");
            t.setContextClassLoader(lb);
            Class<?> cb = Interop.findClass("cachetest.Thing");
            assertTrue(ca != cb, "two classes");
            assertTrue(la == ca.getClassLoader());
            assertTrue(lb == cb.getClassLoader());
            t.setContextClassLoader(la);
            assertTrue(ca == Interop.findClass("cachetest.Thing"), "cached per loader");
        } finally {
            t.setContextClassLoader(saved);
        }
    }
}
