package me.padej.jumper.lsp;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Sources of Java classes for go-to-definition, as files an editor can open, the best that can be had:
 * <ol>
 *   <li>the real source: the JDK's from its src.zip, a jar's from `name-sources.jar` next to it (or from .java
 *       entries in the jar itself);</li>
 *   <li>decompiled by CFR. CFR is not in jmp.jar: the server downloads it once into Jumper's data folder the
 *       first time a class needs it ({@link Cfr}: pinned version and SHA-256; {@code -Djmp.cfr} / {@code JMP_CFR}
 *       name another cfr.jar or {@code off}). It runs as a separate process with a time limit: a decompiler that
 *       hangs or fails costs this one request, never the language server;</li>
 *   <li>an outline generated from the class - its declarations with no bodies.</li>
 * </ol> Everything is written under the user's cache
 * folder ({@code .../jumper/sources}), one folder per JDK version or jar version, never next to the jars, and
 * made read-only: it is for reading.
 */
final class Sources {
    private final Path dir;
    /** The CFR jar to decompile with; null while there is none (it may appear later: {@link Cfr}). */
    private final Supplier<Path> cfr;
    private final long cfrTimeoutMs;
    /** The version of what is written: a new outline format must not be served from an old cache folder. */
    static final int FORMAT = 2;
    static final long CFR_TIMEOUT_MS = 30_000;

    Sources(Path dir) {
        this(dir, Cfr.find(), CFR_TIMEOUT_MS);
    }

    /** @param cfr the CFR jar to decompile with, or null (outlines only) */
    Sources(Path dir, Path cfr) {
        this(dir, () -> cfr, CFR_TIMEOUT_MS);
    }

    Sources(Path dir, Path cfr, long cfrTimeoutMs) {
        this(dir, () -> cfr, cfrTimeoutMs);
    }

    Sources(Path dir, Supplier<Path> cfr, long cfrTimeoutMs) {
        this.dir = dir;
        this.cfr = cfr;
        this.cfrTimeoutMs = cfrTimeoutMs;
    }

    static final String CFR_MAIN = "org.benf.cfr.reader.Main";

    static Path defaultDir() {
        return me.padej.jumper.workspace.IndexCache.defaultDir().getParent().resolve("sources");
    }

    /** A place in a source file: the file, the offset of the name, its length. */
    record Place(Path file, int offset, int length) {}

    /**
     * Where `member` of `cls` is declared (null member - the class itself): the method with that many
     * parameters if there is one (arity < 0 - any), else the first of that name, else a field; else the class.
     */
    Place locate(Class<?> cls, String member, int arity) throws IOException {
        Path file = sourceOf(cls);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (member != null) {
            int at = method(text, member, arity);
            if (at < 0) at = field(text, member);
            if (at >= 0) return new Place(file, at, member.length());
        }
        int at = typeDecl(text, cls.getSimpleName());
        return new Place(file, Math.max(at, 0), at < 0 ? 0 : cls.getSimpleName().length());
    }

    /** The source file of the top-level class that holds `cls`: real if it can be found, an outline otherwise. */
    Path sourceOf(Class<?> cls) throws IOException {
        Class<?> top = cls;
        while (top.getEnclosingClass() != null) top = top.getEnclosingClass();
        String rel = top.getName().replace('.', '/') + ".java";
        Path origin = origin(top);
        String key = origin == null ? "jdk-" + Runtime.version().feature() + "-" + Runtime.version()
                : origin.getFileName() + "-" + Integer.toHexString(origin.toAbsolutePath().toString().hashCode())
                  + "-" + Files.getLastModifiedTime(origin).toMillis();
        key = (key + "-f" + FORMAT).replaceAll("[^\\w.+-]", "_");
        Path real = dir.resolve(key).resolve(rel), decompiled = dir.resolve(key).resolve("decompiled").resolve(rel),
                outline = dir.resolve(key).resolve("outline").resolve(rel);
        if (Files.isRegularFile(real)) return real;
        if (Files.isRegularFile(decompiled)) return decompiled;
        String text = origin == null ? jdkSource(top, rel) : jarSource(origin, rel);
        if (text != null) return write(real, text);
        Path jar = cfr.get();   // the first class that needs it installs CFR
        if (jar != null) {
            String d = decompile(jar, top, origin, rel);
            if (d != null) return write(decompiled, d);
        }
        if (Files.isRegularFile(outline)) return outline;
        return write(outline, outline(top, origin));
    }

