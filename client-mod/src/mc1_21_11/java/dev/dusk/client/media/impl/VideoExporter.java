package dev.dusk.client.media.impl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.media.Mcpr;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * EXPORT VIDEO: plays the marked stretch of a replay frame by frame at a
 * fixed rate and pipes the frames to ffmpeg, which is how ReplayMod's
 * renderer works (behaviour only; none of its code). The game's frame
 * clock follows the frame count instead of the wall clock
 * ({@code VideoClockMixin}), so frames land exactly 1/fps of replay apart
 * however long each takes to draw, and each frame is read back right after
 * the world is drawn, before the HUD ({@code VideoFrameMixin}). No audio.
 *
 * <p>Going back to the in mark reloads the world, so an export first seeks,
 * waits for every chunk to be built, then starts.
 */
public final class VideoExporter {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Export");
    public static final int[] FPS = {30, 60};
    private static final byte[] END = new byte[0];
    private static final int SETTLE_FRAMES = 20;
    private static final long SETTLE_TIMEOUT_MS = 20_000, FLUSH_TIMEOUT_MS = 5_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    private enum Stage { SEEKING, SETTLING, CAPTURING, FLUSHING }

    private static @Nullable VideoExporter current;
    /** What the frame clock adds to the wall clock, so it never runs backwards after an export ran ahead of it. */
    private static long offset;
    /** The last time handed to the frame clock. */
    private static long last;
    /** How the last export went, for the replay menu. */
    private static @Nullable String lastResult;

    private final ReplayPlayer replay;
    private final long from, to;
    private final int fps;
    private final Path exe, out;
    private final List<String> codec;
    private Stage stage = Stage.SEEKING;
    private long stageStart = Util.getMillis();
    private int settleFrames;
    private long base, frames;
    private int pending;
    private int width, height;
    private boolean done;
    private @Nullable Process ffmpeg;
    private @Nullable Thread writer;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(6);
    private final ArrayBlockingQueue<byte[]> spare = new ArrayBlockingQueue<>(8);
    private volatile @Nullable String failure;

    // the player's settings, put back afterwards
    private final int savedFpsLimit;
    private final boolean savedVsync;
    private final InactivityFpsLimit savedInactivity;

    private VideoExporter(ReplayPlayer replay, long from, long to, int fps, Path exe, List<String> codec, Path out, Options o) {
        this.replay = replay;
        this.from = from;
        this.to = to;
        this.fps = fps;
        this.exe = exe;
        this.codec = codec;
        this.out = out;
        savedFpsLimit = o.framerateLimit().get();
        savedVsync = o.enableVsync().get();
        savedInactivity = o.inactivityFpsLimit().get();
        // as fast as the machine draws: the frame clock, not the wall clock, keeps time
        o.framerateLimit().set(260);
        o.enableVsync().set(false);
        o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
    }

    public static boolean active() {
        return current != null;
    }

    /** @return why it could not start, or null once it has */
    public static @Nullable String start(ReplayPlayer p, int fps) {
        if (current != null) return "Already exporting.";
        Path exe = findFfmpeg();
        if (exe == null) {
            return "Exporting needs ffmpeg. Install it (brew install ffmpeg, or winget install ffmpeg on Windows) and try again.";
        }
        List<String> codec = codecArgs(exe);
        long from = p.markIn() >= 0 ? p.markIn() : p.clock();
        long to = p.markOut() > from ? p.markOut() : p.duration;
        if (to - from < 500) return "Nothing to export from here. Mark an in point (I) and an out point (O) first.";
        Path out;
        try {
            Path dir = Mcpr.videosDir();
            Files.createDirectories(dir);
            String base = LocalDateTime.now().format(STAMP) + "_" + Mcpr.duration(from).replace(':', '.') + "-"
                    + Mcpr.duration(to).replace(':', '.');
            out = dir.resolve(base + ".mp4");
            for (int i = 2; Files.exists(out); i++) out = dir.resolve(base + "_" + i + ".mp4");
        } catch (IOException e) {
            return "Can't write to the videos folder: " + e.getMessage();
        }
        Minecraft mc = Minecraft.getInstance();
        current = new VideoExporter(p, from, to, fps, exe, codec, out, mc.options);
        lastResult = null;
        LOG.info("Exporting {} {}..{} ms at {} fps to {}", p.title, from, to, fps, out);
        Compat.setScreen(mc, null);
        p.setPaused(true);
        if (from != p.clock()) p.seek(from);
        return null;
    }

