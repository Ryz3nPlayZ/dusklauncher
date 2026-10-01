package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The launcher's boolean Choice: OFF and ON segments, the current one in the
 * accent family. Clicking a segment picks it; clicking the label flips it.
 */
public class ToggleWidget extends SettingRow {
    private final BooleanSupplier get;
    private final Consumer<Boolean> set;

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
        segment(c, false, !value, mouseX, mouseY);
        segment(c, true, value, mouseX, mouseY);
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
        if (Vanilla.inside(mx, my, segmentX(true), top(), Px.MIN_W, SQUARE)) value = true;
        else if (Vanilla.inside(mx, my, segmentX(false), top(), Px.MIN_W, SQUARE)) value = false;
        else value = !value;
        if (value != get.getAsBoolean()) set.accept(value);
        return true;
    }
}
