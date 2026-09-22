package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;

/** Static text, optionally wrapped over several lines by the caller. */
public class LabelWidget extends Widget {
    private final String text;
    private final int color;

    public LabelWidget(String text) {
        this(text, Theme.TEXT_MUTED);
    }

    public LabelWidget(String text, int color) {
        this.text = text;
        this.color = color;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        c.text(text, x, y + (h - c.lineHeight()) / 2, color, false);
    }
}
