package dev.dusk.client.gui;

import net.minecraft.network.chat.Component;

/**
 * The handful of 2D calls our screens need, over whatever the game version
 * hands a screen to draw with (GuiGraphics on 1.21.11, GuiGraphicsExtractor
 * on 26.x). Implemented per target in {@code src/mc*}.
 */
public interface Canvas {
    void fill(int x0, int y0, int x1, int y1, int argb);

    void text(Component text, int x, int y, int argb);

    void centeredText(Component text, int x, int y, int argb);

    int textWidth(String text);
}
