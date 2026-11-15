package dev.dusk.client.media;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * One run of the encoder: an ffmpeg that takes raw frames on its input and
 * gives MPEG-TS on its output, the thread feeding it and the thread reading
 * it into the {@link TsBuffer}. The render thread only asks which frame is
 * due and hands finished readbacks over; everything slow is off it.
 *
 * Frames arrive as BGRA rows bottom-up, the way OpenGL reads them back;
 * ffmpeg flips them and converts the colours.
 */
public final class Session {
    private static final Logger LOG = LogManager.getLogger("Dusk Client");

    private static final class Frame {
        final byte[] data;
        final long slot;

        Frame(byte[] data, long slot) {
            this.data = data;
            this.slot = slot;
        }
    }

    private static final Frame END = new Frame(null, -1);

    public final Path exe;
    public final VideoEncoders.Choice enc;
    /** The window the session was started for. */
    public final int inW, inH;
    /** The raw frames fed to ffmpeg. */
    public final int frameW, frameH;
    /** The video. */
    public final int outW, outH;
    public final int fps, keep;
    public final String quality, codec;

    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<Frame>(3);
    private final ArrayBlockingQueue<byte[]> spare = new ArrayBlockingQueue<byte[]>(4);
    private final List<Thread> threads = new ArrayList<Thread>();
    private Process process;
    private TsBuffer buffer;
    private long t0;
    private long lastGrab = -1;
    private volatile boolean running, closed;
    private volatile String failure;

    public Session(Path exe, VideoEncoders.Choice enc, int inW, int inH, int frameW, int frameH, int outW, int outH,
            int fps, String quality, String codec, int keep) {
        this.exe = exe;
        this.enc = enc;
        this.inW = inW;
        this.inH = inH;
        this.frameW = frameW;
        this.frameH = frameH;
        this.outW = outW;
        this.outH = outH;
        this.fps = fps;
        this.quality = quality;
        this.codec = codec;
        this.keep = keep;
    }

    public TsBuffer buffer() {
        return buffer;
    }

    public boolean running() {
        return running && !closed;
    }

    /** Why the session died, or null while it is fine. */
    public String failure() {
        return failure;
    }

    public boolean matches(int outW, int outH, int fps, String quality, String codec, int keep) {
        return this.outW == outW && this.outH == outH && this.fps == fps && this.keep == keep
                && this.quality.equals(quality) && this.codec.equals(codec);
    }

    public int frameBytes() {
        return frameW * frameH * 4;
    }

    // ---- starting ------------------------------------------------------------------------

    /** Starts ffmpeg; blocking, so off the render thread. */
    public void launch(Path cache, Path log) throws IOException {
        Files.createDirectories(log.getParent());
        buffer = new TsBuffer(cache, keep);
        List<String> cmd = new ArrayList<String>(Arrays.asList(exe.toString(), "-hide_banner", "-loglevel", "warning",
                "-f", "rawvideo", "-pix_fmt", "bgr0", "-s", frameW + "x" + frameH, "-r", String.valueOf(fps),
                "-thread_queue_size", "64", "-i", "pipe:0", "-map", "0:v",
                "-vf", "vflip,scale=" + outW + ":" + outH + ":flags=bicubic:out_color_matrix=bt709:out_range=tv,format=" + enc.pixFmt,
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv"));
        cmd.addAll(enc.args);
        // a keyframe a second: the buffer is cut at keyframes, so that is how finely a clip starts
        cmd.addAll(Arrays.asList("-g", String.valueOf(fps), "-force_key_frames", "expr:gte(t,n_forced*1)",
                "-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1", "pipe:1"));
        process = new ProcessBuilder(cmd).redirectError(log.toFile()).start();
        LOG.info("Screen recorder: " + inW + "x" + inH + " -> " + outW + "x" + outH + " at " + fps + " fps with " + enc.name);
    }

    /** Starts the clock and the two threads; after {@link #launch}. */
    public void start() {
        final Process p = process;
        final TsBuffer buf = buffer;
        t0 = System.nanoTime();
        running = true;
        spawn("Dusk recorder output", new Runnable() {
            @Override
            public void run() {
                buf.pump(p.getInputStream());
                String why = buf.failure();
                if (why != null && !closed && failure == null) failure = why;
            }
        });
        spawn("Dusk recorder frames", new Runnable() {
            @Override
            public void run() {
                writeFrames(p.getOutputStream());
            }
        });
        Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int exit = p.waitFor();
                    if (!closed && failure == null) failure = "The encoder stopped (exit " + exit + "; logs/dusk-recorder.log says why).";
                } catch (InterruptedException ignored) {
                }
            }
        }, "Dusk recorder watch");
        watch.setDaemon(true);
        watch.start();
    }

    private void spawn(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        threads.add(t);
        t.start();
    }

    // ---- frames (render thread) ----------------------------------------------------------

    /** The frame slot to grab now, or -1: not running, this slot is already taken, or the encoder is behind. */
    public long dueSlot() {
        if (!running || closed) return -1;
        long slot = (System.nanoTime() - t0) * fps / 1_000_000_000L;
        if (slot <= lastGrab) return -1;
        if (frames.remainingCapacity() == 0) return -1;
        return slot;
    }

    public void grabbed(long slot) {
        lastGrab = slot;
    }

    /** An array for one frame, reused when there is one to spare. */
    public byte[] take() {
        byte[] b = spare.poll();
        int n = frameBytes();
        return b != null && b.length == n ? b : new byte[n];
    }

    /** Hands a finished frame to the encoder thread; dropped when it is behind. */
    public void deliver(byte[] data, long slot) {
        if (closed) return;
        if (!frames.offer(new Frame(data, slot))) spare.offer(data);
    }

    // ---- encoder thread ------------------------------------------------------------------

    private void writeFrames(OutputStream out) {
        long next = 0;
        try {
            while (true) {
                Frame f = frames.take();
                if (f == END) break;
                // a frame fills every slot since the last one, so the video keeps real time
                long n = Math.min(f.slot - next + 1, fps * 10L);
                for (long i = 0; i < n; i++) out.write(f.data);
                if (n > 0) next = f.slot + 1;
                spare.offer(f.data);
            }
        } catch (IOException e) {
            if (!closed && failure == null) failure = "The encoder stopped taking frames (logs/dusk-recorder.log says why).";
        } catch (InterruptedException ignored) {
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---- ending --------------------------------------------------------------------------

    /**
     * Lets ffmpeg finish what it has, then runs {@code after} (on another
     * thread) and closes the buffer on {@code io}, behind any save queued there.
     */
    public void close(final Runnable after, final Executor io) {
        if (closed) return;
        closed = true;
        running = false;
        frames.clear();
        frames.offer(END);
        final Process p = process;
        final TsBuffer buf = buffer;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (p != null && !p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
                    for (Thread th : threads) th.join(2_000);
                } catch (InterruptedException ignored) {
                }
                try {
                    if (after != null) after.run();
                } finally {
                    if (buf != null) {
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                buf.close();
                            }
                        });
                    }
                }
            }
        }, "Dusk recorder stop");
        t.setDaemon(true);
        t.start();
    }

    /** Ends a session that never started. */
    public void kill() {
        closed = true;
        running = false;
        if (process != null) process.destroyForcibly();
        if (buffer != null) buffer.close();
    }
}
