package dev.dusk.client.media;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The media folders and ReplayMod's {@code .mcpr} file: a zip holding
 * {@code metaData.json}, {@code recording.tmcpr} (packets as
 * {@code [int ms][int length][bytes]}, big-endian) and {@code thumb.png}.
 * Same layout as ReplayMod's files; opening them in ReplayMod is untested.
 */
public final class Mcpr {
    public static final String META = "metaData.json", RECORDING = "recording.tmcpr", THUMB = "thumb.png";
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    /** One screenshot, clip or replay on disk. {@code durationMs} and {@code server} are only set for recordings. */
    public record Entry(Path path, String name, long modified, long size, long durationMs, String server) {}

    private Mcpr() {}

    public static Path screenshotsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
    }

    public static Path clipsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("clips");
    }

    public static Path replaysDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("replays");
    }

    /** Where EXPORT VIDEO writes its .mp4 files. */
    public static Path videosDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("videos");
    }

    /** A fresh file name in {@code dir}: {@code <prefix>yyyy-MM-dd_HH-mm-ss.mcpr}, suffixed if taken. */
    public static Path newFile(Path dir, String prefix) {
        String base = prefix + LocalDateTime.now().format(STAMP);
        Path p = dir.resolve(base + ".mcpr");
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + "_" + i + ".mcpr");
        return p;
    }

    /** Every file in {@code dir} ending in {@code ext}, newest first; recordings carry their metadata. Blocking. */
    public static List<Entry> list(Path dir, String ext) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(ext) || !Files.isRegularFile(p)) continue;
                try {
                    long duration = 0;
                    String server = "";
                    if (ext.equals(".mcpr")) {
                        JsonObject meta = readMeta(p);
                        if (meta != null) {
                            if (meta.has("duration")) duration = meta.get("duration").getAsLong();
                            boolean single = meta.has("singleplayer") && meta.get("singleplayer").getAsBoolean();
                            server = single ? "Singleplayer" : meta.has("serverName") ? meta.get("serverName").getAsString() : "";
                        }
                    }
                    out.add(new Entry(p, name, Files.getLastModifiedTime(p).toMillis(), Files.size(p), duration, server));
                } catch (IOException | RuntimeException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparingLong(Entry::modified).reversed());
        return out;
    }

    @Nullable
    public static JsonObject readMeta(Path file) {
        byte[] b = readEntry(file, META);
        if (b == null) return null;
        try {
            return GSON.fromJson(new String(b, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static byte @Nullable [] readThumb(Path file) {
        return readEntry(file, THUMB);
    }

    private static byte @Nullable [] readEntry(Path file, String name) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Opens the recording stream of {@code file}; close the returned zip when done. */
    public static ZipFile open(Path file) throws IOException {
        return new ZipFile(file.toFile());
    }

    /**
     * Writes {@code out} (via a temp file, so a crash never leaves half a
     * replay under the real name) from the metadata, the packet stream
     * {@code recording} and an optional thumbnail.
     */
    public static void write(Path out, JsonObject meta, Path recording, byte @Nullable [] thumb) throws IOException {
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            zip.putNextEntry(new ZipEntry(META));
            zip.write(GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(RECORDING));
            Files.copy(recording, zip);
            zip.closeEntry();
            if (thumb != null) {
                zip.putNextEntry(new ZipEntry(THUMB));
                zip.write(thumb);
                zip.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Big-endian int, as the tmcpr stream stores its headers. */
    public static void writeInt(OutputStream out, int v) throws IOException {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    /** {@code 1:05} or {@code 1:02:03}. */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public static String size(long bytes) {
        if (bytes >= 1 << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1 << 30));
        if (bytes >= 1 << 20) return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1 << 20));
        return Math.max(1, bytes / 1024) + " KB";
    }

    public static void openInOs(Path path) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
        try {
            new ProcessBuilder(cmd, path.toAbsolutePath().toString()).start();
        } catch (IOException ignored) {
        }
    }
}
