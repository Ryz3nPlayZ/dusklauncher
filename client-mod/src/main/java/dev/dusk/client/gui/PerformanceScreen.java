package dev.dusk.client.gui;

import dev.dusk.client.perf.Perf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A btop for the client: the frame rate and its lows, how much of each
 * frame Dusk's modules and parts take on the main thread (with about how
 * many fps each one costs), process CPU, memory and GC, and per-thread CPU.
 * Numbers come from {@link Perf}; this page only lays them out. It can show
 * the last second live, or the last ten seconds of play from before the
 * menu opened, which the menu itself can't skew.
 */
public class PerformanceScreen extends PanelScreen {
    private static final String[] TABS = {"OVERVIEW", "CLIENT", "THREADS"};
    private static final int OVERVIEW = 0, CLIENT = 1, THREADS = 2;
    private static final String[] SLOT_NAMES = {"tick", "HUD", "world", "other"};
    private static final int CARD_H = 60, GAP = 6, ROW_H = 15, HEAD_H = 16, MAX_THREADS = 80;
    /** Remembered across openings, like the other pages' tabs. */
    private static int tab;
    private static boolean live;

    private int height;

    public PerformanceScreen(@Nullable Screen parent) {
        super(Component.literal("Performance"), parent);
        if (Perf.play() == null) live = true;
    }

    // ---- layout -------------------------------------------------------------

    @Override
    protected MenuFrame.Entry frameEntry() {
        return MenuFrame.Entry.PERFORMANCE;
    }

    @Override
    protected String[] tabs() {
        return TABS;
    }

    @Override
    protected int activeTab() {
        return tab;
    }

    @Override
    protected void selectTab(int i) {
        tab = i;
        scroll = 0;
    }

