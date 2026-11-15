package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;

/** A launcher PxButton across its bounds, one control tall. */
public class ButtonWidget extends Widget {
    private final String label;
    private final Runnable onClick;

    public ButtonWidget(String label, Runnable onClick) {
        this.label = label;
        this.onClick = onClick;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        int by = y + (h - Px.H) / 2;
        boolean hover = contains(mouseX, mouseY);
        Px.button(c, x, by, w, Px.H, hover, true);
        Px.label(c, label, x, by, w, Px.H, hover, false, 0xFF);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        onClick.run();
        return true;
    }
}
