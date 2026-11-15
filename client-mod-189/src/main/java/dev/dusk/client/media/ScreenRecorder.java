package dev.dusk.client.media;

import dev.dusk.client.modules.misc.Clips;
import dev.dusk.client.util.PerfLog;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The screen recorder, the way OBS does it: the finished frame is read back
 * through pixel buffers and fed to an ffmpeg that encodes it on the GPU. A
 * replay buffer holds the last stretch on disk, so the clip key saves what
 * just happened without re-encoding; the record key writes a video until it
 * is pressed again. Videos land in the instance's videos folder, where the
 * launcher lists them.
 *
 * Everything here runs on the client thread except what is handed to IO.
 */
public final class ScreenRecorder {
    private static final Logger LOG = LogManager.getLogger("Dusk Client");
    private static final String PARTIAL = ".recording.ts";
    private static final int RESIZE_SETTLE = 20, MAX_FAILURES = 3;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Dusk video save");
            t.setDaemon(true);
            return t;
        }
    });

    private static volatile Session session;
    private static FrameGrab grab;
    private static boolean starting, recoveredPartials, recordWanted, worldHookSeen, fallbackLogged;
    private static Path recordingTs;
    private static long recordingStartMs, recordedBeforeMs, retryAt;
    private static int resizeTicks, failures;
    private static volatile String lastResult;

    private ScreenRecorder() {}

    // ---- state -------------------------------------------------------------------------

    /** True while the record key's video is wanted (even between encoder restarts). */
    public static boolean recording() {
        return recordWanted;
    }

    public static long recordingMs() {
        if (!recordWanted) return 0;
        return recordedBeforeMs + (recordingTs != null ? System.currentTimeMillis() - recordingStartMs : 0);
    }

    public static String lastResult() {
        return lastResult;
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public static Path videosDir() {
        return videosDir(Minecraft.getMinecraft());
    }

    private static Path videosDir(Minecraft mc) {
        return mc.mcDataDir.toPath().resolve("videos");
    }

    private static int fps(Clips c) {
        return c.fps() >= 60 ? 60 : 30;
    }

    private static int keep(Clips c) {
        return Math.max(5, c.seconds());
    }

    private static boolean want(Minecraft mc, Clips c) {
        return c.enabled() && (recordWanted || c.bufferOn() && mc.theWorld != null);
    }

    // ---- the tick ----------------------------------------------------------------------

    public static void tick(final Minecraft mc) {
        Clips c = Clips.instance();
        if (c == null) return;
        if (!recoveredPartials) {
            recoveredPartials = true;
            // listed now, before any recording of this run can exist
            final List<Path> left = leftovers(videosDir(mc));
            if (!left.isEmpty()) {
                IO.execute(new Runnable() {
                    @Override
                    public void run() {
                        recoverPartials(left);
                    }
                });
            }
        }
        if (!c.enabled()) recordWanted = false;
        boolean want = want(mc, c);
        long now = System.currentTimeMillis();
        Session s = session;
        if (s != null) {
            String why = s.failure();
            if (why != null) {
                failures++;
                retryAt = now + 5_000;
                endSession(mc, s, false);
                report(mc, failures >= MAX_FAILURES ? why + " Screen recording stopped; change a setting to try again." : why);
                return;
            }
            if (!want) {
                endSession(mc, s, true);
                return;
            }
            if (grab != null && grab.failed()) {
                // the next session scales in ffmpeg instead
                endSession(mc, s, true);
                return;
            }
            if (mc.displayWidth != s.inW || mc.displayHeight != s.inH) {
                if (++resizeTicks >= RESIZE_SETTLE) {
                    LOG.info("Window is now " + mc.displayWidth + "x" + mc.displayHeight + "; restarting the screen recorder");
                    endSession(mc, s, true);
                }
            } else {
                resizeTicks = 0;
            }
            return;
        }
        if (want && !starting && failures < MAX_FAILURES && now >= retryAt) begin(mc, c);
    }

    private static void begin(final Minecraft mc, Clips c) {
        final int inW = mc.displayWidth, inH = mc.displayHeight;
        if (inW < 16 || inH < 16) return;
        if (!FrameGrab.supported()) {
            failures = MAX_FAILURES;
            recordWanted = false;
            report(mc, "Screen recording needs OpenGL 2.1, which this graphics driver doesn't offer.");
            return;
        }
        final int[] out = outputSize(inW, inH, c.resolution());
        boolean capped = out[1] < (inH & ~1);
        final int frameW, frameH;
        if (!capped || FrameGrab.canScale()) {
            frameW = out[0];
            frameH = out[1];
        } else {
            frameW = inW & ~1;
            frameH = inH & ~1;
        }
        final int fps = fps(c), keep = keep(c);
        final String quality = c.quality(), codec = c.codec();
        final Path cache = mc.mcDataDir.toPath().resolve("dusk").resolve("video-buffer");
        final Path log = mc.mcDataDir.toPath().resolve("logs").resolve("dusk-recorder.log");
        starting = true;
        resizeTicks = 0;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Session s = null;
                String why = null;
                boolean noFfmpeg = false;
                try {
                    Path exe = Ffmpeg.find();
                    if (exe == null) {
                        noFfmpeg = true;
                    } else {
                        VideoEncoders.Choice enc = VideoEncoders.pick(exe, codec, quality, out[0], out[1], fps);
                        s = new Session(exe, enc, inW, inH, frameW, frameH, out[0], out[1], fps, quality, codec, keep);
                        s.launch(cache, log);
                    }
                } catch (IOException | RuntimeException e) {
                    why = "Couldn't start the screen recorder: " + e.getMessage();
                    LOG.warn(why, e);
                    if (s != null) s.kill();
                    s = null;
                }
                final Session made = s;
                final String failed = why;
                final boolean missing = noFfmpeg;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (missing) needFfmpeg(mc);
                        else started(mc, made, failed);
                    }
                });
            }
        }, "Dusk recorder start");
        t.setDaemon(true);
        t.start();
    }

    private static void needFfmpeg(Minecraft mc) {
        starting = false;
        recordWanted = false;
        failures = MAX_FAILURES;
        report(mc, "Screen recording needs ffmpeg. Install it (brew install ffmpeg, or winget install ffmpeg on Windows) and change a setting to try again.");
    }

    private static void started(Minecraft mc, Session s, String why) {
        starting = false;
        if (s == null) {
            if (why != null) {
                failures++;
                retryAt = System.currentTimeMillis() + 5_000;
                report(mc, why);
            }
            return;
        }
        Clips c = Clips.instance();
        if (c == null || !want(mc, c) || mc.displayWidth != s.inW || mc.displayHeight != s.inH || !matches(s, c)) {
            s.close(null, IO);
            return;
        }
        session = s;
        s.start();
        PerfLog.event("screen recorder on: " + s.outW + "x" + s.outH + "@" + s.fps + " " + s.enc.name);
        if (recordWanted) startRecordingFile(mc, s);
    }

    private static boolean matches(Session s, Clips c) {
        int[] out = outputSize(s.inW, s.inH, c.resolution());
        return s.matches(out[0], out[1], fps(c), c.quality(), c.codec(), keep(c));
    }

    /** A setting changed: forget past failures and restart the encoder if it no longer fits. */
    public static void settingsChanged() {
        failures = 0;
        retryAt = 0;
        Session s = session;
        Clips c = Clips.instance();
        if (s != null && c != null && !matches(s, c)) endSession(Minecraft.getMinecraft(), s, true);
    }

    // ---- frames (render thread) --------------------------------------------------------

    /**
     * Called twice a frame: after the world is drawn ({@code hud} false) and
     * when the whole frame is ({@code hud} true). Which one is captured
     * depends on whether the HUD is wanted in the video.
     */
    public static void onFrame(boolean hud) {
        Session s = session;
        if (s == null) {
            worldHookSeen = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Clips c = Clips.instance();
        if (c == null) return;
        boolean sized = mc.displayWidth == s.inW && mc.displayHeight == s.inH;
        boolean worldOnly = !c.hud() && mc.theWorld != null;
        if (!hud) {
            worldHookSeen = true;
            if (worldOnly && sized) capture(s);
            return;
        }
        if (grab != null) grab.poll(s);
        boolean seen = worldHookSeen;
        worldHookSeen = false;
        if (!sized) return;
        if (!worldOnly) {
            capture(s);
        } else if (!seen) {
            if (!fallbackLogged) {
                fallbackLogged = true;
                LOG.info("The world-only capture point isn't there (another mod changed the renderer); recording the whole frame");
            }
            capture(s);
        }
    }

    private static void capture(Session s) {
        long slot = s.dueSlot();
        if (slot < 0) return;
        if (grab == null) grab = new FrameGrab(s.inW, s.inH, s.frameW, s.frameH);
        if (grab.grab(slot)) s.grabbed(slot);
    }

    // ---- the keys ----------------------------------------------------------------------

    public static void saveClip(final Minecraft mc) {
        final Clips c = Clips.instance();
        if (c == null || !c.enabled()) {
            report(mc, "Clips is off. Turn the Clips module on in the Dusk menu.");
            return;
        }
        if (!c.bufferOn()) {
            report(mc, "The replay buffer is off. Turn it on in Clips, or use the record key.");
            return;
        }
        final Session s = session;
        if (s == null || !s.running()) {
            report(mc, starting ? "The recorder is still starting." : "Nothing to save yet: the buffer fills while you're in a world.");
            return;
        }
        final int seconds = keep(c);
        final TsBuffer buf = s.buffer();
        final TsBuffer.Span span = buf.hold(seconds);
        if (span == null) {
            report(mc, "Nothing to save yet.");
            return;
        }
        final Path out;
        try {
            out = newFile(mc, "clip_");
        } catch (IOException e) {
            buf.release(span);
            report(mc, "Couldn't save the clip: " + e.getMessage());
            return;
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                Path tmp = out.resolveSibling(out.getFileName() + ".part.ts");
                try {
                    buf.write(span, tmp);
                    String err = Ffmpeg.remux(s.exe, tmp, out, s.enc.hevc);
                    if (err == null) Ffmpeg.thumbnail(s.exe, out);
                    report(mc, err != null ? err
                            : "Saved the last " + Math.round(Math.min(span.seconds, seconds)) + "s to " + out.getFileName() + ".");
                } catch (IOException e) {
                    report(mc, "Couldn't save the clip: " + e.getMessage());
                } finally {
                    Ffmpeg.deleteQuietly(tmp);
                }
            }
        });
    }

    public static void toggleRecording(Minecraft mc) {
        Clips c = Clips.instance();
        if (c == null || !c.enabled()) {
            report(mc, "Clips is off. Turn the Clips module on in the Dusk menu.");
            return;
        }
        if (recordWanted) {
            stopRecording(mc, c);
            return;
        }
        recordWanted = true;
        recordedBeforeMs = 0;
        failures = 0;
        retryAt = 0;
        Session s = session;
        if (s != null && s.running()) startRecordingFile(mc, s);
    }

    private static void startRecordingFile(Minecraft mc, Session s) {
        try {
            Path mp4 = newFile(mc, "recording_");
            String name = mp4.getFileName().toString();
            Path ts = mp4.resolveSibling(name.substring(0, name.length() - 4) + PARTIAL);
            s.buffer().startRecording(ts);
            recordingTs = ts;
            recordingStartMs = System.currentTimeMillis();
            if (recordedBeforeMs == 0) report(mc, "Recording. Press the record key again to stop.");
        } catch (IOException e) {
            recordWanted = false;
            report(mc, "Couldn't start recording: " + e.getMessage());
        }
    }

    private static void stopRecording(final Minecraft mc, Clips c) {
        recordWanted = false;
        final Session s = session;
        if (s != null && !c.bufferOn()) {
            endSession(mc, s, true);
            return;
        }
        if (s != null && recordingTs != null) {
            recordedBeforeMs += System.currentTimeMillis() - recordingStartMs;
            final Path ts = recordingTs;
            recordingTs = null;
            final long bytes = s.buffer().stopRecording();
            IO.execute(new Runnable() {
                @Override
                public void run() {
                    finishFile(mc, s.exe, ts, bytes, s.enc.hevc, true);
                }
            });
        } else {
            report(mc, "Recording stopped before anything was recorded.");
        }
    }

    /** Turns a finished recording's stream into its MP4; on IO. */
    private static void finishFile(Minecraft mc, Path exe, Path ts, long bytes, boolean hevc, boolean announce) {
        if (bytes < TsBuffer.PACKET * 16L) {
            Ffmpeg.deleteQuietly(ts);
            if (announce) report(mc, "The recording was too short to keep.");
            return;
        }
        String name = ts.getFileName().toString();
        Path mp4 = ts.resolveSibling(name.substring(0, name.length() - PARTIAL.length()) + ".mp4");
        String err = Ffmpeg.remux(exe, ts, mp4, hevc);
        if (err == null) {
            Ffmpeg.deleteQuietly(ts);
            Ffmpeg.thumbnail(exe, mp4);
        }
        if (announce || err != null) report(mc, err != null ? err : "Saved " + mp4.getFileName() + " to the videos folder.");
    }

    /**
     * Ends the encoder. With {@code keepRecording} a wanted recording goes on
     * in a new file once the next session is up; without, it ends here.
     */
    private static void endSession(final Minecraft mc, final Session s, boolean keepRecording) {
        if (session == s) session = null;
        if (grab != null) {
            grab.release();
            grab = null;
        }
        final Path ts = recordingTs;
        if (ts != null) {
            recordedBeforeMs += System.currentTimeMillis() - recordingStartMs;
            recordingTs = null;
        }
        final boolean announce = !recordWanted || !keepRecording;
        if (!keepRecording) recordWanted = false;
        s.close(ts == null ? null : new Runnable() {
            @Override
            public void run() {
                final long bytes = s.buffer().stopRecording();
                IO.execute(new Runnable() {
                    @Override
                    public void run() {
                        finishFile(mc, s.exe, ts, bytes, s.enc.hevc, announce);
                    }
                });
            }
        }, IO);
    }

    // ---- files -------------------------------------------------------------------------

    /** The video's size: the window's, capped at the chosen height, both even. */
    static int[] outputSize(int w, int h, String resolution) {
        int cap = resolution.equals("1440") ? 1440 : resolution.equals("1080") ? 1080 : resolution.equals("720") ? 720 : Integer.MAX_VALUE;
        int oh = Math.min(h, cap);
        int ow = (int) Math.round(w * (oh / (double) h));
        return new int[] {ow & ~1, oh & ~1};
    }

    private static Path newFile(Minecraft mc, String prefix) throws IOException {
        Path dir = videosDir(mc);
        Files.createDirectories(dir);
        String base = prefix + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
        String name = base;
        for (int i = 2; Files.exists(dir.resolve(name + ".mp4")) || Files.exists(dir.resolve(name + PARTIAL)); i++) {
            name = base + "_" + i;
        }
        return dir.resolve(name + ".mp4");
    }

    /** Recordings a crash or a quit left unfinished; also clears half-written saves. */
    private static List<Path> leftovers(Path dir) {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (n.endsWith(PARTIAL)) out.add(p);
                else if (n.endsWith(".mp4.tmp") || n.endsWith(".part.ts")) Ffmpeg.deleteQuietly(p);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void recoverPartials(List<Path> left) {
        Path exe = Ffmpeg.find();
        if (exe == null) return;
        for (Path ts : left) {
            String name = ts.getFileName().toString();
            Path mp4 = ts.resolveSibling(name.substring(0, name.length() - PARTIAL.length()) + ".mp4");
            String err = Ffmpeg.remux(exe, ts, mp4, Ffmpeg.probeHevc(exe, ts));
            if (err == null) {
                Ffmpeg.deleteQuietly(ts);
                Ffmpeg.thumbnail(exe, mp4);
                LOG.info("Recovered the unfinished recording " + mp4.getFileName());
            }
        }
    }

    public static void openVideosFolder() {
        Path dir = videosDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        String err = openInOs(dir);
        if (err != null) report(Minecraft.getMinecraft(), "Couldn't open the videos folder: " + err);
    }

    /** Opens a file or folder in the system's app for it; the error, or null. */
    public static String openInOs(Path p) {
        try {
            if (Ffmpeg.windows()) new ProcessBuilder("explorer", p.toString()).start();
            else new ProcessBuilder(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "open" : "xdg-open",
                    p.toString()).start();
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static void report(final Minecraft mc, final String msg) {
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                lastResult = msg;
                LOG.info(msg);
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText(
                            EnumChatFormatting.GRAY + "[Clips] " + EnumChatFormatting.RESET + msg));
                }
            }
        });
    }
}
