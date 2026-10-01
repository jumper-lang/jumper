package me.padej.jumper;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import me.padej.jumper.interp.Interpreter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Every src/test/resources/scripts/*.jmp is a separate test; the script checks itself via assert(). */
class ScriptTest {
    @TestFactory
    Stream<DynamicTest> scripts() throws IOException {
        Path dir = Path.of("src/test/resources/scripts");
        return Files.list(dir)
                .filter(p -> p.toString().endsWith(".jmp"))
                .sorted()
                .map(p -> DynamicTest.dynamicTest(p.getFileName().toString(),
                        () -> Interpreter.runWithBigStack(() -> new Interpreter().evalFile(p))));
    }
}
