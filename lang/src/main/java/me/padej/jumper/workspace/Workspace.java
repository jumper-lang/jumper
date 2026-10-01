package me.padej.jumper.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the context of a Jumper file with no project to go by: a server folder where the host (a plugin
 * jar) put scripts, policies and configs wherever it wanted.
 *
 * <ol>
 * <li><b>Hosts describe themselves.</b> From the file's folder up (at most {@value #MAX_UP} levels, not above
 *     the user's home), every jar in a folder or in its direct subfolders ({@code server/plugins/*.jar}) is
 *     probed for {@value JarIndex#DESCRIPTOR}. A descriptor says where its root is (relative to the jar) and
 *     which globs are its scripts, configs and policy. The host whose globs match the file owns it; of two
 *     matching, the more specific glob wins (and a note says so).</li>
 * <li><b>By convention</b>, when no descriptor claims the file: the policy of a script is the nearest {@code *.jma}
 *     up from it; if that folder has several, the one named like a folder on the way
 *     ({@code scripts/<name>/a.jmp} -> {@code <name>.jma}); if it is still ambiguous - none, with a note.
 *     The root is then that policy's folder.</li>
 * <li><b>Classpath:</b> the jars under the root (depth {@value #MAX_DEPTH}, at most {@value #MAX_JARS}), minus
 *     the jars of other hosts - one plugin's scripts do not see another plugin's classes at run time either.</li>
 * </ol>
 *
 * Nothing is written anywhere near the files: indexes live in {@link IndexCache}, on the user's machine.
 */
public final class Workspace {
    static final int MAX_UP = 8, MAX_DEPTH = 8, MAX_JARS = 4096, MAX_DIR_ENTRIES = 512;

    private Workspace() {}

    /** Descriptor probes by jar version: probing a big jar reads its central directory, a few ms. */
    private static final Map<Path, Probe> PROBES = new HashMap<>();

    private record Probe(long size, long mtime, String descriptor) {}

    public static FileContext contextFor(Path file) {
        Path f = file.toAbsolutePath().normalize();
        FileContext.Kind kind = FileContext.Kind.of(f);
        List<String> notes = new ArrayList<>();
        List<HostDescriptor> hosts = findHosts(f.getParent(), notes);

        // 1. a host claims the file
        HostDescriptor owner = null;
        int best = -1;
        for (HostDescriptor h : hosts) {
            String glob = switch (kind) {
                case SCRIPT -> h.match(h.scripts(), f);
                case CONFIG -> h.match(h.configs(), f);
                case POLICY -> f.equals(h.access()) ? "(access)" : null;
                case OTHER -> null;
            };
            if (glob == null) continue;
            int spec = glob.equals("(access)") ? Integer.MAX_VALUE : Glob.specificity(glob);
            if (owner != null) {
                notes.add("claimed by two hosts: " + owner.name() + " and " + h.name() + " - the more specific pattern wins");
                if (spec <= best) continue;
            }
            owner = h;
            best = spec;
        }
        for (HostDescriptor h : hosts) for (String p : h.problems()) notes.add(h.name() + " (" + h.jar().getFileName() + "): " + p);

        if (owner != null) {
            Path access = kind == FileContext.Kind.SCRIPT ? owner.access() : null;
            if (access != null && !Files.isRegularFile(access)) {
                notes.add(owner.name() + " names the policy " + owner.root().relativize(access) + ", which does not exist");
            }
            Map<String, String> globals = kind == FileContext.Kind.SCRIPT ? owner.globals() : Map.of();
            return new FileContext(kind, f, owner.root(), owner, access, globals, classpath(owner.root(), hosts, owner), List.copyOf(notes));
        }

        // 2. by convention
        // (with no policy found there is no known server folder: its jars are not searched for - the file
        // may sit on a desktop, and a walk of it would find anything)
        if (kind == FileContext.Kind.SCRIPT) {
            Path access = conventionPolicy(f, notes);
            Path root = access != null ? access.getParent() : f.getParent();
            return new FileContext(kind, f, root, null, access, Map.of(), access != null ? classpath(root, hosts, null) : List.of(),
                    List.copyOf(notes));
        }
        // a policy sees only Policy, a config no Java at all: neither needs a classpath
        return new FileContext(kind, f, f.getParent(), null, null, Map.of(), List.of(), List.copyOf(notes));
    }

    // ------------------------------------------------------------------ hosts

    private static List<HostDescriptor> findHosts(Path from, List<String> notes) {
        Map<Path, HostDescriptor> found = new LinkedHashMap<>();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path d = from;
        for (int up = 0; d != null && up <= MAX_UP; up++, d = d.getParent()) {
            for (Path jar : jarsNear(d)) {
                if (found.containsKey(jar)) continue;
                String text = probe(jar);
                if (text != null) found.put(jar, HostDescriptor.parse(jar, text));
            }
            if (d.equals(home)) break;
        }
        return new ArrayList<>(found.values());
    }

    /** Jars in d and in its direct subfolders. */
    private static List<Path> jarsNear(Path d) {
        List<Path> out = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        dirs.add(d);
        try (Stream<Path> s = Files.list(d)) {
            s.limit(MAX_DIR_ENTRIES).forEach(p -> {
                if (Files.isDirectory(p)) dirs.add(p);
                else if (isJar(p)) out.add(p.toAbsolutePath().normalize());
            });
        } catch (IOException | SecurityException e) {
            return out;
        }
        for (Path sub : dirs.subList(1, dirs.size())) {
            try (Stream<Path> s = Files.list(sub)) {
                s.limit(MAX_DIR_ENTRIES).filter(Workspace::isJar).forEach(p -> out.add(p.toAbsolutePath().normalize()));
            } catch (IOException | SecurityException e) {
                // unreadable folder: skip
            }
        }
        return out;
    }

    /** A jar with classes: not `name-sources.jar` (read only by go-to-definition, next to its jar). */
    private static boolean isJar(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".jar") && !n.endsWith("-sources.jar") && Files.isRegularFile(p);
    }

    /** The descriptor text of a jar, or null; a jar that is not a zip is not a host. */
    static synchronized String probe(Path jar) {
        try {
            var a = Files.readAttributes(jar, java.nio.file.attribute.BasicFileAttributes.class);
            Probe p = PROBES.get(jar);
            if (p != null && p.size == a.size() && p.mtime == a.lastModifiedTime().toMillis()) return p.descriptor;
            String text = null;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                var e = zip.getEntry(JarIndex.DESCRIPTOR);
                if (e != null) try (var in = zip.getInputStream(e)) {
                    text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            PROBES.put(jar, new Probe(a.size(), a.lastModifiedTime().toMillis(), text));
            return text;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ convention

    private static Path conventionPolicy(Path file, List<String> notes) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path d = file.getParent();
        for (int up = 0; d != null && up <= MAX_UP; up++, d = d.getParent()) {
            List<Path> jmas = new ArrayList<>();
            try (Stream<Path> s = Files.list(d)) {
                s.limit(MAX_DIR_ENTRIES).filter(p -> p.getFileName().toString().endsWith(".jma") && Files.isRegularFile(p)).sorted().forEach(jmas::add);
            } catch (IOException | SecurityException e) {
                jmas = List.of();
            }
            if (jmas.size() == 1) return jmas.get(0);
            if (jmas.size() > 1) {
                // scripts/<name>/a.jmp -> <name>.jma
                for (Path seg : d.relativize(file.getParent())) {
                    for (Path jma : jmas) {
                        String n = jma.getFileName().toString();
                        if (n.substring(0, n.length() - 4).equals(seg.toString())) return jma;
                    }
                }
                List<String> names = new ArrayList<>();
                for (Path j : jmas) names.add(j.getFileName().toString());
                notes.add("several policies in " + d + " (" + String.join(", ", names) + ") and none is named after a folder of "
                        + "the script: checked without a policy. A host descriptor (" + JarIndex.DESCRIPTOR + ") would say which");
                return null;
            }
            if (d.equals(home)) break;
        }
        notes.add("no access policy (.jma) found above the script: checked without one");
        return null;
    }

    // ------------------------------------------------------------------ classpath

    private static List<Path> classpath(Path root, List<HostDescriptor> hosts, HostDescriptor owner) {
        java.util.Set<Path> otherHosts = new java.util.HashSet<>();
        for (HostDescriptor h : hosts) if (h != owner) otherHosts.add(h.jar().toAbsolutePath().normalize());
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, MAX_DEPTH)) {
            s.filter(Workspace::isJar).map(p -> p.toAbsolutePath().normalize())
                    .filter(p -> !otherHosts.contains(p)).limit(MAX_JARS).sorted().forEach(out::add);
        } catch (IOException | java.io.UncheckedIOException | SecurityException e) {
            // an unreadable part of the tree: what was found so far
        }
        if (owner != null) {
            Path own = owner.jar().toAbsolutePath().normalize();
            if (!out.contains(own)) out.add(own);
        }
        return List.copyOf(out);
    }
}
