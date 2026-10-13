package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Theme;
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
    public boolean carded() { return false; }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        if (text.isEmpty()) return;
        if (Theme.modern()) {
            Px.sans(c, text, x + 8 - Px.pad(), y, w - 8 + Px.pad(), h, Px.Size.S14, color == Vanilla.TEXT_DIM ? Theme.TEXT_MUTED : color,
                    false, true);
            return;
        }
        c.text(text, x + 6, y + (h - c.lineHeight()) / 2 + 1, Theme.modern() && color == Vanilla.TEXT_DIM ? Theme.TEXT_MUTED : color,
                !Theme.modern());
    }
}
