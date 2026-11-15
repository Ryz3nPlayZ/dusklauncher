package dev.dusk.client.util;

import dev.dusk.client.config.DuskConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The frame time log (Preferences, off by default): what the frame rate
 * really did and what each slow frame was waiting for, written to
 * {@code logs/dusk-perf.log}. Every ten seconds in a world it writes one
 * summary line, and each frame that took far longer than the ones around it
 * gets its own line split into the buffer swap, the game tick, the wait for
 * the GPU and garbage collection, followed by the parts of the game (by the
 * names the F3 pie chart uses) that took the time. Every collection is noted
 * with its cause.
 *
 * <p>While it's off the cost is one field read a frame.
 */
public final class PerfLog {
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final int MAX_FRAMES = 8192;
    private static final int MAX_SPIKES = 25;
    /** A part of the frame is worth naming once it takes this long. */
    private static final long SECTION_NANOS = 3_000_000L;
    private static final int MAX_DEPTH = 48, MAX_SECTIONS = 8;

    private static boolean started, failed;
    private static PrintWriter out;
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS");

    // written by the JVM's GC notification thread
    private static final AtomicLong gcMillis = new AtomicLong(), gcCount = new AtomicLong();
    private static final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<String>();
    private static volatile String lastGc = "";

    // client thread only
    private static long lastFrame, windowStart, swapStart, tickStart;
    private static float swapMs, tickMs, gpuWaitMs;
    private static long gcSeen;
    private static final float[] frames = new float[MAX_FRAMES];
    private static int frameCount, spikes, over17, over33;
    private static float worst, tickSum, swapSum, gpuSum, average;
    private static long windowGc;
    private static boolean windowFocused = true;

    /** Read by the profiler hook: true while the log is on and it's worth timing sections. */
    public static boolean sections;
    private static Thread clientThread;
    private static final String[] sectionName = new String[MAX_DEPTH];
    private static final long[] sectionStart = new long[MAX_DEPTH], sectionCovered = new long[MAX_DEPTH];
    private static int depth;
    private static final String[] slowSection = new String[MAX_SECTIONS];
    private static final long[] slowTotal = new long[MAX_SECTIONS], slowOwn = new long[MAX_SECTIONS];
    private static final int[] slowTimes = new int[MAX_SECTIONS];
    private static int slowSections;

    private PerfLog() {}

