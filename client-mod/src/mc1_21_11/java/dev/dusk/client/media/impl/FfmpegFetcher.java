package dev.dusk.client.media.impl;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Gets ffmpeg for EXPORT VIDEO when the player doesn't have it: the static
 * builds the ffmpeg-static npm package publishes on GitHub, checked against
 * their SHA-256 before use. They go in the launcher's shared tools folder
 * ({@code -Ddusk.tools}), or the instance's {@code dusk/tools} without it.
 */
public final class FfmpegFetcher {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Export");
    private static final String VERSION = "6.1.1";
    private static final String BASE = "https://github.com/eugeneware/ffmpeg-static/releases/download/b" + VERSION + "/";

    /** One build: the release asset, its SHA-256 and size. */
    private record Build(String asset, String sha256, long size) {}

    private enum State { IDLE, RUNNING, DONE, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile long got, total;
    private static volatile @Nullable String error;

    private FfmpegFetcher() {}

    private static @Nullable Build build() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return arm
                    ? new Build("ffmpeg-darwin-arm64.gz", "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa", 19246198)
                    : new Build("ffmpeg-darwin-x64.gz", "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106", 25296431);
        }
        // Windows on ARM runs the x64 build emulated
        if (os.startsWith("windows")) {
            return new Build("ffmpeg-win32-x64.gz", "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77", 29581307);
        }
        if (os.startsWith("linux") && x64) {
            return new Build("ffmpeg-linux-x64.gz", "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa", 29354986);
        }
        if (os.startsWith("linux") && arm) {
            return new Build("ffmpeg-linux-arm64.gz", "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0", 25568691);
        }
        return null;
    }

    /** Where a fetched ffmpeg lives, whether or not it's there yet. */
    static @Nullable Path target() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        String name = windows ? "ffmpeg.exe" : "ffmpeg";
        try {
            String prop = System.getProperty("dusk.tools");
            Path dir = prop != null && !prop.isBlank()
                    ? Path.of(prop)
                    : Minecraft.getInstance().gameDirectory.toPath().resolve("dusk").resolve("tools");
            return dir.resolve("ffmpeg-" + VERSION).resolve(name);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** True when this computer has a build to fetch. */
    public static boolean available() {
        return build() != null && target() != null;
    }

    public static boolean running() {
        return state == State.RUNNING;
    }

    /** True once, after a download finished. */
    public static boolean takeDone() {
        if (state != State.DONE) return false;
        state = State.IDLE;
        return true;
    }

    /** Why the last download failed, once, or null. */
    public static @Nullable String takeError() {
        if (state != State.FAILED) return null;
        state = State.IDLE;
        return error;
    }

    /** "FFMPEG 42%" for the export button while it downloads. */
    public static String progressLabel() {
        long t = total;
        return "FFMPEG " + (t <= 0 ? 0 : Math.min(99, got * 100 / t)) + "%";
    }

    /** The download size in MB, rounded, for the message that offers it. */
    public static long sizeMb() {
        Build b = build();
        return b == null ? 0 : Math.round(b.size() / 1_048_576.0);
    }

    /** Starts the download in the background; a second call while one runs does nothing. */
    public static synchronized void start() {
        if (state == State.RUNNING) return;
        Build b = build();
        Path dest = target();
        if (b == null || dest == null) {
            error = "There's no ffmpeg download for this computer. Install ffmpeg yourself and try again.";
            state = State.FAILED;
            return;
        }
        got = 0;
        total = b.size();
        error = null;
        state = State.RUNNING;
        Thread t = new Thread(() -> run(b, dest), "Dusk ffmpeg download");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Build b, Path dest) {
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Path gzPart = dest.resolveSibling(dest.getFileName() + ".gz.part");
        try {
            Files.createDirectories(dest.getParent());
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + b.asset()))
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "DuskClient")
                    .build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                throw new IOException("the download server answered " + res.statusCode());
            }
            // the checksum is of the .gz, so it's checked before anything is unpacked
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(new Counting(res.body()), sha)) {
                Files.copy(in, gzPart, StandardCopyOption.REPLACE_EXISTING);
            }
            String hex = HexFormat.of().formatHex(sha.digest());
            if (!hex.equals(b.sha256())) throw new IOException("the download was damaged (checksum mismatch)");
            try (InputStream gz = new GZIPInputStream(Files.newInputStream(gzPart), 1 << 16)) {
                Files.copy(gz, part, StandardCopyOption.REPLACE_EXISTING);
            }
            deleteQuietly(gzPart);
            if (!part.toFile().setExecutable(true)) LOG.warn("Couldn't mark {} executable", part);
            try {
                Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Fetched ffmpeg {} to {}", VERSION, dest);
            state = State.DONE;
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.warn("Couldn't fetch ffmpeg", e);
            fail("Couldn't download ffmpeg: " + e.getMessage());
            deleteQuietly(part);
            deleteQuietly(gzPart);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("The ffmpeg download was interrupted.");
            deleteQuietly(part);
            deleteQuietly(gzPart);
        }
    }

    private static void fail(String why) {
        error = why;
        state = State.FAILED;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    /** Counts compressed bytes as they arrive, for the progress label. */
    private static final class Counting extends FilterInputStream {
        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) got++;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) got += n;
            return n;
        }
    }
}
