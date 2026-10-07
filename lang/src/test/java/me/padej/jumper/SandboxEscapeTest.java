package me.padej.jumper;

import me.padej.jumper.hostapi.*;
import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.runtime.Access;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Escapes found by the Fable 5.1 security review (07.10.2026): a script under the ordinary mod policy
 * (java.lang + java.util, the policy in Access/Policy javadoc and SandboxAttackTest.modPolicy) reaches
 * file I/O, every system property and the native/FFI and management namespaces - none of which the host
 * granted. The root cause: allowPackage opens every sub-namespace by prefix, and java.util itself holds
 * file sinks. All must stay closed unless the host opens them by class.
 */
class SandboxEscapeTest {

    private static Access modPolicy() {
        String P = "me.padej.jumper.hostapi.";
        return Access.parse(("""
                Policy.allowPackage("java.lang");
                Policy.allowPackage("java.util");
                Policy.allowClass("%sPlayer");
                Policy.allowClass("%sEntity").denyMethod("setHealth");
                Policy.allowClass("%sZombie");
                Policy.allowClass("%sDummy");
                Policy.allowClass("%sWorld");
                """).formatted(P, P, P, P, P));
    }

    private static Interpreter sandbox() {
        return new Interpreter().access(modPolicy()).define("player", new Player()).define("world", new World());
    }

    private static void blocked(String what, String src) {
        SecurityException e = assertThrows(SecurityException.class, () -> sandbox().eval(src), src);
        assertTrue(e.getMessage().contains("Access denied"), src + " -> " + e.getMessage());
    }

    @Test
    void javaUtilFormatterCannotWriteFiles() {
        // new java.util.Formatter(String) opens that path for writing, with only a String argument
        blocked("Formatter", "return new java.util.Formatter(\"/tmp/jumper-escape-probe.txt\");");
    }

    @Test
    void javaUtilLoggingFileHandlerCannotWriteFiles() {
        blocked("FileHandler", "return new java.util.logging.FileHandler(\"/tmp/jumper-escape-probe.log\");");
    }

    @Test
    void managementDoesNotLeakSystemPropertiesOrMBeanServer() {
        blocked("ManagementFactory", "return java.lang.management.ManagementFactory.getRuntimeMXBean();");
        blocked("ManagementFactory", "return java.lang.management.ManagementFactory.getPlatformMBeanServer();");
    }

    @Test
    void foreignFunctionApiIsClosed() {
        blocked("Linker", "return java.lang.foreign.Linker.nativeLinker();");
        blocked("MemorySegment", "return java.lang.foreign.MemorySegment.NULL;");
    }

    @Test
    void toolProviderIsClosed() {
        blocked("ToolProvider", "return java.util.spi.ToolProvider.findFirst(\"javac\");");
    }

    @Test
    void ordinaryJavaUtilStillWorks() {
        // the fix must not close the library a mod actually uses
        assertEquals("[7]", sandbox().eval("import java.util.ArrayList; dyn l = new ArrayList(); l.add(7); return str(l);"));
        assertEquals(5, sandbox().eval("return new java.util.concurrent.atomic.AtomicInteger(5).get();"));
        assertEquals("[1, 2, 3]", sandbox().eval("return str([3,1,2].stream().sorted().toList());"));
    }
}