    @Override
    protected List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("close", Icons.CLOSE, "", "Close"));
        t.add(live ? new Tool("mode", null, "LIVE", "The last second, menu included. Click for the last 10 s of play")
                : new Tool("mode", null, "LAST 10 S OF PLAY", "Before this menu opened. Click for live numbers"));
        return t;
    }

    @Override
    protected void onTool(String id) {
        if (id.equals("mode")) live = !live;
        else super.onTool(id);
    }

    private void layout() {
        layoutPanel();
        listX = px + pad;
        listY = bodyY() + pad;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = py + ph - pad;
        clampScroll();
    }

    @Override
    protected int contentHeight() {
        return height;
    }

    @Nullable
    private static Perf.View view() {
        return live ? Perf.live() : Perf.play();
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        Perf.watchThreads();
        layout();
        drawPanel(c, mouseX, mouseY);
        c.scissor(listX, listY, listX + listW, listBottom);
        int y = listY - scroll;
        int end = switch (tab) {
            case CLIENT -> drawClient(c, y);
            case THREADS -> drawThreads(c, y);
            default -> drawOverview(c, y);
        };
        c.unscissor();
        height = end - y;
        drawScrollbar(c);
    }

    /** A message where a view would go; the y under it. */
    private int empty(Canvas c, int y) {
        String msg = live ? "Measuring... numbers appear after the first second."
                : "No play recorded yet. Close the menu and play for a few seconds in a world, then come back.";
        int ly = y + 4;
        for (String line : wrap(c, msg, listW - 8)) {
            c.text(line, listX + 4, ly, Theme.TEXT_MUTED, false);
            ly += 11;
        }
        return ly;
    }

    private int drawOverview(Canvas c, int y) {
        Perf.View v = view();
        Perf.Sys s = Perf.sys();
        if (v == null || s == null) return empty(c, y);
        int x = listX, w = listW;

        // the headline: frame rate, its lows, and whether a cap holds it
        Theme.plate(c, x, y, w, 40, Theme.SURFACE, Theme.SURFACE_BOT, false);
        String fps = Math.round(v.fps()) + "";
        c.push();
        c.translate(x + 8, y + 8);
        c.scale(2, 2);
        c.text(fps, 0, 0, Theme.TEXT, false);
        c.pop();
        int fx = x + 8 + c.textWidth(fps) * 2 + 4;
        c.text("fps", fx, y + 15, Theme.TEXT_MUTED, false);
        int sx = fx + c.textWidth("fps") + 14;
        String stats = "avg " + ms(v.avgMs()) + " ms   1% low " + Math.round(v.lowFps()) + " fps   worst " + ms(v.maxMs()) + " ms";
        c.text(Theme.ellipsize(c, stats, x + w - 8 - sx), sx, y + 9, Theme.TEXT, false);
        String cap = capNote(v.fps());
        String where = live ? "Last second, this menu included." : "Average of the last " + v.seconds() + " s of play.";
        c.text(Theme.ellipsize(c, where + (cap == null ? "" : "  " + cap), x + w - 8 - sx), sx, y + 22,
                cap == null ? Theme.TEXT_FAINT : Theme.WARN, false);
        y += 40 + GAP;

        double dusk = v.duskMs(), perFrame = v.fps() > 0 ? dusk / v.fps() : 0;
        double share = v.avgMs() > 0 ? perFrame / v.avgMs() : 0;
        y = card(c, y, "DUSK CLIENT",
                List.of(ms(perFrame) + " ms of each " + ms(v.avgMs()) + " ms frame (" + pct(share) + ")",
                        "About +" + Math.round(Perf.fpsGain(dusk, v.fps())) + " fps with every part off",
                        "See CLIENT for each module"),
                share > 0.15 ? Theme.WARN : Theme.OK, Perf.history(1), 0.25, "ms / frame");

        y = card(c, y, "FRAME RATE",
                List.of(Math.round(v.fps()) + " fps, 1% low " + Math.round(v.lowFps()),
                        "Frame time avg " + ms(v.avgMs()) + " ms, worst " + ms(v.maxMs()) + " ms",
                        "Graph: the last minute, live"),
                Theme.INFO, Perf.history(0), 60, "fps");

        List<String> cpu = new ArrayList<>();
        cpu.add(s.processCpu() < 0 ? "Minecraft: n/a" : "Minecraft " + pct(s.processCpu()) + " of " + s.cores()
                + " cores (~" + String.format(Locale.ROOT, "%.1f", s.processCpu() * s.cores()) + " busy)");
        cpu.add((s.mainBusy() < 0 ? "" : "Main thread busy " + pct(s.mainBusy()) + "   ")
                + (s.systemCpu() < 0 ? "" : "Whole PC " + pct(s.systemCpu())));
        cpu.add(s.gpu() > 0 ? "GPU busy " + Math.round(Math.min(100, s.gpu())) + "%" : "GPU: shown while F3 is open");
        y = card(c, y, "CPU", cpu, s.processCpu() > 0.8 ? Theme.WARN : Theme.INFO, Perf.history(2), 1, "% all cores");

        double used = s.heapUsed(), max = Math.max(1, s.heapMax());
        List<String> mem = new ArrayList<>();
        mem.add("Heap " + gb(used) + " / " + gb(max) + " GB (" + pct(used / max) + ")");
        mem.add((s.allocMbPerSec() < 0 ? "" : "Allocating " + Math.round(s.allocMbPerSec()) + " MB/s   ")
                + "Other " + Math.round(s.nonHeap() / 1048576.0) + " MB");
        mem.add("GC " + String.format(Locale.ROOT, "%.1f", s.gcPerSec()) + "/s, " + ms(s.gcMsPerSec()) + " ms/s paused");
        y = card(c, y, "MEMORY", mem, used / max > 0.85 ? Theme.BAD : Theme.INFO, Perf.history(3), max, "of max heap");

        String note = "Times are the main thread's: ticks, the HUD and the world pass, which is what holds the frame "
                + "rate back. GPU work (motion blur, shaders, skies) costs more than its CPU time shows, so turning "
                + "those off can gain more than the estimate. The fps estimates only hold while the frame rate isn't capped.";
        for (String line : wrap(c, note, w - 8)) {
            c.text(line, x + 4, y, Theme.TEXT_FAINT, false);
            y += 11;
        }
        return y;
    }

    /** A titled card: up to three lines on the left, a graph of the last minute on the right; the y under it. */
    private int card(Canvas c, int y, String title, List<String> lines, int color, double[] graph, double minMax, String unit) {
        int x = listX, w = listW;
        Theme.plate(c, x, y, w, CARD_H, Theme.SURFACE, Theme.SURFACE_BOT, false);
        int gw = Math.max(60, (int) (w * 0.42)), gx = x + w - gw - 8, tw = gx - 8 - (x + 8);
        c.text(title, x + 8, y + 6, Theme.TEXT_FAINT, false);
        int ly = y + 19;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) continue;
            c.text(Theme.ellipsize(c, lines.get(i), tw), x + 8, ly, i == 0 ? Theme.TEXT : Theme.TEXT_MUTED, false);
            ly += 12;
        }
        double max = minMax;
        for (double g : graph) max = Math.max(max, g);
        if (unit.equals("fps")) max = Math.ceil(max * 1.1 / 30) * 30;
        graph(c, graph, gx, y + 8, gw, CARD_H - 16, max, color);
        String label = unit.equals("% all cores") || unit.equals("of max heap") ? "100% " + unit
                : (unit.equals("fps") ? Math.round(max) + "" : ms(max)) + " " + unit;
        c.text(label, gx + gw - c.textWidth(label) - 2, y + 9, Theme.TEXT_FAINT, false);
        return y + CARD_H + GAP;
    }

    private static void graph(Canvas c, double[] v, int x, int y, int w, int h, double max, int color) {
        c.fill(x, y, x + w, y + h, Theme.pick(0x0DFFFFFF, 0xFF141414));
        int span = 60, dim = (color & 0x00FFFFFF) | 0x66000000;
        for (int i = 0; i < v.length; i++) {
            int slot = span - v.length + i;
            int x0 = x + w * slot / span, x1 = Math.max(x0 + 1, x + w * (slot + 1) / span - 1);
            int bh = (int) Math.round(Math.min(1, Math.max(0, v[i] / max)) * h);
            if (bh <= 0) continue;
            c.fill(x0, y + h - bh, x1, y + h, dim);
            c.fill(x0, y + h - bh, x1, y + h - bh + 1, color);
        }
    }

    private int drawClient(Canvas c, int y) {
        Perf.View v = view();
        if (v == null) return empty(c, y);
        int x = listX, w = listW;
        double fps = v.fps(), frame = v.avgMs();
        double total = v.duskMs(), totalFrame = fps > 0 ? total / fps : 0;
        String head = "Dusk " + ms(totalFrame) + " ms per frame (" + pct(frame > 0 ? totalFrame / frame : 0)
                + " of " + ms(frame) + " ms)   ~+" + Math.round(Perf.fpsGain(total, fps)) + " fps without any of it";
        c.text(Theme.ellipsize(c, head, w - 4), x + 2, y + 2, Theme.TEXT, false);
        y += HEAD_H;

        // columns from the right: bar, fps, % of frame, ms per frame, where
        boolean bars = w > 330;
        int barW = bars ? 56 : 0;
        int fpsX = x + w - barW - (bars ? 8 : 4), pctX = fpsX - 50, msX = pctX - 44, whereX = msX - 64 - 8;
        int nameW = whereX - 8 - (x + 6);
        c.text("PART", x + 6, y + 3, Theme.TEXT_FAINT, false);
        c.text("WHERE", whereX, y + 3, Theme.TEXT_FAINT, false);
        right(c, "MS/FRAME", msX, y + 3, Theme.TEXT_FAINT);
        right(c, "FRAME", pctX, y + 3, Theme.TEXT_FAINT);
        right(c, "FPS", fpsX, y + 3, Theme.TEXT_FAINT);
        y += ROW_H;
        c.fill(x, y - 2, x + w, y - 1, Theme.LINE);

        double worst = 0;
        for (Perf.Row r : v.rows()) worst = Math.max(worst, r.total());
        int i = 0;
        for (Perf.Row r : v.rows()) {
            if (i++ % 2 == 0) c.fill(x, y, x + w, y + ROW_H, Theme.HOVER);
            double perFrame = fps > 0 ? r.total() / fps : 0, share = frame > 0 ? perFrame / frame : 0;
            String name = r.section().name, group = r.section().group();
            int nw = c.textWidth(name);
            c.text(Theme.ellipsize(c, name, nameW), x + 6, y + 4, Theme.TEXT, false);
            if (nw + 6 + c.textWidth(group) <= nameW) c.text(group, x + 6 + nw + 6, y + 4, Theme.TEXT_FAINT, false);
            c.text(Theme.ellipsize(c, where(r), 64), whereX, y + 4, Theme.TEXT_MUTED, false);
            int col = share >= 0.05 ? Theme.BAD : share >= 0.01 ? Theme.WARN : Theme.TEXT;
            right(c, ms(perFrame), msX, y + 4, col);
            right(c, pct(share), pctX, y + 4, Theme.TEXT_MUTED);
            long gain = Math.round(Perf.fpsGain(r.total(), fps));
            right(c, gain > 0 ? "+" + gain : "<1", fpsX, y + 4, gain > 0 ? col : Theme.TEXT_FAINT);
            if (bars) {
                int bx = x + w - barW - 4, bw = (int) Math.round(barW * (worst > 0 ? r.total() / worst : 0));
                bar(c, bx, y + 6, barW, 3, bw, col == Theme.TEXT ? Theme.INFO : col);
            }
            y += ROW_H;
        }
        if (v.rows().isEmpty()) {
            c.text("Nothing measurable: no module took any time.", x + 6, y + 4, Theme.TEXT_MUTED, false);
            y += ROW_H;
        }
        y += 6;
        String note = "Each row is main-thread time. FPS is about what you'd gain turning that part off on its own. "
                + "Parts with no row cost nothing measurable, or work inside the game's own code where they can't be timed.";
        for (String line : wrap(c, note, w - 8)) {
            c.text(line, x + 4, y, Theme.TEXT_FAINT, false);
            y += 11;
        }
        return y;
    }

    private static String where(Perf.Row r) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Perf.SLOTS; i++) {
            if (r.ms()[i] <= 0.0005) continue;
            if (!b.isEmpty()) b.append(", ");
            b.append(SLOT_NAMES[i]);
        }
        return b.toString();
    }

    private int drawThreads(Canvas c, int y) {
        List<Perf.ThreadRow> rows = Perf.threads();
        int x = listX, w = listW;
        if (rows.isEmpty()) {
            c.text("Measuring threads... (they're timed only while this page is open)", x + 4, y + 4, Theme.TEXT_MUTED, false);
            return y + 16;
        }
        Map<Perf.Group, double[]> groups = new EnumMap<>(Perf.Group.class);
        for (Perf.ThreadRow r : rows) {
            double[] g = groups.computeIfAbsent(r.group(), k -> new double[2]);
            g[0] += r.cpu();
            g[1]++;
        }
        c.text(Theme.ellipsize(c, "CPU per thread, live. 100% is one full core.", w - 4), x + 2, y + 2, Theme.TEXT_MUTED, false);
        y += HEAD_H;
        int cpuX = x + w - 70, barX = x + w - 62, barW = 56;
        for (Map.Entry<Perf.Group, double[]> e : groups.entrySet()) {
            Theme.plate(c, x, y, w, ROW_H + 2, Theme.SURFACE, Theme.SURFACE_BOT, false);
            boolean dusk = e.getKey() == Perf.Group.DUSK;
            String label = e.getKey().label + "  ·  " + (int) e.getValue()[1] + (e.getValue()[1] == 1 ? " thread" : " threads");
            c.text(Theme.ellipsize(c, label, cpuX - 50 - x - 8), x + 8, y + 5, dusk ? Theme.INFO : Theme.TEXT, false);
            right(c, pct(e.getValue()[0]), cpuX, y + 5, dusk ? Theme.INFO : Theme.TEXT);
            bar(c, barX, y + 7, barW, 3, (int) Math.round(barW * Math.min(1, e.getValue()[0])), dusk ? Theme.INFO : Theme.TEXT_MUTED);
            y += ROW_H + 4;
        }
        y += 6;
        c.text("THREAD", x + 6, y + 3, Theme.TEXT_FAINT, false);
        right(c, "CPU", cpuX, y + 3, Theme.TEXT_FAINT);
        y += ROW_H;
        c.fill(x, y - 2, x + w, y - 1, Theme.LINE);
        int shown = 0, idle = 0;
        for (Perf.ThreadRow r : rows) {
            if (r.cpu() < 0.001 || shown >= MAX_THREADS) {
                idle++;
                continue;
            }
            if (shown++ % 2 == 0) c.fill(x, y, x + w, y + ROW_H, Theme.HOVER);
            boolean dusk = r.group() == Perf.Group.DUSK;
            int nameW = cpuX - 50 - (x + 6) - 90;
            c.text(Theme.ellipsize(c, r.name(), nameW), x + 6, y + 4, dusk ? Theme.INFO : Theme.TEXT, false);
            c.text(Theme.ellipsize(c, r.group().label, 86), x + 6 + nameW + 4, y + 4, Theme.TEXT_FAINT, false);
            int col = r.cpu() >= 0.5 ? Theme.WARN : dusk ? Theme.INFO : Theme.TEXT_MUTED;
            right(c, pct(r.cpu()), cpuX, y + 4, col);
            bar(c, barX, y + 6, barW, 3, (int) Math.round(barW * Math.min(1, r.cpu())), col);
            y += ROW_H;
        }
        if (idle > 0) {
            c.text(idle + " more " + (idle == 1 ? "thread" : "threads") + " idle", x + 6, y + 4, Theme.TEXT_FAINT, false);
            y += ROW_H;
        }
        return y + 4;
    }

    // ---- bits ---------------------------------------------------------------

    private static void bar(Canvas c, int x, int y, int w, int h, int filled, int color) {
        if (Theme.modern()) {
            Round.pill(c, x, y, w, h, Theme.TRACK);
            if (filled > 0) Round.pill(c, x, y, Math.max(h, filled), h, color);
        } else {
            c.fill(x, y, x + w, y + h, Theme.TRACK);
            if (filled > 0) c.fill(x, y, x + filled, y + h, color);
        }
    }

    private static void right(Canvas c, String s, int rightX, int y, int color) {
        c.text(s, rightX - c.textWidth(s), y, color, false);
    }

    /** Why the frame rate may be held, or null when nothing caps it. */
    @Nullable
    private static String capNote(double fps) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.enableVsync().get()) return "VSync is on, so fps may be held to your monitor.";
        int limit = mc.options.framerateLimit().get();
        if (limit < 260 && fps >= limit * 0.9) return "Capped at " + limit + " fps, so turning things off won't raise it.";
        return null;
    }

    private static String ms(double v) {
        if (v <= 0) return "0";
        if (v < 0.01) return "<0.01";
        return String.format(Locale.ROOT, v < 10 ? "%.2f" : "%.1f", v);
    }

    private static String pct(double f) {
        double p = f * 100;
        if (p > 0 && p < 0.1) return "<0.1%";
        return String.format(Locale.ROOT, p < 10 ? "%.1f%%" : "%.0f%%", p);
    }

    private static String gb(double bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1073741824.0);
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (clickChrome(mx, my, button)) return true;
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }
}
