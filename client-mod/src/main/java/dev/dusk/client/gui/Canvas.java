package dev.dusk.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The 2D calls our screens and HUD need, over whatever the game version
 * hands us to draw with (GuiGraphics on 1.21.11, GuiGraphicsExtractor on
 * 26.x). Implemented per target in {@code src/mc*} as GraphicsCanvas.
 * Coordinates are GUI-scaled pixels in the current transform.
 */
public interface Canvas {
    void fill(int x0, int y0, int x1, int y1, int argb);

    void fillGradient(int x0, int y0, int x1, int y1, int argbTop, int argbBottom);

    /** One-pixel border just inside the given box. */
    void outline(int x, int y, int w, int h, int argb);

    void text(Component text, int x, int y, int argb);

    void text(Component text, int x, int y, int argb, boolean shadow);

    void text(String text, int x, int y, int argb, boolean shadow);

    void centeredText(Component text, int x, int y, int argb);

    void centeredText(String text, int x, int y, int argb, boolean shadow);

    int textWidth(String text);

    int textWidth(Component text);

    int lineHeight();

    void item(ItemStack stack, int x, int y);

    /** Durability bar, count and cooldown overlay for an item slot. */
    void itemDecorations(ItemStack stack, int x, int y);

    /**
     * Draws a region of a texture sheet. {@code texture} is a namespaced id
     * ("minecraft:textures/gui/container/inventory.png"), {@code u}/{@code v}
     * the top-left of the region inside a {@code texW} x {@code texH} sheet and
     * {@code argb} a tint (ignored on 1.21.1, which has no tinted blit).
     */
    void blit(String texture, int x, int y, float u, float v, int w, int h, int texW, int texH, int argb);

    void push();

    void pop();

    void translate(float x, float y);

    void scale(float x, float y);

    /** Rotates around the current origin, clockwise, in radians. */
    void rotate(float radians);

    /** Clip to a box (in the current transform, like every other call). */
    void scissor(int x0, int y0, int x1, int y1);

    void unscissor();

    default void text(String text, int x, int y, int argb) {
        text(text, x, y, argb, true);
    }

    default void centeredText(String text, int x, int y, int argb) {
        centeredText(text, x, y, argb, true);
    }

    default void blit(String texture, int x, int y, float u, float v, int w, int h, int texW, int texH) {
        blit(texture, x, y, u, v, w, h, texW, texH, 0xFFFFFFFF);
    }

    /** Draws a horizontal line one pixel high from x0 to x1 inclusive. */
    default void hLine(int x0, int x1, int y, int argb) {
        fill(Math.min(x0, x1), y, Math.max(x0, x1) + 1, y + 1, argb);
    }

    default void vLine(int x, int y0, int y1, int argb) {
        fill(x, Math.min(y0, y1), x + 1, Math.max(y0, y1) + 1, argb);
    }
}
