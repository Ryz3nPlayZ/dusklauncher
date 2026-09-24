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
        Theme.Kind kind = primary ? Theme.Kind.PRIMARY : Theme.Kind.NORMAL;
        Theme.button(c, x, y, w, h, hover, kind);
        Theme.buttonLabel(c, label, x, y, w, h, hover, kind);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        onClick.run();
        return true;
    }
}
