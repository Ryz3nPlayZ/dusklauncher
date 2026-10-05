package dev.dusk.client.media.impl;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.MenuScreen;
import dev.dusk.client.gui.NavBar;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.media.Mcpr;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * What Esc opens while a replay plays, in place of the pause menu: the
 * timeline (click or drag to seek), play/pause, speed, whose eyes to
 * look through, in/out marks and EXPORT VIDEO, and the way out. While a
 * video exports it shows the export's progress instead.
 */
public final class ReplayMenuScreen extends MenuScreen {
    private static final int BTN_H = 20, GAP = 4, BAR_H = 10, ROWS = 4;
    /** 30 or 60 fps, remembered for the session. */
    private static int fpsIndex = 1;

    private int px, py, pw, ph, pad;
    private int barX, barY, barW;
    /** Where a drag on the timeline will seek to when released, or -1. */
    private long dragTo = -1;
    /** Why EXPORT VIDEO didn't start, until the next click. */
    private @org.jetbrains.annotations.Nullable String notice;
    /** News that isn't a failure (ffmpeg arrived), until the next click. */
    private @org.jetbrains.annotations.Nullable String info;

    public ReplayMenuScreen() {
        super(Component.literal("Replay"), null);
    }

    private void layout() {
        pw = Math.min(360, this.width - 16);
        pad = 10;
        int rows = VideoExporter.active() ? 1 : ROWS;
        ph = pad + 12 + 6 + BAR_H + 4 + 10 + 8 + rows * (BTN_H + GAP) - GAP + pad;
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        barX = px + pad;
        barW = pw - 2 * pad;
        barY = py + pad + 12 + 6;
    }

    private int rowY(int row) {
        return barY + BAR_H + 4 + 10 + 8 + row * (BTN_H + GAP);
    }

    private long timeAt(double mx, ReplayPlayer p) {
        double f = Math.max(0, Math.min(1, (mx - barX) / barW));
        return Math.round(f * p.duration);
    }

    @Override
    protected void drawMenu(Canvas c, int mx, int my, float delta) {
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null) {
            onClose();
            return;
        }
        layout();
        if (FfmpegFetcher.takeDone()) info = "ffmpeg is ready. Press EXPORT VIDEO.";
        String fetchError = FfmpegFetcher.takeError();
        if (fetchError != null) notice = fetchError;
        c.fill(0, 0, this.width, this.height, 0x4D000000);
        NavBar.window(c, px, py, pw, ph);
        if (VideoExporter.active()) {
            drawExport(c, p, mx, my);
            return;
        }

