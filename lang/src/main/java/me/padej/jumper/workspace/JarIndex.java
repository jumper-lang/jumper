package me.padej.jumper.workspace;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Everything a tool reads from one jar: its classes ({@link ClassInfo}, read as data) and the Jumper host
 * descriptor it may carry ({@value #DESCRIPTOR}). Built once per jar version and kept by {@link IndexCache}.
 */
public final class JarIndex {
    /** Where a jar that embeds Jumper describes itself: a config (.jmc), so it is data and never runs. */
    public static final String DESCRIPTOR = "META-INF/jumper/host.jmc";
    /** A class file bigger than this is not read (no real class is; a hostile jar could be). */
    private static final long MAX_CLASS_BYTES = 16 << 20;

    private final Path jar;
    private final long size, mtime;
    private final String descriptor;
    private final Map<String, ClassInfo> classes;
    private final int unreadable;

    private JarIndex(Path jar, long size, long mtime, String descriptor, Map<String, ClassInfo> classes, int unreadable) {
        this.jar = jar;
        this.size = size;
        this.mtime = mtime;
        this.descriptor = descriptor;
        this.classes = Collections.unmodifiableMap(classes);
        this.unreadable = unreadable;
    }

    /** Reads the jar. A class file that cannot be read is counted in {@link #unreadable()}, not an error. */
    public static JarIndex build(Path jar) throws IOException {
        Path abs = jar.toAbsolutePath().normalize();
        BasicFileAttributes attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        Map<String, ClassInfo> classes = new TreeMap<>();
        String descriptor = null;
        int unreadable = 0;
        try (ZipFile zip = new ZipFile(abs.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.equals(DESCRIPTOR)) {
                    try (var in = zip.getInputStream(e)) { descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                    continue;
                }
                // module-info/package-info are not classes; META-INF/versions/* are alternatives of classes read already
                if (!n.endsWith(".class") || n.endsWith("module-info.class") || n.endsWith("package-info.class")
                        || n.startsWith("META-INF/")) continue;
                if (e.getSize() > MAX_CLASS_BYTES) { unreadable++; continue; }
                try (var in = zip.getInputStream(e)) {
                    ClassInfo ci = ClassFiles.read(in.readAllBytes());
                    classes.put(ci.name(), ci);
                } catch (IOException ex) {
                    unreadable++;
                }
            }
        }
        return new JarIndex(abs, attrs.size(), attrs.lastModifiedTime().toMillis(), descriptor, classes, unreadable);
    }

    public Path jar() { return jar; }
    public long size() { return size; }
    public long mtime() { return mtime; }

    /** The host descriptor's source text, or null if the jar does not embed a Jumper host. */
    public String descriptor() { return descriptor; }

    /** Binary name (dots) -> class, sorted by name. */
    public Map<String, ClassInfo> classes() { return classes; }

    public ClassInfo find(String name) { return classes.get(name); }

    /** Class files the reader could not read (broken or bigger than 16 MB). */
    public int unreadable() { return unreadable; }

    /** Is this index still the jar on disk? */
    boolean matches(long size, long mtime) {
        return this.size == size && this.mtime == mtime;
    }

    // ------------------------------------------------------------------ serialization (IndexCache)

    static final int FORMAT = 1;

    void write(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT);
        writeString(out, jar.toString());
        out.writeLong(size);
        out.writeLong(mtime);
        out.writeBoolean(descriptor != null);
        if (descriptor != null) writeString(out, descriptor);
        out.writeInt(unreadable);
        out.writeInt(classes.size());
        for (ClassInfo c : classes.values()) {
            writeString(out, c.name());
            out.writeShort(c.access());
            writeString(out, c.superName() == null ? "" : c.superName());
            out.writeShort(c.interfaces().size());
            for (String i : c.interfaces()) writeString(out, i);
            writeMembers(out, c.fields());
            writeMembers(out, c.methods());
        }
    }

    /** Reads an index written by {@link #write}; null if the header does not match the jar as it is now. */
    static JarIndex read(DataInputStream in, Path jar, long size, long mtime) throws IOException {
        if (in.readInt() != FORMAT) return null;
        if (!readString(in).equals(jar.toString())) return null;
        if (in.readLong() != size || in.readLong() != mtime) return null;
        String descriptor = in.readBoolean() ? readString(in) : null;
        int unreadable = in.readInt();
        int n = in.readInt();
        Map<String, ClassInfo> classes = new TreeMap<>();
        for (int k = 0; k < n; k++) {
            String name = readString(in);
            int access = in.readUnsignedShort();
            String sup = readString(in);
            int ni = in.readUnsignedShort();
            List<String> ifs = new ArrayList<>(ni);
            for (int i = 0; i < ni; i++) ifs.add(readString(in));
            List<ClassInfo.Member> fields = readMembers(in), methods = readMembers(in);
            classes.put(name, new ClassInfo(name, access, sup.isEmpty() ? null : sup, List.copyOf(ifs), fields, methods));
        }
        return new JarIndex(jar, size, mtime, descriptor, classes, unreadable);
    }

    private static void writeMembers(DataOutputStream out, List<ClassInfo.Member> ms) throws IOException {
        out.writeInt(ms.size());
        for (ClassInfo.Member m : ms) {
            writeString(out, m.name());
            writeString(out, m.descriptor());
            out.writeShort(m.access());
        }
    }

    private static List<ClassInfo.Member> readMembers(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<ClassInfo.Member> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new ClassInfo.Member(readString(in), readString(in), in.readUnsignedShort()));
        return List.copyOf(out);
    }

    /** Length-prefixed UTF-8 (writeUTF stops at 64 KB). */
    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > (64 << 20)) throw new IOException("bad string length " + n);
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }
}
