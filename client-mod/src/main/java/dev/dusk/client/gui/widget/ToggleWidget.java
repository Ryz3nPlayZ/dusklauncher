package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Labelled on/off switch drawn as a pill with a sliding knob. */
public class ToggleWidget extends Widget {
    public static final int SWITCH_W = 22, SWITCH_H = 10;

    private final String label;
    private final BooleanSupplier get;
    private final Consumer<Boolean> set;

    public ToggleWidget(String label, BooleanSupplier get, Consumer<Boolean> set) {
        this.label = label;
        this.get = get;
        this.set = set;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        if (contains(mouseX, mouseY)) c.fill(x, y, x + w, y + h, Theme.ROW_HOVER);
        if (label != null) c.text(label, x + 4, y + (h - c.lineHeight()) / 2 + 1, Theme.TEXT, false);
        drawSwitch(c, x + w - SWITCH_W - 4, y + (h - SWITCH_H) / 2, get.getAsBoolean());
    }

    public static void drawSwitch(Canvas c, int sx, int sy, boolean on) {
        c.fill(sx, sy, sx + SWITCH_W, sy + SWITCH_H, on ? Theme.ACCENT : Theme.TOGGLE_OFF);
        int kx = on ? sx + SWITCH_W - SWITCH_H + 1 : sx + 1;
        c.fill(kx, sy + 1, kx + SWITCH_H - 2, sy + SWITCH_H - 1, on ? 0xFF14140A : Theme.TEXT_MUTED);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        set.accept(!get.getAsBoolean());
        return true;
    }
}