    // ------------------------------------------------------------------ CFR

    /**
     * The class decompiled by CFR, or null (no bytes, CFR failed or took too long). The top-level class and its
     * nested classes (Outer$*.class) go into a temporary jar - CFR puts nested classes back into their outer one
     * - and CFR runs on it in its own JVM: `java -cp cfr.jar org.benf.cfr.reader.Main tmp.jar --outputdir out`.
     */
    private String decompile(Path cfr, Class<?> top, Path origin, String rel) {
        Path work = null;
        try {
            java.util.Map<String, byte[]> classes = classFiles(top, origin);
            if (classes.isEmpty()) return null;
            Files.createDirectories(dir);
            work = Files.createTempDirectory(dir, "cfr");
            Path jar = work.resolve("in.jar"), out = work.resolve("out");
            try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                for (var e : classes.entrySet()) {
                    z.putNextEntry(new ZipEntry(e.getKey()));
                    z.write(e.getValue());
                    z.closeEntry();
                }
            }
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(javaExe(), "-cp", cfr.toString(),
                    CFR_MAIN, jar.toString(), "--outputdir", out.toString(), "--silent", "true"));
            if (origin != null) cmd.addAll(java.util.List.of("--extraclasspath", origin.toString()));   // types of its signatures
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(cfrTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // wait for it to be gone: on Windows the files a live process holds cannot be deleted below
                p.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return null;
            }
            Path result = out.resolve(rel);
            if (!Files.isRegularFile(result)) return null;
            return "// The source of " + top.getName() + " is not available: decompiled by CFR from its class file. Read-only.\n"
                    + Files.readString(result, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (work != null) deleteTree(work);
        }
    }

    /** The class files of `top` and its nested classes, entry name -> bytes: from its jar, or from the JDK's image. */
    private static java.util.Map<String, byte[]> classFiles(Class<?> top, Path origin) throws IOException {
        String base = top.getName().replace('.', '/');
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        if (origin != null) {
            try (ZipFile z = new ZipFile(origin.toFile())) {
                var en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (n.equals(base + ".class") || n.startsWith(base + "$") && n.endsWith(".class"))
                        try (var in = z.getInputStream(e)) { out.put(n, in.readAllBytes()); }
                }
            }
            return out;
        }
        // the JDK: its runtime image (jrt:/modules/<module>/<path>)
        java.nio.file.FileSystem jrt = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
        Path pkgDir = jrt.getPath("/modules", top.getModule().getName()).resolve(base).getParent();
        String simple = base.substring(base.lastIndexOf('/') + 1);
        try (var s = Files.list(pkgDir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.equals(simple + ".class") || n.startsWith(simple + "$") && n.endsWith(".class"))
                    out.put(base.substring(0, base.length() - simple.length()) + n, Files.readAllBytes(p));
            }
        }
        return out;
    }

    private static String javaExe() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static void deleteTree(Path root) {
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            // a leftover temporary folder in the cache: harmless
        }
    }

    /** The jar a class was loaded from; null for the JDK's own classes. */
    private static Path origin(Class<?> c) {
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            Path p = Path.of(cs.getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String jdkSource(Class<?> top, String rel) {
        Path zip = Path.of(System.getProperty("java.home"), "lib", "src.zip");
        if (!Files.isRegularFile(zip)) return null;
        String module = top.getModule().isNamed() ? top.getModule().getName() + "/" : "";
        return entry(zip, module + rel, rel);
    }

    private static String jarSource(Path jar, String rel) {
        String name = jar.getFileName().toString();
        Path sources = jar.resolveSibling(name.replaceFirst("\\.jar$", "") + "-sources.jar");
        if (Files.isRegularFile(sources)) {
            String s = entry(sources, rel);
            if (s != null) return s;
        }
        return entry(jar, rel);
    }

    private static String entry(Path zip, String... names) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (String n : names) {
                ZipEntry e = z.getEntry(n);
                if (e != null) try (var in = z.getInputStream(e)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            }
        } catch (IOException e) {
            // not a readable zip
        }
        return null;
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "src", ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            if (!Files.isRegularFile(file)) throw e;   // another request wrote it first
        }
        file.toFile().setWritable(false);
        return file;
    }

    // ------------------------------------------------------------------ an outline from the class

    static String outline(Class<?> top, Path origin) {
        StringBuilder sb = new StringBuilder();
        String where = origin == null ? "the JDK has no lib/src.zip"
                : "put " + origin.getFileName().toString().replaceFirst("\\.jar$", "") + "-sources.jar next to " + origin.getFileName();
        sb.append("// The source of ").append(top.getName()).append(" is not available (").append(where).append(").\n")
                .append("// What follows is its outline, read from the class file by Jumper: the declarations,\n")
                .append("// without method bodies - they exist only as bytecode. Read-only.\n\n");
        if (!top.getPackageName().isEmpty()) sb.append("package ").append(top.getPackageName()).append(";\n\n");
        type(sb, top, "");
        return sb.toString();
    }

    private static void type(StringBuilder sb, Class<?> c, String ind) {
        int mods = c.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL | (c.isInterface() ? 0 : Modifier.ABSTRACT));
        sb.append(ind).append(mods == 0 ? "" : Modifier.toString(mods) + " ")
                .append(c.isAnnotation() ? "@interface " : c.isInterface() ? "interface " : c.isEnum() ? "enum " : c.isRecord() ? "record " : "class ")
                .append(c.getSimpleName()).append(typeParams(c.getTypeParameters()));
        pkg.set(c.getPackageName());
        try {
            if (c.getGenericSuperclass() != null && c.getSuperclass() != Object.class && !c.isEnum() && !c.isRecord())
                sb.append(" extends ").append(name(c.getGenericSuperclass()));
            Type[] ifs = c.getGenericInterfaces();
            if (ifs.length > 0) {
                sb.append(c.isInterface() ? " extends " : " implements ");
                for (int i = 0; i < ifs.length; i++) sb.append(i > 0 ? ", " : "").append(name(ifs[i]));
            }
            sb.append(" {\n");
            String in = ind + "    ";
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || !visible(f.getModifiers())) continue;
                sb.append(in).append(mods(f.getModifiers())).append(name(f.getGenericType())).append(' ').append(f.getName()).append(";\n");
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (k.isSynthetic() || !visible(k.getModifiers())) continue;
                sb.append(in).append(mods(k.getModifiers())).append(c.getSimpleName()).append('(');
                params(sb, k.getGenericParameterTypes(), k.getParameters(), k.isVarArgs());
                sb.append(");\n");
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge() || !visible(m.getModifiers())) continue;
                int mm = m.getModifiers();
                if (c.isInterface()) mm &= ~(Modifier.PUBLIC | Modifier.ABSTRACT);
                sb.append(in).append(c.isInterface() && m.isDefault() ? "default " : "").append(mods(mm & ~Modifier.NATIVE));
                String tps = typeParams(m.getTypeParameters());
                if (!tps.isEmpty()) sb.append(tps).append(' ');
                sb.append(name(m.getGenericReturnType())).append(' ').append(m.getName()).append('(');
                params(sb, m.getGenericParameterTypes(), m.getParameters(), m.isVarArgs());
                sb.append(");\n");
            }
            for (Class<?> inner : c.getDeclaredClasses()) {
                if (!visible(inner.getModifiers()) || inner.isSynthetic()) continue;
                sb.append('\n');
                type(sb, inner, in);
            }
        } catch (LinkageError e) {
            sb.append(" {\n").append(ind).append("    // members cannot be listed: ").append(e).append('\n');
        }
        sb.append(ind).append("}\n");
    }

    private static boolean visible(int mods) {
        return Modifier.isPublic(mods) || Modifier.isProtected(mods);
    }

    private static String mods(int m) {
        String s = Modifier.toString(m & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT));
        return s.isEmpty() ? "" : s + " ";
    }

    /** Parameters with their names when the class file has them (compiled with -parameters), else arg0, arg1... */
    private static void params(StringBuilder sb, Type[] ps, java.lang.reflect.Parameter[] named, boolean varargs) {
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            String t = name(ps[i]);
            if (varargs && i == ps.length - 1 && t.endsWith("[]")) t = t.substring(0, t.length() - 2) + "...";
            // generic parameter types and parameters can differ in count (an inner class's outer instance)
            String n = named.length == ps.length && named[i].isNamePresent() ? named[i].getName() : "arg" + i;
            sb.append(t).append(' ').append(n);
        }
    }

    /** The package of the class being outlined: its own types are written without it. */
    private static final ThreadLocal<String> pkg = ThreadLocal.withInitial(() -> "");

    /** A type as source writes it: java.lang. and the class's own package dropped, nested classes with dots. */
    private static String name(Type t) {
        String n = t.getTypeName().replace('$', '.').replaceAll("\\bjava\\.lang\\.(?=[A-Z])", "");
        String p = pkg.get();
        return p.isEmpty() ? n : n.replaceAll("\\b" + Pattern.quote(p) + "\\.(?=[A-Z])", "");
    }

    /** `<K, V>`, `<T extends Comparable<? super T>>`, or "". */
    private static String typeParams(java.lang.reflect.TypeVariable<?>[] tvs) {
        if (tvs.length == 0) return "";
        StringBuilder sb = new StringBuilder("<");
        for (int i = 0; i < tvs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(tvs[i].getName());
            Type[] bounds = tvs[i].getBounds();
            if (bounds.length > 0 && !(bounds.length == 1 && bounds[0] == Object.class)) {
                sb.append(" extends ");
                for (int j = 0; j < bounds.length; j++) sb.append(j > 0 ? " & " : "").append(name(bounds[j]));
            }
        }
        return sb.append('>').toString();
    }

    // ------------------------------------------------------------------ finding declarations in source

    static int typeDecl(String text, String simple) {
        Matcher m = Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+(" + Pattern.quote(simple) + ")\\b").matcher(text);
        return m.find() ? m.start(1) : -1;
    }

    private static final Pattern DECL_PREFIX = Pattern.compile(
            "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
                    + "(?:<[^()]*>\\s+)?[\\w.$<>\\[\\],? ]+\\s+$");

    /** A method declaration of that name, preferring one with `arity` parameters. */
    static int method(String text, String name, int arity) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(text);
        int first = -1;
        while (m.find()) {
            int lineStart = text.lastIndexOf('\n', m.start()) + 1;
            if (!DECL_PREFIX.matcher(text.substring(lineStart, m.start())).matches()) continue;
            if (text.substring(lineStart, m.start()).strip().startsWith("return")) continue;
            if (first < 0) first = m.start();
            if (arity < 0 || params(text, m.end() - 1) == arity) return m.start();
        }
        return first;
    }

    /** The number of parameters in the list opening at `open`. */
    private static int params(String text, int open) {
        int depth = 0, n = 0;
        boolean any = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<') depth++;
            else if (c == ')' || c == '>') { if (--depth == 0 && c == ')') return any ? n + 1 : 0; }
            else if (c == ',' && depth == 1) n++;
            else if (!Character.isWhitespace(c)) any = true;
        }
        return -1;
    }

    static int field(String text, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*[\\w.$<>\\[\\],? ]+\\s+("
                + Pattern.quote(name) + ")\\s*[;=]").matcher(text);
        return m.find() ? m.start(1) : -1;
    }
}
