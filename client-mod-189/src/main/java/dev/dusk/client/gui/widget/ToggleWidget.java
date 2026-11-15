package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The launcher's boolean Choice: OFF and ON segments, the current one in the
 * accent family. Clicking a segment picks it; clicking the label flips it.
 * The modern look draws a switch at the right of the same slot instead, and
 * any click on the row flips it.
 */
public class ToggleWidget extends SettingRow {
    private final BooleanSupplier get;
    private final Consumer<Boolean> set;
    /** The switch knob's travel, 0 off to 1 on, eased toward the value each frame. */
    private float knob = -1;

    public ToggleWidget(String label, BooleanSupplier get, Consumer<Boolean> set) {
        super(label);
        this.get = get;
        this.set = set;
    }

    @Override
    protected int controlWidth() { return 2 * Px.MIN_W + Px.GAP; }

    private int segmentX(boolean on) {
        return controlX() + (on ? Px.MIN_W + Px.GAP : 0);
    }

    private boolean onRow(double mx, double my) {
        return Vanilla.inside(mx, my, x, top(), controlX() + controlWidth() - x, SQUARE);
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        boolean value = get.getAsBoolean();
        if (Theme.modern()) {
            drawSwitch(c, value, onRow(mouseX, mouseY));
            return;
        }
        segment(c, false, !value, mouseX, mouseY);
        segment(c, true, value, mouseX, mouseY);
    }

    public static final int SWITCH_W = 26, SWITCH_H = 14;

    private void drawSwitch(Canvas c, boolean value, boolean hover) {
        knob = drawSwitch(c, controlX() + controlWidth() - SWITCH_W, top() + (SQUARE - SWITCH_H) / 2, value, hover, knob);
    }

    /**
     * The modern switch, {@link #SWITCH_W} by {@link #SWITCH_H} at {@code (sx, sy)}.
     * {@code knob} is the knob's travel from the last frame (negative to snap);
     * returns it eased one frame toward {@code value}.
     */
    public static float drawSwitch(Canvas c, int sx, int sy, boolean value, boolean hover, float knob) {
        float target = value ? 1 : 0;
        knob = knob < 0 ? target : knob + (target - knob) * 0.35f;
        if (Math.abs(target - knob) < 0.01f) knob = target;
        Round.begin(c);
        int x0 = Round.w(sx), y0 = Round.w(sy), x1 = Round.w(sx + SWITCH_W), y1 = Round.w(sy + SWITCH_H);
        int track = mix(hover ? mix(Theme.TRACK, Theme.TEXT_MUTED, 0.12f) : Theme.TRACK, Theme.ACCENT, knob);
        Round.fillW(c, x0, y0, x1, y1, 64, track);
        int inset = Math.max(2, Round.w(2)), d = y1 - y0 - 2 * inset;
        int kx = Math.round(x0 + inset + (x1 - x0 - 2 * inset - d) * knob);
        int knobColor = mix(hover ? Theme.TEXT : Theme.TEXT_MUTED, Theme.INK, knob);
        Round.fillW(c, kx, y0 + inset, kx + d, y0 + inset + d, 64, knobColor);
        Round.end(c);
        return knob;
    }

    private static int mix(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int ca = a >>> sh & 0xFF, cb = b >>> sh & 0xFF;
            r |= Math.round(ca + (cb - ca) * t) << sh;
        }
        return r;
    }

    private void segment(Canvas c, boolean on, boolean selected, int mouseX, int mouseY) {
        int sx = segmentX(on), y = top();
        boolean hover = Vanilla.inside(mouseX, mouseY, sx, y, Px.MIN_W, SQUARE);
        Px.box(c, sx, y, Px.MIN_W, SQUARE, selected ? Theme.Family.ACCENT : Theme.Family.GREY, hover, false, 0xFF);
        Px.label(c, on ? "On" : "Off", sx, y, Px.MIN_W, SQUARE, Px.Size.S16, selected ? Px.Tone.ACCENT : Px.Tone.GREY,
                hover, false, false, 0xFF);
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (button != 0 || !onRow(mx, my)) return false;
        boolean value = get.getAsBoolean();
        if (Theme.modern()) value = !value;
        else if (Vanilla.inside(mx, my, segmentX(true), top(), Px.MIN_W, SQUARE)) value = true;
        else if (Vanilla.inside(mx, my, segmentX(false), top(), Px.MIN_W, SQUARE)) value = false;
        else value = !value;
        if (value != get.getAsBoolean()) set.accept(value);
        return true;
    }
}
