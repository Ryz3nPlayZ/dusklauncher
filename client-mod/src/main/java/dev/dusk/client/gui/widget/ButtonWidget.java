package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;

/** A vanilla button filling its bounds. */
public class ButtonWidget extends Widget {
    private final String label;
    private final Runnable onClick;

    public ButtonWidget(String label, Runnable onClick) {
        this.label = label;
        this.onClick = onClick;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        Vanilla.button(c, label, x, y + (h - Vanilla.BUTTON_H) / 2, w, Vanilla.BUTTON_H, contains(mouseX, mouseY), true);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        onClick.run();
        return true;
    }
}