    /**
     * ffmpeg from {@code -Ddusk.ffmpeg}, PATH, where Homebrew and the usual
     * installers put it, or the copy {@link FfmpegFetcher} downloaded.
     */
    static @Nullable Path findFfmpeg() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        String name = windows ? "ffmpeg.exe" : "ffmpeg";
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) dirs.addAll(List.of(path.split(File.pathSeparator)));
        // a launcher started from the dock doesn't get the shell's PATH
        dirs.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"));
        List<Path> candidates = new ArrayList<>();
        try {
            String prop = System.getProperty("dusk.ffmpeg");
            if (prop != null && !prop.isBlank()) candidates.add(Path.of(prop));
            for (String d : dirs) if (!d.isBlank()) candidates.add(Path.of(d, name));
        } catch (InvalidPathException ignored) {
        }
        Path fetched = FfmpegFetcher.target();
        if (fetched != null) candidates.add(fetched);
        for (Path c : candidates) if (Files.isRegularFile(c) && Files.isExecutable(c)) return c;
        return null;
    }

    /** The encoder settings for each ffmpeg, worked out once. */
    private static final java.util.Map<Path, List<String>> CODECS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * H.264 with x264 when this ffmpeg has it, else the system's own H.264
     * encoder (VideoToolbox on macOS, Media Foundation on Windows), else
     * OpenH264, else MPEG-4 Part 2, which every build has.
     */
    static List<String> codecArgs(Path exe) {
        return CODECS.computeIfAbsent(exe, e -> {
            String list = encoders(e);
            if (list.contains(" libx264 ")) return List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18");
            if (list.contains(" h264_videotoolbox ")) return List.of("-c:v", "h264_videotoolbox", "-b:v", "16M");
            if (list.contains(" h264_mf ")) return List.of("-c:v", "h264_mf", "-b:v", "16M");
            if (list.contains(" libopenh264 ")) return List.of("-c:v", "libopenh264", "-b:v", "16M");
            if (list.isEmpty()) return List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18");
            return List.of("-c:v", "mpeg4", "-q:v", "2");
        });
    }

    private static String encoders(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "-hide_banner", "-encoders")
                    .redirectErrorStream(true)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Couldn't list {}'s encoders", exe, e);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    // ---- the frame clock ----------------------------------------------------------

    /** What {@code Minecraft.runTick} hands its frame timer, in place of the wall clock. */
    public static long millis(long real) {
        VideoExporter e = current;
        last = e != null && e.stage.ordinal() >= Stage.CAPTURING.ordinal() ? e.base + e.frames * 1000L / e.fps : real + offset;
        return last;
    }

    // ---- frames -------------------------------------------------------------------

    /** Right after the world is drawn, before the HUD goes over it. */
    public static void onFrame() {
        VideoExporter e = current;
        if (e != null) e.frame(Minecraft.getInstance());
    }

    private void frame(Minecraft mc) {
        String why = failure;
        if (why != null) {
            stop(why);
            return;
        }
        if (ReplayPlayer.current() != replay || replay.closed()) {
            stop("The replay closed.");
            return;
        }
        switch (stage) {
            case SEEKING -> {
                if (replay.settled()) enter(Stage.SETTLING);
            }
            case SETTLING -> {
                settleFrames++;
                boolean built = mc.levelRenderer.hasRenderedAllSections();
                if (built && settleFrames >= SETTLE_FRAMES || Util.getMillis() - stageStart > SETTLE_TIMEOUT_MS) begin(mc);
            }
            case CAPTURING -> capture(mc);
            case FLUSHING -> {
                if (pending <= 0 || Util.getMillis() - stageStart > FLUSH_TIMEOUT_MS) finish();
            }
        }
    }

    private void enter(Stage s) {
        stage = s;
        stageStart = Util.getMillis();
    }

    private void begin(Minecraft mc) {
        RenderTarget t = Compat.mainTarget(mc);
        width = t.width;
        height = t.height;
        try {
            ffmpeg = launch(mc);
        } catch (IOException e) {
            LOG.warn("Couldn't start {}", exe, e);
            stop("Couldn't start ffmpeg: " + e.getMessage());
            return;
        }
        Thread w = new Thread(this::write, "Dusk video export");
        w.setDaemon(true);
        w.start();
        writer = w;
        base = last + 1000L / fps;
        frames = 0;
        replay.setPaused(false);
        enter(Stage.CAPTURING);
    }

    private Process launch(Minecraft mc) throws IOException {
        Path log = mc.gameDirectory.toPath().resolve("logs").resolve("dusk-ffmpeg.log");
        Files.createDirectories(log.getParent());
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-y", "-hide_banner", "-loglevel", "warning",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-",
                // H.264 wants even sizes
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2"));
        cmd.addAll(codec);
        cmd.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toString()));
        return new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(log.toFile())
                .start();
    }

    private void capture(Minecraft mc) {
        if (replay.clock() >= to) {
            enter(Stage.FLUSHING);
            return;
        }
        if (replay.paused()) replay.setPaused(false);
        RenderTarget t = Compat.mainTarget(mc);
        if (t.width != width || t.height != height) {
            stop("The window changed size, so the export stopped. Keep it the same size while exporting.");
            return;
        }
        pending++;
        frames++;
        Screenshot.takeScreenshot(t, this::accept);
    }

    /** A frame back from the GPU, in order; the copy goes to the writer, waiting if it has fallen behind. */
    private void accept(NativeImage img) {
        try (img) {
            pending--;
            if (done && stage != Stage.FLUSHING || failure != null) return;
            if (img.getWidth() != width || img.getHeight() != height) return;
            int n = width * height * 4;
            byte[] b = spare.poll();
            if (b == null || b.length != n) b = new byte[n];
            MemoryUtil.memByteBuffer(img.getPointer(), n).get(b);
            while (failure == null && !queue.offer(b, 200, TimeUnit.MILLISECONDS)) {
                // the encoder is behind; hold the game until it catches up
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write() {
        Process proc = ffmpeg;
        if (proc == null) return;
        try (OutputStream os = new BufferedOutputStream(proc.getOutputStream(), 1 << 20)) {
            while (true) {
                byte[] b = queue.take();
                if (b == END) break;
                os.write(b);
                spare.offer(b);
            }
        } catch (IOException e) {
            LOG.warn("ffmpeg stopped taking frames", e);
            if (failure == null) failure = "ffmpeg stopped taking frames (logs/dusk-ffmpeg.log says why).";
            return;
        } catch (InterruptedException e) {
            return;
        }
        int code;
        try {
            code = proc.waitFor();
        } catch (InterruptedException e) {
            return;
        }
        String result = code == 0 ? null : "ffmpeg failed (exit " + code + "); logs/dusk-ffmpeg.log says why.";
        Minecraft.getInstance().execute(() -> written(result));
    }

    private void written(@Nullable String error) {
        if (error != null) {
            deleteOutput();
            report(error);
            return;
        }
        LOG.info("Exported {} ({} frames)", out, frames);
        report("Saved " + out.getFileName() + " to the videos folder.");
    }

    // ---- ending -------------------------------------------------------------------

    /** All frames are in: let ffmpeg finish the file in the background. */
    private void finish() {
        done = true;
        restore();
        lastResult = "Encoding " + out.getFileName() + "…";
        while (failure == null) {
            try {
                if (queue.offer(END, 200, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        written(failure);
    }

    /** Stops early and throws the partial file away. */
    private void stop(String why) {
        if (done) return;
        done = true;
        restore();
        queue.clear();
        Thread w = writer;
        if (w != null) w.interrupt();
        Process proc = ffmpeg;
        if (proc != null) {
            proc.destroyForcibly();
            proc.onExit().thenRun(this::deleteOutput);
        } else {
            deleteOutput();
        }
        LOG.info("Export stopped: {}", why);
        report(why);
    }

    private void restore() {
        if (current == this) current = null;
        offset = last - Util.getMillis();
        Options o = Minecraft.getInstance().options;
        o.framerateLimit().set(savedFpsLimit);
        o.enableVsync().set(savedVsync);
        o.inactivityFpsLimit().set(savedInactivity);
        if (!replay.closed()) replay.setPaused(true);
    }

    private void deleteOutput() {
        try {
            Files.deleteIfExists(out);
        } catch (IOException ignored) {
        }
    }

    private static void report(String message) {
        lastResult = message;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) MediaCompat.message(mc.player, Component.literal(message), false);
    }

    /** Esc menu: CANCEL EXPORT. */
    public static void cancel() {
        VideoExporter e = current;
        if (e != null) e.stop("Export cancelled.");
    }

    static void replayClosed() {
        VideoExporter e = current;
        if (e != null) e.stop("The replay closed, so the export stopped.");
    }

    // ---- what the menu and HUD show -----------------------------------------------

    /** 0..1 through the marked stretch; 0 until frames are being written. */
    public static float progress() {
        VideoExporter e = current;
        if (e == null || e.stage.ordinal() < Stage.CAPTURING.ordinal()) return 0;
        return Math.max(0, Math.min(1f, (e.replay.clock() - e.from) / (float) Math.max(1, e.to - e.from)));
    }

    public static String status() {
        VideoExporter e = current;
        if (e == null) return "";
        return switch (e.stage) {
            case SEEKING -> "Going to " + Mcpr.duration(e.from) + "…";
            case SETTLING -> "Loading chunks…";
            case CAPTURING -> Math.round(progress() * 100) + "%   " + Mcpr.duration(e.replay.clock()) + " / "
                    + Mcpr.duration(e.to) + "   " + e.fps + " fps";
            case FLUSHING -> "Finishing…";
        };
    }

    /** The outcome of the last export (saved, cancelled, failed), or null. */
    public static @Nullable String lastResult() {
        return lastResult;
    }
}