        String kind = p.clip ? "CLIP" : "REPLAY";
        Theme.label(c, kind, px + pad, py + pad + 2, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
        int kw = Theme.labelWidth(c, kind, 1f);
        c.text(Theme.ellipsize(c, p.title, pw - 2 * pad - kw - 8), px + pad + kw + 8, py + pad + 2, Theme.TEXT_MUTED, false);

        // timeline
        long shown = dragTo >= 0 ? dragTo : p.clock();
        float f = p.duration <= 0 ? 0 : Math.min(1f, shown / (float) p.duration);
        boolean overBar = Vanilla.inside(mx, my, barX, barY - 3, barW, BAR_H + 6);
        c.fill(barX, barY, barX + barW, barY + BAR_H, 0xFF141414);
        int fill = Math.round(barW * f);
        c.fill(barX, barY, barX + fill, barY + BAR_H, Theme.ACCENT);
        // the stretch an export covers: a band under the bar, a flag at each end
        if (p.markIn() >= 0 || p.markOut() >= 0) {
            int a = xAt(p.markIn() >= 0 ? p.markIn() : 0, p), b = xAt(p.markOut() >= 0 ? p.markOut() : p.duration, p);
            c.fill(a, barY + BAR_H + 1, b, barY + BAR_H + 3, Theme.GREEN_UP);
            if (p.markIn() >= 0) c.fill(a, barY - 2, a + 1, barY + BAR_H + 3, Theme.GREEN_UP);
            if (p.markOut() >= 0) c.fill(b - 1, barY - 2, b, barY + BAR_H + 3, Theme.GREEN_UP);
        }
        c.outline(barX, barY, barW, BAR_H, overBar || dragTo >= 0 ? Theme.LABEL_UP : 0xFF3A3A3A);
        int knob = barX + fill;
        c.fill(knob - 1, barY - 2, knob + 2, barY + BAR_H + 2, Theme.TEXT);
        if (overBar && dragTo < 0) {
            long at = timeAt(mx, p);
            String tip = Mcpr.duration(at);
            int tx = Math.max(barX, Math.min(barX + barW - c.textWidth(tip), mx - c.textWidth(tip) / 2));
            c.vLine(mx, barY, barY + BAR_H - 1, 0xAAFFFFFF);
            c.text(tip, tx, barY - 11, Theme.TEXT, true);
        }
        String time = Mcpr.duration(shown) + " / " + Mcpr.duration(p.duration);
        c.text(time, barX, barY + BAR_H + 4, Theme.TEXT, false);
        String state = p.paused() ? "PAUSED" : "PLAYING";
        c.text(state, barX + barW - c.textWidth(state), barY + BAR_H + 4, p.paused() ? Theme.TEXT_FAINT : Theme.GREEN_UP, false);

        // row 0: play/pause and speeds
        int y0 = rowY(0);
        int playW = 70;
        boxButton(c, p.paused() ? "PLAY" : "PAUSE", barX, y0, playW, mx, my, Theme.Family.ACCENT, false);
        int n = ReplayPlayer.SPEEDS.length;
        int sx = barX + playW + GAP, sw = (barW - playW - GAP - (n - 1) * GAP) / n;
        for (int i = 0; i < n; i++) {
            boolean on = i == p.speedIndex();
            boxButton(c, speedLabel(ReplayPlayer.SPEEDS[i]), sx + i * (sw + GAP), y0, sw, mx, my,
                    on ? Theme.Family.ACCENT : Theme.Family.GREY, on);
        }

        // row 1: point of view, with the players either side
        int y1 = rowY(1);
        String who = p.povName();
        String view = who != null ? "WATCHING " + who.toUpperCase(java.util.Locale.ROOT) : p.pov() ? "PLAYER'S VIEW" : "FREE CAMERA";
        boxButton(c, "<", barX, y1, BTN_H, mx, my, Theme.Family.GREY, false);
        boxButton(c, view, barX + BTN_H + GAP, y1, barW - 2 * (BTN_H + GAP), mx, my, Theme.Family.GREY, p.pov());
        boxButton(c, ">", barX + barW - BTN_H, y1, BTN_H, mx, my, Theme.Family.GREY, false);

        // row 2: in/out marks, frame rate, export
        int y2 = rowY(2);
        int[] cols = exportCols();
        boxButton(c, p.markIn() >= 0 ? "IN " + Mcpr.duration(p.markIn()) : "MARK IN", cols[0], y2, cols[1], mx, my,
                Theme.Family.GREY, p.markIn() >= 0);
        boxButton(c, p.markOut() >= 0 ? "OUT " + Mcpr.duration(p.markOut()) : "MARK OUT", cols[2], y2, cols[3], mx, my,
                Theme.Family.GREY, p.markOut() >= 0);
        boxButton(c, VideoExporter.FPS[fpsIndex] + " FPS", cols[4], y2, cols[5], mx, my, Theme.Family.GREY, false);
        boxButton(c, FfmpegFetcher.running() ? FfmpegFetcher.progressLabel() : "EXPORT VIDEO", cols[6], y2, cols[7], mx, my,
                Theme.Family.ACCENT, false);

        // row 3: resume and exit
        int y3 = rowY(3), half = (barW - GAP) / 2;
        boxButton(c, "RESUME", barX, y3, half, mx, my, Theme.Family.GREEN, false);
        boxButton(c, p.clip ? "BACK TO CLIPS" : "BACK TO REPLAYS", barX + half + GAP, y3, barW - half - GAP, mx, my,
                Theme.Family.GREY, false);

        String say = notice != null ? notice : info != null ? info : VideoExporter.lastResult();
        int below = py + ph + 6;
        if (say != null) {
            c.text(Theme.ellipsize(c, say, this.width - 16), (this.width - Math.min(this.width - 16, c.textWidth(say))) / 2, below,
                    notice != null ? Theme.RED_UP : Theme.TEXT, true);
            below += 12;
        }
        String hint = "← → seek   ↑ ↓ speed   P pause   V view   , . player   I O mark   right-click a mark to clear";
        c.text(hint, (this.width - c.textWidth(hint)) / 2, below, Theme.TEXT_FAINT, true);
    }

