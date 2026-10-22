package dev.dusk.client.media.impl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.media.Mcpr;
import dev.dusk.client.perf.Perf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Screen recording, the way OBS does it rather than a world replay: what
 * the window shows (with or without the HUD), at a steady frame rate, with
 * the game's sound, encoded live by the GPU's encoder where there is one.
 * Two ways to use it, together or apart: a replay buffer that always holds
 * the last stretch so the clip key can save it after the fact, and a record
 * key that starts and stops a recording.
 *
 * <p>Frames come back from the GPU without stalling ({@link FrameGrab}) on
 * a wall-clock grid: one per slot, repeated when the game drew slower than
 * the video's rate and skipped when the encoder is behind, so the video
 * never drifts from the sound. ffmpeg writes MPEG-TS, which
 * {@link TsBuffer} cuts at each keyframe, so saving a clip is a copy and a
 * remux into MP4, never a re-encode.
 */
public final class ScreenRecorder {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Recorder");
    public static final int[] FPS = {30, 60};
    public static final String[] RESOLUTIONS = {"native", "1440", "1080", "720"};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final String PARTIAL = ".recording.ts";
    /** Ticks the window has to keep one size before the recorder restarts at it. */
    private static final int RESIZE_SETTLE = 20;
    private static final int MAX_FAILURES = 3;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Dusk video save");
        t.setDaemon(true);
        return t;
    });

    private static @Nullable Session session;
    private static boolean starting, waitingForFfmpeg, recoveredPartials;
    /** The record key: whether a recording should be running, and the file it goes to. */
    private static boolean recordWanted;
    private static @Nullable Path recordingTs;
    private static long recordingStartMs, recordedBeforeMs;
    private static int resizeTicks, failures;
    private static long retryAt;
    private static @Nullable String lastResult;

    private ScreenRecorder() {}

    // ---- what the rest of the client asks ---------------------------------------------

    public static boolean enabled() {
        return !DuskConfig.get().videoMode.equals("off");
    }

    /** Whether the replay buffer is on (it fills while you are in a world). */
    public static boolean bufferOn() {
        return DuskConfig.get().videoMode.equals("buffer");
    }

    public static boolean recording() {
        return recordWanted;
    }

    public static long recordingMs() {
        if (!recordWanted) return 0;
        return recordedBeforeMs + (recordingTs != null ? System.currentTimeMillis() - recordingStartMs : 0);
    }

    public static @Nullable String lastResult() {
        return lastResult;
    }

    /** The encoder in use, e.g. "VideoToolbox (GPU)", or null while none runs. */
    public static @Nullable String encoder() {
        Session s = session;
        return s == null ? null : VideoEncoders.label(s.enc.name());
    }

    public static String status() {
        DuskConfig c = DuskConfig.get();
        if (c.videoMode.equals("off")) return "Screen recording is off";
        if (waitingForFfmpeg) return "Getting ffmpeg… " + FfmpegFetcher.progressLabel();
        if (starting) return "Starting the encoder…";
        Session s = session;
        if (s == null) {
            if (failures >= MAX_FAILURES) return lastResult != null ? lastResult : "The encoder kept failing";
            return bufferOn() ? "The buffer starts when you join a world" : "Press the record key to start";
        }
        StringBuilder b = new StringBuilder();
        if (recordWanted) b.append("Recording ").append(Mcpr.duration(recordingMs())).append(" · ");
        else if (bufferOn()) b.append("Keeping the last ").append(c.videoSeconds).append("s · ");
        b.append(VideoEncoders.label(s.enc.name())).append(" · ").append(s.outW).append('x').append(s.outH).append(' ').append(s.fps).append(" fps");
        if (s.overloaded) b.append(" · encoder can't keep up, lower the fps or quality");
        return b.toString();
    }

    // ---- ticking ----------------------------------------------------------------------

    public static void tick(Minecraft mc) {
        if (!recoveredPartials) {
            recoveredPartials = true;
            IO.execute(ScreenRecorder::recoverPartials);
        }
        if (waitingForFfmpeg) {
            String err = FfmpegFetcher.takeError();
            if (err != null) {
                waitingForFfmpeg = false;
                recordWanted = false;
                report(mc, "Couldn't get ffmpeg: " + err);
            } else if (FfmpegFetcher.takeDone()) {
                waitingForFfmpeg = false;
            } else {
                return;
            }
        }
        if (!enabled()) recordWanted = false;
        boolean want = enabled() && (recordWanted || bufferOn() && mc.level != null);
        Session s = session;
        if (s != null) {
            String why = s.failure;
            if (why != null) {
                failures++;
                retryAt = System.currentTimeMillis() + 5_000;
                endSession(s, false);
                report(mc, failures >= MAX_FAILURES ? why + " Screen recording stopped; change a setting to try again." : why);
                return;
            }
            if (!want) {
                endSession(s, true);
                return;
            }
            RenderTarget t = Compat.mainTarget(mc);
            if (t.width != s.inW || t.height != s.inH) {
                if (++resizeTicks >= RESIZE_SETTLE) {
                    LOG.info("The window changed size; restarting the recorder at {}x{}", t.width, t.height);
                    endSession(s, true);
                }
            } else {
                resizeTicks = 0;
            }
            return;
        }
        if (want && !starting && failures < MAX_FAILURES && System.currentTimeMillis() >= retryAt) begin(mc);
    }

    private static final Perf.Section RECORDER = Perf.part("Screen recorder");

    /** The render hooks: after the world is drawn ({@code hud} false), and once the HUD is on it too. */
    public static void onFrame(boolean hud) {
        Session s = session;
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        boolean world = mc.level != null && !DuskConfig.get().videoHud;
        // in the menus there is no world pass, so the finished frame it is
        if (hud == world) return;
        long t0 = Perf.start();
        s.frame(mc);
        Perf.end(RECORDER, Perf.WORLD, t0);
    }

    // ---- keys -------------------------------------------------------------------------

    /** The clip key, for the screen buffer: saves the last stretch to an MP4. */
    public static void saveBuffer() {
        Minecraft mc = Minecraft.getInstance();
        Session s = session;
        if (s == null || s.buffer == null || !s.running) {
            report(mc, starting ? "The recorder is still starting." : "Nothing to save yet: the buffer fills while you're in a world.");
            return;
        }
        TsBuffer.Span span = s.buffer.hold(Math.max(5, DuskConfig.get().videoSeconds));
        if (span == null) {
            report(mc, "Nothing to save yet.");
            return;
        }
        Path out = newFile("clip_");
        boolean hevc = s.enc.hevc();
        Path exe = s.exe;
        TsBuffer buf = s.buffer;
        IO.execute(() -> {
            Path tmp = out.resolveSibling(out.getFileName() + ".part.ts");
            try {
                buf.write(span, tmp);
                String err = remux(exe, tmp, out, hevc);
                report(mc, err != null ? err : "Saved the last " + Math.round(Math.min(span.seconds(), DuskConfig.get().videoSeconds)) + "s to " + out.getFileName() + ".");
            } catch (IOException e) {
                LOG.warn("Couldn't save the buffer", e);
                report(mc, "Couldn't save the clip: " + e.getMessage());
            } finally {
                deleteQuietly(tmp);
            }
        });
    }

    /** The record key: starts a recording, or stops and saves the one running. */
    public static void toggleRecording() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled()) {
            report(mc, "Screen recording is off. Turn it on in Media → Settings.");
            return;
        }
        if (recordWanted) {
            stopRecording(mc);
            return;
        }
        recordWanted = true;
        recordedBeforeMs = 0;
        failures = 0;
        retryAt = 0;
        Session s = session;
        if (s != null && s.running) startRecordingFile(mc, s);
        // otherwise the session that starts next tick picks it up
    }

    private static void startRecordingFile(Minecraft mc, Session s) {
        TsBuffer buf = s.buffer;
        if (buf == null) return;
        Path mp4 = newFile("recording_");
        Path ts = mp4.resolveSibling(mp4.getFileName().toString().replace(".mp4", PARTIAL));
        try {
            buf.startRecording(ts);
        } catch (IOException e) {
            LOG.warn("Couldn't start a recording", e);
            recordWanted = false;
            report(mc, "Couldn't start recording: " + e.getMessage());
            return;
        }
        recordingTs = ts;
        recordingStartMs = System.currentTimeMillis();
        if (recordedBeforeMs == 0) report(mc, "Recording. Press the record key again to stop.");
    }

    private static void stopRecording(Minecraft mc) {
        recordWanted = false;
        Session s = session;
        // manual mode with no buffer: the session ends, and its tail goes in the file first
        if (s != null && !bufferOn()) {
            endSession(s, true);
            return;
        }
        if (s != null) finishRecordingFile(mc, s, true);
    }

    /** Ends the current recording file and turns it into an MP4. */
    private static void finishRecordingFile(Minecraft mc, Session s, boolean announce) {
        Path ts = recordingTs;
        recordingTs = null;
        if (ts == null || s.buffer == null) return;
        recordedBeforeMs += System.currentTimeMillis() - recordingStartMs;
        long bytes = s.buffer.stopRecording();
        Path exe = s.exe;
        boolean hevc = s.enc.hevc();
        IO.execute(() -> {
            if (bytes < TsBuffer.PACKET * 16L) {
                deleteQuietly(ts);
                if (announce) report(mc, "The recording was too short to keep.");
                return;
            }
            Path out = ts.resolveSibling(ts.getFileName().toString().replace(PARTIAL, ".mp4"));
            String err = remux(exe, ts, out, hevc);
            if (err == null) deleteQuietly(ts);
            if (announce) report(mc, err != null ? err : "Saved " + out.getFileName() + " to the videos folder.");
        });
    }

    // ---- settings ---------------------------------------------------------------------

    /** After the Media settings change: restarts at the new settings, and reloads sound if routing changed. */
    public static void settingsChanged() {
        Minecraft mc = Minecraft.getInstance();
        failures = 0;
        retryAt = 0;
        Session s = session;
        if (s != null && !s.matches(DuskConfig.get())) endSession(s, true);
        if (GameAudio.needsReload()) {
            LOG.info("Reloading sound so game audio {} the recorder", GameAudio.wanted() ? "reaches" : "leaves");
            mc.getSoundManager().reload();
            // a session started before the reload has no sound; start again with it
            Session now = session;
            if (now != null) endSession(now, true);
        }
    }

    // ---- sessions ---------------------------------------------------------------------

    private static void begin(Minecraft mc) {
        RenderTarget t = Compat.mainTarget(mc);
        DuskConfig c = DuskConfig.get();
        int inW = t.width, inH = t.height;
        if (inW < 16 || inH < 16) return;
        int[] out = outputSize(inW, inH, c.videoResolution);
        int fps = c.videoFps >= 45 ? 60 : 30;
        String quality = c.videoQuality, codec = c.videoCodec;
        boolean audio = c.videoAudio && GameAudio.routed();
        int keep = Math.max(5, c.videoSeconds);
        Path cache = mc.gameDirectory.toPath().resolve("dusk").resolve("video-buffer");
        Path log = mc.gameDirectory.toPath().resolve("logs").resolve("dusk-recorder.log");
        starting = true;
        resizeTicks = 0;
        Thread th = new Thread(() -> {
            Session s = null;
            String why = null;
            try {
                Path exe = VideoExporter.findFfmpeg();
                if (exe == null) {
                    mc.execute(() -> needFfmpeg(mc));
                    return;
                }
                VideoEncoders.Choice enc = VideoEncoders.pick(exe, codec, quality, out[0], out[1], fps);
                s = new Session(exe, enc, inW, inH, out[0], out[1], fps, quality, codec, audio, keep);
                s.launch(cache, log);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Couldn't start the screen recorder", e);
                why = "Couldn't start the screen recorder: " + e.getMessage();
                if (s != null) s.kill();
                s = null;
            } finally {
                Session started = s;
                String failure = why;
                mc.execute(() -> started(mc, started, failure));
            }
        }, "Dusk recorder start");
        th.setDaemon(true);
        th.start();
    }

    private static void started(Minecraft mc, @Nullable Session s, @Nullable String why) {
        starting = false;
        if (s == null) {
            if (why != null) {
                failures++;
                retryAt = System.currentTimeMillis() + 5_000;
                report(mc, why);
            }
            return;
        }
        boolean want = enabled() && (recordWanted || bufferOn() && mc.level != null);
        RenderTarget t = Compat.mainTarget(mc);
        if (!want || t.width != s.inW || t.height != s.inH || !s.matches(DuskConfig.get())) {
            s.close(null);
            return;
        }
        session = s;
        s.start();
        if (recordWanted) startRecordingFile(mc, s);
    }

    private static void needFfmpeg(Minecraft mc) {
        if (FfmpegFetcher.available()) {
            waitingForFfmpeg = true;
            FfmpegFetcher.start();
            report(mc, "Screen recording needs ffmpeg; getting it now (" + FfmpegFetcher.sizeMb() + " MB). Recording starts once it's in.");
        } else {
            recordWanted = false;
            failures = MAX_FAILURES;
            report(mc, "Screen recording needs ffmpeg. Install it (brew install ffmpeg, or winget install ffmpeg on Windows) and change a setting to try again.");
        }
    }

    /** Ends a session: a recording in it is finished into its file, and continues in the next one when {@code keepRecording}. */
    private static void endSession(Session s, boolean keepRecording) {
        if (session == s) session = null;
        Minecraft mc = Minecraft.getInstance();
        boolean rec = recordingTs != null;
        Path ts = recordingTs;
        if (rec) {
            recordedBeforeMs += System.currentTimeMillis() - recordingStartMs;
            recordingTs = null;
        }
        boolean announce = !recordWanted || !keepRecording;
        if (!keepRecording) recordWanted = false;
        s.close(ts == null ? null : () -> {
            // the encoder has written everything, so the file ends where the recording stopped
            long bytes = s.buffer != null ? s.buffer.stopRecording() : 0;
            Path exe = s.exe;
            boolean hevc = s.enc.hevc();
            IO.execute(() -> {
                if (bytes < TsBuffer.PACKET * 16L) {
                    deleteQuietly(ts);
                    return;
                }
                Path out = ts.resolveSibling(ts.getFileName().toString().replace(PARTIAL, ".mp4"));
                String err = remux(exe, ts, out, hevc);
                if (err == null) deleteQuietly(ts);
                if (announce || err != null) report(mc, err != null ? err : "Saved " + out.getFileName() + " to the videos folder.");
            });
        });
    }

    static int[] outputSize(int w, int h, String resolution) {
        int cap = switch (resolution) {
            case "1440" -> 1440;
            case "1080" -> 1080;
            case "720" -> 720;
            default -> Integer.MAX_VALUE;
        };
        int oh = Math.min(h, cap);
        int ow = (int) Math.round(w * (oh / (double) h));
        return new int[]{ow & ~1, oh & ~1};
    }

    // ---- files ------------------------------------------------------------------------

    private static Path newFile(String prefix) {
        Path dir = Mcpr.videosDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        String base = prefix + LocalDateTime.now().format(STAMP);
        Path p = dir.resolve(base + ".mp4");
        for (int i = 2; Files.exists(p) || Files.exists(dir.resolve(p.getFileName().toString().replace(".mp4", PARTIAL))); i++) {
            p = dir.resolve(base + "_" + i + ".mp4");
        }
        return p;
    }

    /** MPEG-TS to MP4 without re-encoding. @return why it failed, or null */
    private static @Nullable String remux(Path exe, Path in, Path out, boolean hevc) {
        // under a name the MEDIA lists skip until it is whole
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-y", "-hide_banner", "-loglevel", "error",
                "-i", in.toString(), "-map", "0", "-c", "copy", "-movflags", "+faststart"));
        if (hevc) cmd.addAll(List.of("-tag:v", "hvc1"));
        cmd.addAll(List.of("-f", "mp4", tmp.toString()));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] log = p.getInputStream().readNBytes(8192);
            p.getInputStream().close();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                deleteQuietly(tmp);
                return "Saving the video took too long.";
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(tmp)) {
                LOG.warn("Remux of {} failed: {}", in, new String(log, java.nio.charset.StandardCharsets.UTF_8));
                deleteQuietly(tmp);
                return "Couldn't save the video (ffmpeg exit " + p.exitValue() + ").";
            }
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return null;
        } catch (IOException e) {
            deleteQuietly(tmp);
            return "Couldn't save the video: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Saving the video was interrupted.";
        }
    }

    /** A recording the game didn't get to finish (it crashed or was killed): make its MP4 now. */
    private static void recoverPartials() {
        Path dir = Mcpr.videosDir();
        if (!Files.isDirectory(dir)) return;
        List<Path> partials;
        try (var s = Files.list(dir)) {
            partials = s.filter(p -> p.getFileName().toString().endsWith(PARTIAL)).toList();
        } catch (IOException e) {
            return;
        }
        // a remux the game closed during
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".mp4.tmp")).forEach(ScreenRecorder::deleteQuietly);
        } catch (IOException ignored) {
        }
        if (partials.isEmpty()) return;
        Path exe = VideoExporter.findFfmpeg();
        if (exe == null) return;
        for (Path ts : partials) {
            Path out = ts.resolveSibling(ts.getFileName().toString().replace(PARTIAL, ".mp4"));
            boolean hevc = false;
            try {
                hevc = probeHevc(exe, ts);
            } catch (IOException ignored) {
            }
            if (remux(exe, ts, out, hevc) == null) {
                deleteQuietly(ts);
                LOG.info("Recovered the unfinished recording {}", out.getFileName());
            }
        }
    }

    /**
     * A frame a second into {@code video} as a PNG, kept in {@code .thumbs}
     * beside it so the MEDIA grid only asks ffmpeg once per file. Blocking.
     */
    public static byte @Nullable [] thumbnail(Path video) {
        try {
            Path cache = video.resolveSibling(".thumbs").resolve(video.getFileName() + ".png");
            if (Files.isRegularFile(cache) && Files.getLastModifiedTime(cache).compareTo(Files.getLastModifiedTime(video)) >= 0) {
                return Files.readAllBytes(cache);
            }
            Path exe = VideoExporter.findFfmpeg();
            if (exe == null) return null;
            for (String at : new String[] {"1", "0"}) {
                Process p = new ProcessBuilder(exe.toString(), "-hide_banner", "-loglevel", "error", "-ss", at, "-i", video.toString(),
                        "-frames:v", "1", "-vf", "scale=320:-2", "-f", "image2pipe", "-vcodec", "png", "-")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                byte[] png = p.getInputStream().readAllBytes();
                if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
                if (png.length == 0) continue;
                Files.createDirectories(cache.getParent());
                Files.write(cache, png);
                return png;
            }
        } catch (IOException e) {
            LOG.debug("No thumbnail for {}", video, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static boolean probeHevc(Path exe, Path ts) throws IOException {
        Process p = new ProcessBuilder(exe.toString(), "-hide_banner", "-i", ts.toString()).redirectErrorStream(true).start();
        String info = new String(p.getInputStream().readNBytes(16384), java.nio.charset.StandardCharsets.UTF_8);
        p.destroy();
        return info.contains("Video: hevc");
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static void report(Minecraft mc, String message) {
        mc.execute(() -> {
            lastResult = message;
            if (mc.player != null) MediaCompat.message(mc.player, Component.literal(message), false);
        });
    }

    // ---- one encoder run ----------------------------------------------------------------

    private record Frame(byte[] data, long slot) {}

    private static final Frame END = new Frame(new byte[0], Long.MAX_VALUE);

    private static final class Session {
        final Path exe;
        final VideoEncoders.Choice enc;
        final int inW, inH, outW, outH, fps, keep;
        final String quality, codec;
        final boolean audio;
        @Nullable Process proc;
        @Nullable TsBuffer buffer;
        @Nullable ServerSocket audioServer;
        final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(3);
        final ArrayBlockingQueue<byte[]> spare = new ArrayBlockingQueue<>(4);
        final LinkedBlockingQueue<byte[]> sound = new LinkedBlockingQueue<>(4096);
        volatile long t0;
        long lastGrab = -1;
        volatile boolean running, closed, overloaded;
        volatile @Nullable String failure;
        private final List<Thread> threads = new ArrayList<>();

        Session(Path exe, VideoEncoders.Choice enc, int inW, int inH, int outW, int outH, int fps, String quality, String codec, boolean audio, int keep) {
            this.exe = exe;
            this.enc = enc;
            this.inW = inW;
            this.inH = inH;
            this.outW = outW;
            this.outH = outH;
            this.fps = fps;
            this.quality = quality;
            this.codec = codec;
            this.audio = audio;
            this.keep = keep;
        }

        boolean matches(DuskConfig c) {
            int[] out = outputSize(inW, inH, c.videoResolution);
            return out[0] == outW && out[1] == outH && (c.videoFps >= 45 ? 60 : 30) == fps && c.videoQuality.equals(quality)
                    && c.videoCodec.equals(codec) && (c.videoAudio && GameAudio.routed()) == audio && Math.max(5, c.videoSeconds) == keep;
        }

        /** Off the render thread: ffmpeg and the threads around it. */
        void launch(Path cache, Path log) throws IOException {
            Files.createDirectories(log.getParent());
            buffer = new TsBuffer(cache, keep);
            // frames arrive already scaled on the GPU, so ffmpeg only converts the colours
            List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-hide_banner", "-loglevel", "warning",
                    "-f", "rawvideo", "-pix_fmt", "rgba", "-s", outW + "x" + outH, "-r", String.valueOf(fps),
                    "-thread_queue_size", "64", "-i", "pipe:0"));
            if (audio) {
                ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                ss.setSoTimeout(15_000);
                audioServer = ss;
                cmd.addAll(List.of("-f", "s16le", "-ar", String.valueOf(GameAudio.RATE), "-ac", "2", "-thread_queue_size", "1024",
                        "-i", "tcp://127.0.0.1:" + ss.getLocalPort()));
            }
            cmd.addAll(List.of("-map", "0:v"));
            if (audio) cmd.addAll(List.of("-map", "1:a"));
            cmd.addAll(List.of("-vf", "scale=" + outW + ":" + outH + ":flags=bicubic:out_color_matrix=bt709:out_range=tv,format=" + enc.pixFmt(),
                    "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv"));
            cmd.addAll(enc.args());
            // a keyframe every second: the buffer cuts there, so clips start within a second of where asked
            cmd.addAll(List.of("-g", String.valueOf(fps), "-force_key_frames", "expr:gte(t,n_forced*1)"));
            if (audio) cmd.addAll(List.of("-c:a", "aac", "-b:a", "160k"));
            cmd.addAll(List.of("-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1", "pipe:1"));
            LOG.info("Screen recorder: {}x{} -> {}x{} at {} fps with {}{}", inW, inH, outW, outH, fps, enc.name(), audio ? " and game audio" : "");
            proc = new ProcessBuilder(cmd).redirectError(log.toFile()).start();
        }

        /** On the render thread, once it is the session: the clock starts and frames are wanted. */
        void start() {
            t0 = System.nanoTime();
            if (audio) GameAudio.tap((block, len) -> {
                byte[] b = new byte[len];
                System.arraycopy(block, 0, b, 0, len);
                if (!sound.offer(b)) {
                    sound.poll();
                    sound.offer(b);
                }
            });
            running = true;
            Process p = proc;
            TsBuffer buf = buffer;
            if (p == null || buf == null) return;
            spawn("Dusk recorder output", () -> {
                buf.pump(p.getInputStream());
                String why = buf.failure();
                if (why != null && !closed) failure = why;
            });
            spawn("Dusk recorder frames", () -> writeFrames(p));
            if (audio) spawn("Dusk recorder sound", this::writeSound);
            p.onExit().thenRun(() -> {
                if (!closed && failure == null) failure = "The encoder stopped (exit " + p.exitValue() + "; logs/dusk-recorder.log says why).";
            });
        }

        private void spawn(String name, Runnable r) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }

        // -- frames --

        void frame(Minecraft mc) {
            if (!running || closed) return;
            long slot = (System.nanoTime() - t0) * fps / 1_000_000_000L;
            if (slot <= lastGrab) return;
            if (frames.remainingCapacity() == 0) return;
            // whatever size the target is now (render scale shrinks the world's), the video's size goes out
            RenderTarget t = FrameScale.to(Compat.mainTarget(mc), outW, outH);
            if (FrameGrab.grab(t, (data, w, h) -> deliver(data, w, h, slot))) lastGrab = slot;
        }

        /** A frame back from the GPU: flipped upright into a plain array for the writer. Never waits. */
        private void deliver(ByteBuffer data, int w, int h, long slot) {
            if (closed || w != outW || h != outH) return;
            int row = w * 4, n = row * h;
            byte[] b = spare.poll();
            if (b == null || b.length != n) b = new byte[n];
            for (int y = 0; y < h; y++) data.get((h - 1 - y) * row, b, y * row, row);
            if (!frames.offer(new Frame(b, slot))) spare.offer(b);
        }

        private void writeFrames(Process p) {
            long next = 0;
            try (OutputStream os = p.getOutputStream()) {
                while (true) {
                    Frame f = frames.take();
                    if (f == END) break;
                    // fill the slots since the last frame with this one, at most ten seconds' worth
                    long n = Math.min(f.slot - next + 1, fps * 10L);
                    for (long i = 0; i < n; i++) os.write(f.data);
                    if (n > 0) next = f.slot + 1;
                    spare.offer(f.data);
                    long due = (System.nanoTime() - t0) * fps / 1_000_000_000L;
                    overloaded = due - next > fps * 2L;
                }
            } catch (IOException e) {
                if (!closed && failure == null) failure = "The encoder stopped taking frames (logs/dusk-recorder.log says why).";
            } catch (InterruptedException ignored) {
            }
        }

        // -- sound --

        private void writeSound() {
            ServerSocket ss = audioServer;
            if (ss == null) return;
            try (ss; Socket sock = ss.accept(); OutputStream os = sock.getOutputStream()) {
                sock.setTcpNoDelay(true);
                long written = 0;
                byte[] silence = new byte[4800 * 4];
                while (!closed) {
                    byte[] b = sound.poll(20, TimeUnit.MILLISECONDS);
                    long due = (System.nanoTime() - t0) * GameAudio.RATE / 1_000_000_000L;
                    if (b != null) {
                        // far ahead of the clock (the sound caught up after a stall): drop it
                        if (written - due > GameAudio.RATE / 10) continue;
                        os.write(b);
                        written += b.length / 4;
                    } else if (due - written > GameAudio.RATE / 10) {
                        // no sound coming (a stall, or nothing routed): silence keeps it in step
                        long gap = due - written;
                        while (gap > 0) {
                            int frames = (int) Math.min(gap, silence.length / 4);
                            os.write(silence, 0, frames * 4);
                            gap -= frames;
                            written += frames;
                        }
                    }
                }
            } catch (IOException e) {
                if (!closed && failure == null) failure = "The encoder stopped taking sound (logs/dusk-recorder.log says why).";
            } catch (InterruptedException ignored) {
            }
        }

        // -- ending --

        /**
         * Stops taking frames and lets ffmpeg finish what it has, then runs
         * {@code after} (on the save thread) and drops the buffer.
         */
        void close(@Nullable Runnable after) {
            if (closed) return;
            closed = true;
            running = false;
            GameAudio.tap(null);
            FrameGrab.release(0);
            FrameScale.release();
            frames.clear();
            frames.offer(END);
            Process p = proc;
            TsBuffer buf = buffer;
            Thread finisher = new Thread(() -> {
                try {
                    if (p != null && !p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
                    for (Thread t : threads) t.join(2_000);
                } catch (InterruptedException ignored) {
                }
                if (after != null) after.run();
                if (buf != null) IO.execute(buf::close);
            }, "Dusk recorder stop");
            finisher.setDaemon(true);
            finisher.start();
            // unblocks a sound thread still waiting for ffmpeg; a connection it has already stays open
            ServerSocket ss = audioServer;
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }

        void kill() {
            closed = true;
            Process p = proc;
            if (p != null) p.destroyForcibly();
            ServerSocket ss = audioServer;
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
