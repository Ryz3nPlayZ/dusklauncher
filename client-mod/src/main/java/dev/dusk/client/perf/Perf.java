package dev.dusk.client.perf;

import net.minecraft.client.Minecraft;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Performance page's numbers: how long each module and client part
 * spends on the game's main thread (ticks, the HUD, the world pass), the
 * frame rate and its lows, and process-wide CPU, memory, GC and thread use.
 * Timers are two {@link System#nanoTime} calls, so they stay on all the
 * time and the page can show the last ten seconds of play from before it
 * opened. Everything here runs on the main (render) thread, which is the
 * only thread that writes sections or reads the results.
 */
public final class Perf {
    public static final int TICK = 0, HUD = 1, WORLD = 2, OTHER = 3, SLOTS = 4;
    private static final int PLAY_SECONDS = 10, HISTORY = 60, FRAME_CAP = 8192;

    /** One module or client part; time is kept per slot (where it was spent). */
    public static final class Section {
        public final String name;
        String group;
        final int index;
        final long[] acc = new long[SLOTS];

        Section(String name, String group, int index) {
            this.name = name;
            this.group = group;
            this.index = index;
        }

        /** The module category, or "Client" for parts outside the module list. */
        public String group() { return group; }

        public void acc(int slot, long nanos) {
            acc[slot] += nanos;
        }
    }

    /** A section's time in a view, in ms of main-thread time per second, by slot. */
    public record Row(Section section, double[] ms) {
        public double total() {
            double t = 0;
            for (double v : ms) t += v;
            return t;
        }
    }

    /** One window of frames: live (the last second) or the last seconds of play. */
    public record View(int seconds, double fps, double avgMs, double lowFps, double maxMs, List<Row> rows) {
        public double duskMs() {
            double t = 0;
            for (Row r : rows) t += r.total();
            return t;
        }
    }

    /** Process-wide numbers from the last second. */
    public record Sys(double processCpu, double systemCpu, int cores, double mainBusy,
                      long heapUsed, long heapMax, long nonHeap, double allocMbPerSec,
                      double gcPerSec, double gcMsPerSec, double gpu) {}

    public enum Group {
        DUSK("Dusk client"), MAIN("Game main thread"), SERVER("Singleplayer server"),
        WORKERS("Game workers"), NETWORK("Network"), AUDIO("Sound"), OTHER("Java & other mods");

        public final String label;

        Group(String label) { this.label = label; }
    }

    /** A thread's CPU over the last second, as a fraction of one core. */
    public record ThreadRow(String name, Group group, double cpu) {}

    private record Second(double fps, double avgMs, double lowFps, double maxMs, double[][] ms) {}

    private static final Map<String, Section> byName = new HashMap<>();
    private static final List<Section> sections = new ArrayList<>();

    private static final long[] frames = new long[FRAME_CAP];
    private static int frameCount;
    private static long lastFrame, secondStart;
    private static boolean menuThisSecond;
    private static Second live;
    private static final ArrayDeque<Second> play = new ArrayDeque<>();

    private static final double[] fpsHistory = new double[HISTORY], duskHistory = new double[HISTORY],
            cpuHistory = new double[HISTORY], heapHistory = new double[HISTORY];
    private static int historyHead, historyCount;

    private static Sys sys;
    private static long lastGcCount = -1, lastGcMs, lastAlloc = -1, lastMainCpu = -1;
    private static Thread mainThread;
    private static final Section self = section("Performance monitor", "Client");

    private static long watchThreadsUntil;
    private static List<ThreadRow> threads = List.of();
    private static Map<Long, Long> threadCpu = new HashMap<>();

    private Perf() {}

    // ---- timers -------------------------------------------------------------------

    /** The section called {@code name}, made in {@code group} the first time. */
    public static Section section(String name, String group) {
        Section s = byName.get(name);
        if (s != null) {
            // a part timed before its module asked for its row takes the module's category
            if (s.group.equals("Client")) s.group = group;
            return s;
        }
        s = new Section(name, group, sections.size());
        byName.put(name, s);
        sections.add(s);
        return s;
    }

    /** A client part outside the module list; shares a module's row when the name matches. */
    public static Section part(String name) {
        return section(name, "Client");
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void end(Section s, int slot, long start) {
        s.acc[slot] += System.nanoTime() - start;
    }

    /** A Dusk menu drew this frame, so this second doesn't count as play. */
    public static void menuOpen() {
        menuThisSecond = true;
    }

    /** Keeps per-thread sampling on while the page shows (it costs more than the timers). */
    public static void watchThreads() {
        watchThreadsUntil = System.nanoTime() + 2_000_000_000L;
    }

    // ---- frames -------------------------------------------------------------------

    /** Start of each frame (Minecraft.runTick). */
    public static void frame() {
        long now = System.nanoTime();
        if (mainThread == null) mainThread = Thread.currentThread();
        if (lastFrame != 0 && frameCount < FRAME_CAP) frames[frameCount++] = now - lastFrame;
        lastFrame = now;
        if (secondStart == 0) secondStart = now;
        if (now - secondStart < 1_000_000_000L) return;
        try {
            roll(now);
        } catch (RuntimeException e) {
            // a bean that misbehaves costs this second's numbers, never the frame
            secondStart = now;
            frameCount = 0;
        }
    }

    private static void roll(long now) {
        long t0 = System.nanoTime();
        double secs = (now - secondStart) / 1e9;
        secondStart = now;
        double[][] ms = new double[sections.size()][];
        double dusk = 0;
        for (Section s : sections) {
            double[] m = new double[SLOTS];
            for (int i = 0; i < SLOTS; i++) {
                m[i] = s.acc[i] / 1e6 / secs;
                s.acc[i] = 0;
                dusk += m[i];
            }
            ms[s.index] = m;
        }
        Second sec = frameStats(secs, ms);
        live = sec;
        Minecraft mc = Minecraft.getInstance();
        if (!menuThisSecond && mc.level != null && sec.fps > 0) {
            play.addLast(sec);
            while (play.size() > PLAY_SECONDS) play.removeFirst();
        }
        menuThisSecond = false;
        sampleSystem(mc, secs);
        if (System.nanoTime() < watchThreadsUntil) sampleThreads(secs);
        else if (!threads.isEmpty()) {
            threads = List.of();
            threadCpu = new HashMap<>();
        }
        fpsHistory[historyHead] = sec.fps;
        duskHistory[historyHead] = sec.fps > 0 ? dusk / sec.fps : 0;
        cpuHistory[historyHead] = sys.processCpu;
        heapHistory[historyHead] = sys.heapUsed;
        historyHead = (historyHead + 1) % HISTORY;
        historyCount = Math.min(HISTORY, historyCount + 1);
        end(self, OTHER, t0);
    }

    private static Second frameStats(double secs, double[][] ms) {
        int n = frameCount;
        frameCount = 0;
        if (n == 0) return new Second(0, 0, 0, 0, ms);
        long[] sorted = Arrays.copyOf(frames, n);
        Arrays.sort(sorted);
        long sum = 0;
        for (long f : sorted) sum += f;
        double avg = sum / 1e6 / n;
        // the 1% low: the frame rate the slowest 1% of frames ran at
        double p99 = sorted[Math.min(n - 1, (int) Math.floor(n * 0.99))] / 1e6;
        return new Second(n / secs, avg, p99 > 0 ? 1000 / p99 : 0, sorted[n - 1] / 1e6, ms);
    }

    private static void sampleSystem(Minecraft mc, double secs) {
        double[] cpu = Jdk.cpu();
        double proc = cpu[0], system = cpu[1];
        int cores = Runtime.getRuntime().availableProcessors();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        double gcPer = lastGcCount < 0 ? 0 : (gcCount - lastGcCount) / secs;
        double gcMsPer = lastGcCount < 0 ? 0 : (gcMs - lastGcMs) / secs;
        lastGcCount = gcCount;
        lastGcMs = gcMs;
        double alloc = -1, mainBusy = -1;
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        long a = Jdk.allocated();
        if (a >= 0 && lastAlloc >= 0) alloc = (a - lastAlloc) / 1048576.0 / secs;
        lastAlloc = a;
        try {
            long mainCpu = tm.getCurrentThreadCpuTime();
            if (mainCpu >= 0 && lastMainCpu >= 0) mainBusy = Math.min(1, (mainCpu - lastMainCpu) / 1e9 / secs);
            lastMainCpu = mainCpu;
        } catch (UnsupportedOperationException ignored) {
            // no thread CPU time here
        }
        double gpu = 0;
        try {
            gpu = mc.getGpuUtilization();
        } catch (RuntimeException ignored) {
            // no GPU timer query on this driver
        }
        sys = new Sys(proc, system, cores, mainBusy, heap.getUsed(), heap.getMax() > 0 ? heap.getMax() : heap.getCommitted(),
                nonHeap.getUsed(), alloc, gcPer, gcMsPer, gpu);
    }

    private static void sampleThreads(double secs) {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        if (!tm.isThreadCpuTimeSupported()) return;
        if (!tm.isThreadCpuTimeEnabled()) tm.setThreadCpuTimeEnabled(true);
        long[] ids = tm.getAllThreadIds();
        ThreadInfo[] infos = tm.getThreadInfo(ids, 0);
        Map<Long, Long> next = new HashMap<>();
        List<ThreadRow> rows = new ArrayList<>();
        long mainId = mainThread == null ? -1 : mainThread.threadId();
        for (int i = 0; i < ids.length; i++) {
            if (infos[i] == null) continue;
            long cpu = tm.getThreadCpuTime(ids[i]);
            if (cpu < 0) continue;
            next.put(ids[i], cpu);
            Long before = threadCpu.get(ids[i]);
            if (before == null) continue;
            String name = infos[i].getThreadName();
            rows.add(new ThreadRow(name, ids[i] == mainId ? Group.MAIN : group(name), Math.max(0, (cpu - before) / 1e9 / secs)));
        }
        rows.sort((a, b) -> Double.compare(b.cpu, a.cpu));
        threadCpu = next;
        threads = rows;
    }

    /**
     * The JDK-only beans (com.sun.management), kept apart so a runtime
     * without jdk.management only loses these numbers (-1, shown as n/a).
     */
    private static final class Jdk {
        private static boolean broken;

        static double[] cpu() {
            if (!broken) {
                try {
                    if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                        return new double[] {os.getProcessCpuLoad(), os.getCpuLoad()};
                    }
                } catch (LinkageError | RuntimeException e) {
                    broken = true;
                }
            }
            return new double[] {-1, -1};
        }

        static long allocated() {
            if (!broken) {
                try {
                    if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean st) {
                        return st.getTotalThreadAllocatedBytes();
                    }
                } catch (LinkageError | RuntimeException e) {
                    broken = true;
                }
            }
            return -1;
        }
    }

    private static Group group(String name) {
        if (name.startsWith("duskclient-") || name.startsWith("Dusk ")) return Group.DUSK;
        if (name.equals("Server thread")) return Group.SERVER;
        if (name.startsWith("Worker-") || name.startsWith("IO-Worker") || name.startsWith("Download-")
                || name.startsWith("Chunk") || name.startsWith("Util")) return Group.WORKERS;
        if (name.contains("Netty") || name.contains("netty")) return Group.NETWORK;
        if (name.startsWith("Sound") || name.contains("OpenAL")) return Group.AUDIO;
        return Group.OTHER;
    }

    // ---- reading --------------------------------------------------------------------

    /** The last second, or null before the first one ends. */
    public static View live() {
        Second s = live;
        return s == null ? null : view(1, s.fps, s.avgMs, s.lowFps, s.maxMs, List.of(s));
    }

    /** The last ten seconds in a world with no Dusk menu open, or null before any. */
    public static View play() {
        if (play.isEmpty()) return null;
        double fps = 0, avg = 0, low = 0, max = 0;
        for (Second s : play) {
            fps += s.fps;
            avg += s.avgMs;
            low += s.lowFps;
            max = Math.max(max, s.maxMs);
        }
        int n = play.size();
        return view(n, fps / n, avg / n, low / n, max, play);
    }

    private static View view(int n, double fps, double avg, double low, double max, Iterable<Second> secs) {
        Map<Section, double[]> sum = new LinkedHashMap<>();
        for (Second s : secs) {
            for (Section sec : sections) {
                if (sec.index >= s.ms.length) continue;
                double[] m = s.ms[sec.index];
                double[] into = sum.computeIfAbsent(sec, k -> new double[SLOTS]);
                for (int i = 0; i < SLOTS; i++) into[i] += m[i] / n;
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Section, double[]> e : sum.entrySet()) {
            Row r = new Row(e.getKey(), e.getValue());
            if (r.total() > 0) rows.add(r);
        }
        rows.sort((a, b) -> Double.compare(b.total(), a.total()));
        return new View(n, fps, avg, low, max, rows);
    }

    public static Sys sys() {
        return sys;
    }

    /** Per-thread CPU, busiest first; empty until the page has been open a second. */
    public static List<ThreadRow> threads() {
        return threads;
    }

    /** The last minute, oldest first: 0 fps, 1 Dusk ms per frame, 2 process CPU (0..1), 3 heap bytes. */
    public static double[] history(int which) {
        double[] src = switch (which) {
            case 0 -> fpsHistory;
            case 1 -> duskHistory;
            case 2 -> cpuHistory;
            default -> heapHistory;
        };
        double[] out = new double[historyCount];
        for (int i = 0; i < historyCount; i++) {
            out[i] = src[(historyHead - historyCount + i + HISTORY) % HISTORY];
        }
        return out;
    }

    /**
     * About how many frames a second turning a part off would add: its time
     * comes off every frame, so the rate becomes {@code 1000 / (frame - cost)}.
     * Only holds while the main thread is what limits the frame rate.
     */
    public static double fpsGain(double msPerSec, double fps) {
        if (fps <= 0 || msPerSec <= 0 || msPerSec >= 999) return 0;
        return fps * msPerSec / (1000 - msPerSec);
    }
}