    private static boolean on() {
        if (failed) return false;
        try {
            return DuskConfig.get().perfLog;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Minecraft.updateDisplay HEAD: the frame's drawing is done. */
    public static void frameEnd() {
        if (!on()) {
            lastFrame = 0;
            sections = false;
            return;
        }
        if (!started) start();
        long now = System.nanoTime();
        if (lastFrame != 0) frame(now, (now - lastFrame) / 1e6f);
        sections = !failed;
        depth = 0;
        slowSections = 0;
        lastFrame = now;
        swapStart = now;
        tickMs = 0;
        gpuWaitMs = 0;
    }

    /** Minecraft.updateDisplay RETURN: the buffer swap is over. */
    public static void afterSwap() {
        if (swapStart != 0) swapMs = (System.nanoTime() - swapStart) / 1e6f;
    }

    public static void tickStart() {
        if (lastFrame != 0) tickStart = System.nanoTime();
    }

    public static void tickEnd() {
        if (lastFrame != 0 && tickStart != 0) tickMs += (System.nanoTime() - tickStart) / 1e6f;
    }

    /** A named part of the frame begins (Profiler.startSection). */
    public static void sectionStart(String name) {
        if (Thread.currentThread() != clientThread) return;
        if (depth < MAX_DEPTH) {
            sectionName[depth] = name;
            sectionStart[depth] = System.nanoTime();
            sectionCovered[depth] = 0;
        }
        depth++;
    }

    /**
     * The innermost part ends. One that took long is kept for the slow frame
     * line, unless the parts inside it already account for its time.
     */
    public static void sectionEnd() {
        if (Thread.currentThread() != clientThread || depth == 0) return;
        depth--;
        if (depth >= MAX_DEPTH) return;
        long took = System.nanoTime() - sectionStart[depth];
        if (depth > 0) sectionCovered[depth - 1] += took;
        if (took < SECTION_NANOS) return;
        long own = took - sectionCovered[depth];
        if (own < SECTION_NANOS || idle(sectionName[depth])) return;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i <= depth; i++) {
            if (i == 0 && depth > 0 && "root".equals(sectionName[0])) continue;
            if (path.length() > 0) path.append('/');
            path.append(sectionName[i]);
        }
        // the same part can run several times a frame (one light check per block): one entry, summed
        String name = path.toString();
        int at = 0;
        while (at < slowSections && !slowSection[at].equals(name)) at++;
        if (at == MAX_SECTIONS) return;
        if (at == slowSections) {
            slowSection[at] = name;
            slowTotal[at] = slowOwn[at] = 0;
            slowTimes[at] = 0;
            slowSections++;
        }
        slowTotal[at] += took;
        slowOwn[at] += own;
        slowTimes[at]++;
    }

    /** The frame limiter's sleep is a section too; it isn't work. */
    private static boolean idle(String section) {
        return "fpslimit_wait".equals(section);
    }

    /** How long Low Input Lag held the frame for the GPU to finish the last one. */
    public static void gpuWait(long nanos) {
        if (lastFrame != 0) gpuWaitMs += nanos / 1e6f;
    }

    /** Something worth a line of its own (a world load, a resource reload). */
    public static void event(String what) {
        if (lastFrame != 0) pending.add(what);
    }

    private static void frame(long now, float ms) {
        Minecraft mc = Minecraft.getMinecraft();
        for (String line; (line = pending.poll()) != null; ) write(line);

        long gcNow = gcMillis.get();
        long gcMs = gcNow - gcSeen;
        gcSeen = gcNow;

        boolean inWorld = mc.theWorld != null && mc.thePlayer != null;
        boolean focused = Display.isActive();
        if (!inWorld) {
            windowStart = 0;
            return;
        }
        if (windowStart == 0) resetWindow(now);
        if (!focused) windowFocused = false;

        if (frameCount < MAX_FRAMES) frames[frameCount] = ms;
        frameCount++;
        if (ms > worst) worst = ms;
        if (ms > 16.7f) over17++;
        if (ms > 33.4f) over33++;
        tickSum += tickMs;
        swapSum += swapMs;
        gpuSum += gpuWaitMs;
        windowGc += gcMs;

        if (focused && average > 0 && ms > 25f && ms > average * 2.5f && spikes < MAX_SPIKES) {
            spikes++;
            float rest = Math.max(0f, ms - swapMs - tickMs - gpuWaitMs);
            write(String.format(Locale.ROOT,
                    "  slow frame %.1f ms  (swap %.1f, tick %.1f, gpu wait %.1f, rest %.1f)%s%s",
                    ms, swapMs, tickMs, gpuWaitMs, rest,
                    gcMs > 0 ? "  gc " + gcMs + " ms [" + lastGc + "]" : "",
                    mc.currentScreen != null ? "  screen " + mc.currentScreen.getClass().getSimpleName() : ""));
            if (slowSections > 0) {
                StringBuilder parts = new StringBuilder("    in: ");
                for (int i = 0; i < slowSections; i++) {
                    if (i > 0) parts.append("; ");
                    parts.append(slowSection[i]).append(String.format(Locale.ROOT, " %.1f", slowTotal[i] / 1e6f));
                    if (slowTotal[i] - slowOwn[i] >= SECTION_NANOS) {
                        parts.append(String.format(Locale.ROOT, " (own %.1f)", slowOwn[i] / 1e6f));
                    }
                    if (slowTimes[i] > 1) parts.append(" x").append(slowTimes[i]);
                }
                write(parts.toString());
            }
        }
        // The usual frame, slow to follow so one bad frame doesn't hide the next.
        average = average == 0 ? ms : average + (Math.min(ms, average * 3f) - average) * 0.02f;

        if (now - windowStart >= WINDOW_NANOS) {
            summary(mc, (now - windowStart) / 1e9f);
            resetWindow(now);
        }
    }

    private static void resetWindow(long now) {
        windowStart = now;
        frameCount = spikes = over17 = over33 = 0;
        worst = tickSum = swapSum = gpuSum = 0;
        windowGc = 0;
        windowFocused = true;
    }

    private static void summary(Minecraft mc, float seconds) {
        if (frameCount < 2) return;
        int n = Math.min(frameCount, MAX_FRAMES);
        float[] sorted = Arrays.copyOf(frames, n);
        Arrays.sort(sorted);
        int slow = Math.max(1, n / 100);
        float slowSum = 0;
        for (int i = n - slow; i < n; i++) slowSum += sorted[i];
        int entities = 0;
        try {
            entities = mc.theWorld.loadedEntityList.size();
        } catch (Throwable ignored) {
        }
        write(String.format(Locale.ROOT,
                "fps %.0f | 1%% low %.0f | worst %.1f ms | over 17 ms: %d, over 33 ms: %d | per frame: tick %.2f, swap %.2f, "
                        + "gpu wait %.2f ms | gc %d ms | %d entities | %dx%d%s",
                frameCount / seconds, 1000f / (slowSum / slow), worst, over17, over33,
                tickSum / frameCount, swapSum / frameCount, gpuSum / frameCount, windowGc, entities,
                mc.displayWidth, mc.displayHeight, windowFocused ? "" : " | window not focused"));
    }

    private static void start() {
        started = true;
        clientThread = Thread.currentThread();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            File dir = new File(mc.mcDataDir, "logs");
            dir.mkdirs();
            out = new PrintWriter(new FileWriter(new File(dir, "dusk-perf.log"), true), true);
            write("==== " + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + "  Java "
                    + System.getProperty("java.version") + " " + System.getProperty("os.arch") + "  "
                    + GL11.glGetString(GL11.GL_RENDERER) + "  " + mc.displayWidth + "x" + mc.displayHeight
                    + " (scale " + HiDpi.scale() + ")  max fps " + mc.gameSettings.limitFramerate
                    + ", vsync " + mc.gameSettings.enableVsync + ", vbo " + mc.gameSettings.useVbo
                    + ", distance " + mc.gameSettings.renderDistanceChunks
                    + ", heap " + (Runtime.getRuntime().maxMemory() >> 20) + " MB");
        } catch (Throwable t) {
            failed = true;
            return;
        }
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean instanceof NotificationEmitter) {
                    ((NotificationEmitter) bean).addNotificationListener(new GcListener(), null, null);
                }
            }
        } catch (Throwable t) {
            write("(no garbage collection details on this Java)");
        }
    }

    private static void write(String line) {
        if (out == null) return;
        out.println(CLOCK.format(new Date()) + "  " + line);
    }

    /** One line per collection that could be felt, with what asked for it. */
    private static final class GcListener implements NotificationListener {
        @Override
        public void handleNotification(Notification n, Object handback) {
            try {
                if (!"com.sun.management.gc.notification".equals(n.getType())) return;
                com.sun.management.GarbageCollectionNotificationInfo info =
                        com.sun.management.GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                long ms = info.getGcInfo().getDuration();
                gcMillis.addAndGet(ms);
                gcCount.incrementAndGet();
                lastGc = info.getGcName() + ", " + info.getGcCause();
                boolean full = info.getGcAction() != null && info.getGcAction().contains("major");
                if (full || ms >= 15) {
                    pending.add("  gc " + ms + " ms  " + info.getGcName() + "  cause: " + info.getGcCause());
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
