package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;

/** Static text, optionally wrapped over several lines by the caller. */
public class LabelWidget extends Widget {
    private final String text;
    private final int color;

    public LabelWidget(String text) {
        this(text, Vanilla.TEXT_DIM);
    }

    public LabelWidget(String text, int color) {
        this.text = text;
        this.color = color;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        c.text(text, x + 6, y + (h - c.lineHeight()) / 2 + 1, color, true);
    }
}
