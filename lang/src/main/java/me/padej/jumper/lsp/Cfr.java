package me.padej.jumper.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * The CFR decompiler (MIT license, https://www.benf.org/other/cfr/) that {@link Sources} runs for a class with
 * no source. It is not inside jmp.jar: the language server downloads it once, the first time a class needs it,
 * into Jumper's data folder next to the index cache - {@code %LOCALAPPDATA%\jumper\cfr\cfr-<version>.jar}
 * ({@code $XDG_CACHE_HOME/jumper/cfr}, {@code ~/.cache/jumper/cfr} elsewhere).
 *
 * <p>What is downloaded is pinned by the build: {@code META-INF/jumper/cfr.properties} in jmp.jar holds the
 * version, the Maven Central URL and the SHA-256 of the jar Gradle resolved for {@code org.benf:cfr} (see
 * lang/build.gradle.kts). A jar with another hash is never run - neither a download nor a file found in the
 * folder. A jmp.jar built without the pin downloads nothing.
 *
 * <p>Until CFR is there (no network, a proxy that refuses, a hash that does not match) a class with no source
 * opens as an outline, as before; a failed download is tried again after {@link #RETRY_MS}, not on every
 * request. {@code -Djmp.cfr} / {@code JMP_CFR}: a cfr.jar to run instead (nothing is downloaded), or {@code off}
 * for no decompiler at all.
 */
final class Cfr {
    private Cfr() {}

    static final String PIN_RESOURCE = "META-INF/jumper/cfr.properties";
    static final long RETRY_MS = 10 * 60_000;
    /** CFR is about 2 MB; a response larger than this is not it. */
    static final int MAX_BYTES = 32 << 20;

    /** What to download and how to recognize it. */
    record Pin(String version, URI url, String sha256) {}

    /** The decompiler to use: a getter that may download it on its first call; it gives null while there is none. */
    static Supplier<Path> find() {
        String set = System.getProperty("jmp.cfr", System.getenv("JMP_CFR"));
        if (set != null && !set.isBlank()) {
            if (set.strip().equalsIgnoreCase("off")) return () -> null;
            Path p = Path.of(set.strip());
            return () -> Files.isRegularFile(p) ? p : null;
        }
        Pin pin = pin();
        if (pin == null) return () -> null;
        return new Installer(dir(), pin)::jar;
    }

    /** Where it is installed: {@code <Jumper's data folder>/cfr}. */
    static Path dir() {
        return me.padej.jumper.workspace.IndexCache.defaultDir().getParent().resolve("cfr");
    }

    /** The pin jmp.jar carries, or null (a build without it, a broken file). */
    static Pin pin() {
        try (InputStream in = Cfr.class.getClassLoader().getResourceAsStream(PIN_RESOURCE)) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version"), url = p.getProperty("url"), sha = p.getProperty("sha256");
            if (v == null || url == null || sha == null || !v.matches("[\\w.-]+") || !sha.matches("[0-9a-f]{64}")) return null;
            return new Pin(v, URI.create(url), sha);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One installation of one pinned version: finds it in the folder, or downloads it there. */
    static final class Installer {
        private final Path dir;
        private final Pin pin;
        /** The jar checked against the pin in this process. */
        private Path verified;
        /** No new attempt before this moment (a failed download). */
        private long retryAt;
        int downloads;

        Installer(Path dir, Pin pin) {
            this.dir = dir;
            this.pin = pin;
        }

        Path file() {
            return dir.resolve("cfr-" + pin.version() + ".jar");
        }

        /** The installed jar, downloading it first if it is not there yet; null if that is not possible now. */
        synchronized Path jar() {
            Path f = file();
            if (verified != null && Files.isRegularFile(verified)) return verified;
            try {
                if (Files.isRegularFile(f) && pin.sha256().equals(sha256(Files.readAllBytes(f)))) return verified = f;
            } catch (IOException e) {
                // unreadable: download it again
            }
            if (System.currentTimeMillis() < retryAt) return null;
            try {
                byte[] bytes = download();
                String got = sha256(bytes);
                if (!pin.sha256().equals(got))
                    throw new IOException("the download from " + pin.url() + " is not CFR " + pin.version() + " (SHA-256 " + got + ")");
                Files.createDirectories(dir);
                Path part = Files.createTempFile(dir, "cfr-", ".part");
                try {
                    Files.write(part, bytes);
                    try {
                        Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(part, f, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(part);
                }
                System.err.println("[jumper-lsp] CFR " + pin.version() + " installed: " + f);
                return verified = f;
            } catch (IOException | RuntimeException e) {
                return failed(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failed("interrupted");
            }
        }

        private Path failed(String why) {
            retryAt = System.currentTimeMillis() + RETRY_MS;
            System.err.println("[jumper-lsp] CFR " + pin.version() + " is not installed (" + why
                    + "): classes with no source open as outlines; put it at " + file() + " or set -Djmp.cfr");
            return null;
        }

        private byte[] download() throws IOException, InterruptedException {
            downloads++;
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(pin.url()).timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + pin.url());
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("more than " + MAX_BYTES + " bytes from " + pin.url());
                return bytes;
            }
        }
    }
}
