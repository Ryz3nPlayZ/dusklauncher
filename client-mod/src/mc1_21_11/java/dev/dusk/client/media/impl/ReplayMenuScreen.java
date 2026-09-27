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
 * timeline (click or drag to seek), play/pause, speed, the recording
 * player's point of view, and the way out.
 */
public final class ReplayMenuScreen extends MenuScreen {
    private static final int BTN_H = 20, GAP = 4, BAR_H = 10;

    private int px, py, pw, ph, pad;
    private int barX, barY, barW;
    /** Where a drag on the timeline will seek to when released, or -1. */
    private long dragTo = -1;

    public ReplayMenuScreen() {
        super(Component.literal("Replay"), null);
    }

    private void layout() {
        pw = Math.min(360, this.width - 16);
        pad = 10;
        ph = pad + 12 + 6 + BAR_H + 4 + 10 + 8 + 3 * (BTN_H + GAP) - GAP + pad;
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
        c.fill(0, 0, this.width, this.height, 0x4D000000);
        NavBar.window(c, px, py, pw, ph);

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

        // row 1: point of view
        int y1 = rowY(1);
        boxButton(c, p.pov() ? "FREE CAMERA" : "PLAYER'S VIEW", barX, y1, barW, mx, my, Theme.Family.GREY, p.pov());

        // row 2: resume and exit
        int y2 = rowY(2), half = (barW - GAP) / 2;
        boxButton(c, "RESUME", barX, y2, half, mx, my, Theme.Family.GREEN, false);
        boxButton(c, p.clip ? "BACK TO CLIPS" : "BACK TO REPLAYS", barX + half + GAP, y2, barW - half - GAP, mx, my,
                Theme.Family.GREY, false);

        String hint = "← → seek   ↑ ↓ speed   P pause   V view";
        c.text(hint, (this.width - c.textWidth(hint)) / 2, py + ph + 6, Theme.TEXT_FAINT, true);
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
        if (p == null || button != 0) return false;
        layout();
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
        if (Vanilla.inside(mx, my, barX, rowY(1), barW, BTN_H)) {
            p.togglePov();
            return true;
        }
        int y2 = rowY(2), half = (barW - GAP) / 2;
        if (Vanilla.inside(mx, my, barX, y2, half, BTN_H)) {
            onClose();
            return true;
        }
        if (Vanilla.inside(mx, my, barX + half + GAP, y2, barW - half - GAP, BTN_H)) {
            p.exit();
            return true;
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    protected boolean menuDrag(double mx, double my, int button) {
        ReplayPlayer p = ReplayPlayer.current();
        if (dragTo < 0 || p == null) return false;
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
        if (p == null) return false;
        switch (key) {
            case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_P -> p.setPaused(!p.paused());
            case GLFW.GLFW_KEY_UP -> p.setSpeed(p.speedIndex() + 1);
            case GLFW.GLFW_KEY_DOWN -> p.setSpeed(p.speedIndex() - 1);
            case GLFW.GLFW_KEY_V -> p.togglePov();
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