    /** While exporting: progress, and a way to stop. The video itself never shows this menu. */
    private void drawExport(Canvas c, ReplayPlayer p, int mx, int my) {
        Theme.label(c, "EXPORTING VIDEO", px + pad, py + pad + 2, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
        float f = VideoExporter.progress();
        c.fill(barX, barY, barX + barW, barY + BAR_H, 0xFF141414);
        c.fill(barX, barY, barX + Math.round(barW * f), barY + BAR_H, Theme.GREEN_UP);
        c.outline(barX, barY, barW, BAR_H, 0xFF3A3A3A);
        c.text(VideoExporter.status(), barX, barY + BAR_H + 4, Theme.TEXT, false);
        int y0 = rowY(0), half = (barW - GAP) / 2;
        boxButton(c, "KEEP GOING", barX, y0, half, mx, my, Theme.Family.GREEN, false);
        boxButton(c, "CANCEL EXPORT", barX + half + GAP, y0, barW - half - GAP, mx, my, Theme.Family.GREY, false);
        String hint = "The menu and HUD aren't in the video. Keep the window the same size until it's done.";
        c.text(hint, (this.width - c.textWidth(hint)) / 2, py + ph + 6, Theme.TEXT_FAINT, true);
    }

    /** x and width of MARK IN, MARK OUT, FPS and EXPORT VIDEO. */
    private int[] exportCols() {
        int exportW = 96, fpsW = 52;
        int markW = (barW - exportW - fpsW - 3 * GAP) / 2;
        int x0 = barX, x1 = x0 + markW + GAP, x2 = x1 + markW + GAP, x3 = x2 + fpsW + GAP;
        return new int[]{x0, markW, x1, markW, x2, fpsW, x3, barX + barW - x3};
    }

    private int xAt(long t, ReplayPlayer p) {
        return barX + (p.duration <= 0 ? 0 : Math.round(barW * Math.min(1f, t / (float) p.duration)));
    }

    private static String speedLabel(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    /** A {@link Theme#box} with a centred two-tone label; {@code on} tints the label green. */
    private static void boxButton(Canvas c, String label, int x, int y, int w, int mx, int my, Theme.Family f, boolean on) {
        boolean hover = Vanilla.inside(mx, my, x, y, w, BTN_H);
        Theme.box(c, x, y, w, BTN_H, f, hover, false);
        int up = on ? Theme.GREEN_UP : Theme.LABEL_UP, lo = on ? Theme.GREEN_LO : Theme.LABEL_LO;
        Theme.label(c, label, x + (w - Theme.labelWidth(c, label, 1f)) / 2, y + (BTN_H - 7) / 2,
                Theme.filter(up, hover, false), Theme.filter(lo, hover, false), 1f);
    }

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null) return false;
        layout();
        notice = null;
        info = null;
        if (VideoExporter.active()) {
            int y0 = rowY(0), half = (barW - GAP) / 2;
            if (button != 0) return false;
            if (Vanilla.inside(mx, my, barX, y0, half, BTN_H)) onClose();
            else if (Vanilla.inside(mx, my, barX + half + GAP, y0, barW - half - GAP, BTN_H)) VideoExporter.cancel();
            return Vanilla.inside(mx, my, px, py, pw, ph);
        }
        int y2 = rowY(2);
        int[] cols = exportCols();
        // right-click clears a mark
        if (Vanilla.inside(mx, my, cols[0], y2, cols[1], BTN_H)) {
            p.setMarkIn(button == 1 ? -1 : p.clock());
            return true;
        }
        if (Vanilla.inside(mx, my, cols[2], y2, cols[3], BTN_H)) {
            p.setMarkOut(button == 1 ? -1 : p.clock());
            return true;
        }
        if (button != 0) return false;
        if (Vanilla.inside(mx, my, cols[4], y2, cols[5], BTN_H)) {
            fpsIndex = (fpsIndex + 1) % VideoExporter.FPS.length;
            return true;
        }
        if (Vanilla.inside(mx, my, cols[6], y2, cols[7], BTN_H)) {
            if (FfmpegFetcher.running()) {
                info = "Downloading ffmpeg for video export. It only happens once.";
            } else if (VideoExporter.findFfmpeg() == null && FfmpegFetcher.available()) {
                FfmpegFetcher.start();
                info = "Downloading ffmpeg (" + FfmpegFetcher.sizeMb() + " MB) for video export. It only happens once.";
            } else {
                notice = VideoExporter.start(p, VideoExporter.FPS[fpsIndex]);
            }
            return true;
        }
        if (Vanilla.inside(mx, my, barX, barY - 3, barW, BAR_H + 6)) {
            dragTo = timeAt(mx, p);
            return true;
        }
        int y0 = rowY(0), playW = 70;
        if (Vanilla.inside(mx, my, barX, y0, playW, BTN_H)) {
            p.setPaused(!p.paused());
            return true;
        }
        int n = ReplayPlayer.SPEEDS.length;
        int sx = barX + playW + GAP, sw = (barW - playW - GAP - (n - 1) * GAP) / n;
        for (int i = 0; i < n; i++) {
            if (Vanilla.inside(mx, my, sx + i * (sw + GAP), y0, sw, BTN_H)) {
                p.setSpeed(i);
                return true;
            }
        }
        int y1 = rowY(1);
        if (Vanilla.inside(mx, my, barX, y1, BTN_H, BTN_H)) {
            p.cyclePov(-1);
            return true;
        }
        if (Vanilla.inside(mx, my, barX + barW - BTN_H, y1, BTN_H, BTN_H)) {
            p.cyclePov(1);
            return true;
        }
        if (Vanilla.inside(mx, my, barX, y1, barW, BTN_H)) {
            p.togglePov();
            return true;
        }
        int y3 = rowY(3), half = (barW - GAP) / 2;
        if (Vanilla.inside(mx, my, barX, y3, half, BTN_H)) {
            onClose();
            return true;
        }
        if (Vanilla.inside(mx, my, barX + half + GAP, y3, barW - half - GAP, BTN_H)) {
            p.exit();
            return true;
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    protected boolean menuDrag(double mx, double my, int button) {
        ReplayPlayer p = ReplayPlayer.current();
        if (dragTo < 0 || p == null || VideoExporter.active()) return false;
        dragTo = timeAt(mx, p);
        return true;
    }

    /** Seeks once, on release: going back reloads the world, so it shouldn't happen for every pixel of a drag. */
    @Override
    protected boolean menuRelease(double mx, double my, int button) {
        ReplayPlayer p = ReplayPlayer.current();
        if (dragTo < 0) return false;
        long to = dragTo;
        dragTo = -1;
        if (p != null) {
            boolean back = to < p.clock();
            if (back) onClose();
            p.seek(to);
        }
        return true;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null || VideoExporter.active()) return false;
        switch (key) {
            case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_P -> p.setPaused(!p.paused());
            case GLFW.GLFW_KEY_UP -> p.setSpeed(p.speedIndex() + 1);
            case GLFW.GLFW_KEY_DOWN -> p.setSpeed(p.speedIndex() - 1);
            case GLFW.GLFW_KEY_V -> p.togglePov();
            case GLFW.GLFW_KEY_COMMA -> p.cyclePov(-1);
            case GLFW.GLFW_KEY_PERIOD -> p.cyclePov(1);
            case GLFW.GLFW_KEY_I -> p.setMarkIn(p.clock());
            case GLFW.GLFW_KEY_O -> p.setMarkOut(p.clock());
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
