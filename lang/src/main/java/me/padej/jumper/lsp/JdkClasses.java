package me.padej.jumper.lsp;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * The names of the JDK's classes (for `import java.util.|`), read once from the runtime image (jrt:/):
 * the server's jars do not contain them. Names only; a class is loaded when it is about to be offered.
 */
final class JdkClasses {
    private JdkClasses() {}

    private static volatile List<String> names;

    static List<String> names() {
        List<String> n = names;
        if (n != null) return n;
        synchronized (JdkClasses.class) {
            if (names != null) return names;
            List<String> out = new ArrayList<>();
            try {
                FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
                Path modules = jrt.getPath("/modules");
                try (Stream<Path> mods = Files.list(modules)) {
                    for (Path mod : mods.toList()) {
                        try (Stream<Path> s = Files.walk(mod)) {
                            s.forEach(p -> {
                                String f = mod.relativize(p).toString();
                                if (!f.endsWith(".class") || f.indexOf('$') >= 0 || f.endsWith("module-info.class") || f.endsWith("package-info.class")) return;
                                out.add(f.substring(0, f.length() - 6).replace('/', '.'));
                            });
                        }
                    }
                }
            } catch (Exception e) {
                // no runtime image (an unusual JVM): no JDK names
            }
            Collections.sort(out);
            names = List.copyOf(out);
            return names;
        }
    }
}
