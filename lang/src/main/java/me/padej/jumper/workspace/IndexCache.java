package me.padej.jumper.workspace;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/**
 * Jar indexes kept on the user's machine, never next to the jars: a server folder is not ours to write in.
 * The unit is one jar. Its entry is valid while the jar's size and modification time are the same; any
 * change - a rebuilt plugin, an updated server - rebuilds that one entry, the others are read back as they
 * are. There is no diffing inside a jar: reading a jar anew is cheap enough, knowing that it changed is
 * the part worth caching.
 *
 * <p>Default place: {@code %LOCALAPPDATA%\jumper\index} on Windows, {@code $XDG_CACHE_HOME/jumper/index}
 * or {@code ~/.cache/jumper/index} elsewhere; {@code -Djmp.cache=dir} overrides it. If the directory
 * cannot be written the cache works in memory only.
 */
public final class IndexCache {
    private final Path dir;
    private final Map<Path, JarIndex> memory = new HashMap<>();
    private int builds, hits;

    /** A cache in dir; null - in memory only. */
    public IndexCache(Path dir) {
        this.dir = dir;
    }

    public static IndexCache atDefault() {
        return new IndexCache(defaultDir());
    }

    public static Path defaultDir() {
        String prop = System.getProperty("jmp.cache");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "jumper", "index");
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "jumper", "index");
        return Path.of(System.getProperty("user.home"), ".cache", "jumper", "index");
    }

    /** The index of the jar as it is on disk now: from memory, from the cache directory, or read anew. */
    public synchronized JarIndex get(Path jar) throws IOException {
        Path abs = jar.toAbsolutePath().normalize();
        BasicFileAttributes a = Files.readAttributes(abs, BasicFileAttributes.class);
        long size = a.size(), mtime = a.lastModifiedTime().toMillis();
        JarIndex idx = memory.get(abs);
        if (idx != null && idx.matches(size, mtime)) { hits++; return idx; }
        Path file = entry(abs);
        if (file != null && Files.isRegularFile(file)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                idx = JarIndex.read(in, abs, size, mtime);
            } catch (IOException | RuntimeException e) {
                idx = null;   // a damaged entry is just rebuilt
            }
            if (idx != null) { hits++; memory.put(abs, idx); return idx; }
        }
        idx = JarIndex.build(abs);
        builds++;
        memory.put(abs, idx);
        store(file, idx);
        return idx;
    }

    private void store(Path file, JarIndex idx) {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "idx", ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                idx.write(out);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            // read-only or foreign directory: the memory copy is enough for this session
        }
    }

    /** One file per jar path: the name is a hash of the path, the header inside says which jar and version. */
    private Path entry(Path jar) {
        if (dir == null) return null;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(jar.toString().getBytes(StandardCharsets.UTF_8));
            return dir.resolve(java.util.HexFormat.of().formatHex(h) + ".idx");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Jars read anew (not found or out of date in the cache) - for tests and reports. */
    public synchronized int builds() { return builds; }

    /** Jars served from memory or the cache directory. */
    public synchronized int hits() { return hits; }
}
