package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;

public class ButtonWidget extends Widget {
    private final String label;
    private final Runnable onClick;
    private boolean primary;

    public ButtonWidget(String label, Runnable onClick) {
        this.label = label;
        this.onClick = onClick;
    }

    public ButtonWidget primary() {
        this.primary = true;
        return this;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        boolean hover = contains(mouseX, mouseY);
        int bg = primary ? (hover ? Theme.ACCENT : Theme.ACCENT_DIM) : (hover ? 0xFF34343F : Theme.TOGGLE_OFF);
        c.fill(x, y, x + w, y + h, bg);
        int fg = primary ? 0xFF14140A : Theme.TEXT;
        c.centeredText(label, x + w / 2, y + (h - c.lineHeight()) / 2 + 1, fg, false);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        onClick.run();
        return true;
    }
}
