package me.padej.jumper.jit;

import me.padej.jumper.ast.FunctionNode;
import me.padej.jumper.interp.Interpreter;
import me.padej.jumper.runtime.JFunction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every benchmark script must go through Tier 1 without a single function falling back to the
 * interpreter. A silent fallback ("[jit] failed", visible only under JMP_DEBUG) still returns the
 * right result, so the benchmark harness cannot tell it from a slow compiled body - this is how
 * a VerifyError in `switch_dense` once showed up as a 30x regression and nothing else.
 */
class BenchSuiteCompilesTest {

    @Test
    void everyBenchmarkCompilesInTier1() throws IOException {
        assumeTrue(Jit.ENABLED, "Tier 1 is disabled");
        Path suite = Path.of("..", "bench", "suite");
        assumeTrue(Files.isDirectory(suite), "no bench/suite next to lang/");
        List<String> failed = new ArrayList<>();
        int compiled = 0;
        try (Stream<Path> dirs = Files.list(suite)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                for (String name : new String[]{"dyn.jmp", "typed.jmp"}) {
                    Path f = dir.resolve(name);
                    if (!Files.isRegularFile(f)) continue;
                    JFunction fn = new Interpreter().compile(Files.readString(f), f);
                    FunctionNode root = ((FunctionNode.ScriptFunction) fn).node;
                    compiled += Jit.compileAll(root);
                    collectFailed(root, dir.getFileName() + "/" + name, failed, 0);
                }
            }
        }
        assertTrue(compiled > 0, "nothing compiled");
        assertEquals(List.of(), failed, "functions that fell back to Tier 0");
    }

    private static void collectFailed(Object node, String file, List<String> out, int depth) {
        if (node == null || depth > 256) return;
        if (node instanceof FunctionNode fn && fn.jitFailed) out.add(file + ": " + fn.name);
        for (Object child : Captures.children(node)) collectFailed(child, file, out, depth + 1);
    }
}
