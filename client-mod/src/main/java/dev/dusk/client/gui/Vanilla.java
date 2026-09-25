package dev.dusk.client.gui;

/**
 * Vanilla-looking controls for the Dusk menus (the Flex-HUD look): the
 * game's own widget sprites, blitted as standalone textures and stretched
 * nine-slice, so buttons, sliders and text boxes match every resource pack.
 */
public final class Vanilla {
    private Vanilla() {}

    private static final String SPRITES = "minecraft:textures/gui/sprites/widget/";

    /** Flex-HUD's row highlight. */
    public static final int ROW_HOVER = 0x55C5C5C5;
    public static final int TEXT = 0xFFFFFFFF, TEXT_OFF = 0xFFA0A0A0, TEXT_DIM = 0xFFAFAFAF;
    public static final int BUTTON_H = 20;

    /**
     * Draws a {@code tw}x{@code th} sprite into any size: the corners as they
     * are, the edges and middle tiled (never scaled, like vanilla).
     */
    public static void nineSlice(Canvas c, String sprite, int x, int y, int w, int h, int tw, int th, int b, int argb) {
        if (w <= 0 || h <= 0) return;
        String tex = SPRITES + sprite + ".png";
        if (w < 2 * b || h < 2 * b) {
            c.blit(tex, x, y, 0, 0, Math.min(w, tw), Math.min(h, th), tw, th, argb);
            return;
        }
        int cw = tw - 2 * b, ch = th - 2 * b, iw = w - 2 * b, ih = h - 2 * b;
        c.blit(tex, x, y, 0, 0, b, b, tw, th, argb);
        c.blit(tex, x + w - b, y, tw - b, 0, b, b, tw, th, argb);
        c.blit(tex, x, y + h - b, 0, th - b, b, b, tw, th, argb);
        c.blit(tex, x + w - b, y + h - b, tw - b, th - b, b, b, tw, th, argb);
        for (int dx = 0; dx < iw; dx += cw) {
            int sw = Math.min(cw, iw - dx);
            c.blit(tex, x + b + dx, y, b, 0, sw, b, tw, th, argb);
            c.blit(tex, x + b + dx, y + h - b, b, th - b, sw, b, tw, th, argb);
            for (int dy = 0; dy < ih; dy += ch) {
                c.blit(tex, x + b + dx, y + b + dy, b, b, sw, Math.min(ch, ih - dy), tw, th, argb);
            }
        }
        for (int dy = 0; dy < ih; dy += ch) {
            int sh = Math.min(ch, ih - dy);
            c.blit(tex, x, y + b + dy, 0, b, b, sh, tw, th, argb);
            c.blit(tex, x + w - b, y + b + dy, tw - b, b, b, sh, tw, th, argb);
        }
    }

    // ---- buttons -----------------------------------------------------------

    public static void button(Canvas c, int x, int y, int w, int h, boolean hover, boolean active) {
        button(c, x, y, w, h, hover, active, 0xFFFFFFFF);
    }

    public static void button(Canvas c, int x, int y, int w, int h, boolean hover, boolean active, int tint) {
        String s = !active ? "button_disabled" : hover ? "button_highlighted" : "button";
        nineSlice(c, s, x, y, w, h, 200, 20, 3, tint);
    }

    /** A button with its label centred, cut to fit. */
    public static void button(Canvas c, String label, int x, int y, int w, int h, boolean hover, boolean active) {
        button(c, x, y, w, h, hover, active);
        buttonLabel(c, label, x, y, w, h, active, 0xFF);
    }

    public static void buttonLabel(Canvas c, String label, int x, int y, int w, int h, boolean active, int alpha) {
        String shown = Theme.ellipsize(c, label, w - 8);
        int color = (Math.max(5, alpha) << 24) | ((active ? TEXT : TEXT_OFF) & 0xFFFFFF);
        c.centeredText(shown, x + w / 2, y + (h - 8) / 2 + 1, color, true);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    // ---- slider --------------------------------------------------------------

    /** Track, 8px handle and the value centred on top. */
    public static void slider(Canvas c, int x, int y, int w, int h, double fraction, String text, boolean hover, boolean active) {
        nineSlice(c, hover && active ? "slider_highlighted" : "slider", x, y, w, h, 200, 20, 1, 0xFFFFFFFF);
        int hx = x + (int) Math.round(Math.max(0, Math.min(1, fraction)) * (w - 8));
        nineSlice(c, hover && active ? "slider_handle_highlighted" : "slider_handle", hx, y, 8, h, 8, 20, 2, 0xFFFFFFFF);
        c.centeredText(Theme.ellipsize(c, text, w - 6), x + w / 2, y + (h - 8) / 2 + 1, active ? TEXT : TEXT_OFF, true);
    }

    // ---- text box ------------------------------------------------------------

    public static void editBox(Canvas c, int x, int y, int w, int h, boolean focused) {
        nineSlice(c, focused ? "text_field_highlighted" : "text_field", x, y, w, h, 200, 20, 1, 0xFFFFFFFF);
    }

    // ---- Flex toggle -----------------------------------------------------------

    /** Flex-HUD's toggle: a grey square with a green tick or a red cross. */
    public static void toggleBox(Canvas c, int x, int y, int size, boolean on, boolean hover, boolean active) {
        c.fill(x, y, x + size, y + size, hover && active ? 0xFFD0D0D0 : 0xFF404040);
        c.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xFF777777);
        Icons glyph = on ? Icons.CHECK : Icons.CROSS;
        int scale = Math.max(1, (size - 6) / 7);
        int gx = x + (size - 7 * scale) / 2, gy = y + (size - 7 * scale) / 2;
        glyph.draw(c, gx + scale, gy + scale, 0x80000000, scale); // drop shadow
        glyph.draw(c, gx, gy, on ? 0xFF66FF00 : 0xFFEE1111, scale);
        if (!active) c.fill(x, y, x + size, y + size, 0x9F4E4E4E);
    }

    // ---- scroll bar --------------------------------------------------------------

    public static final int SCROLLBAR_W = 6;

    public static void scrollbar(Canvas c, int x, int top, int bottom, int barY, int barH) {
        nineSlice(c, "scroller_background", x, top, SCROLLBAR_W, bottom - top, 6, 32, 1, 0xFFFFFFFF);
        nineSlice(c, "scroller", x, barY, SCROLLBAR_W, barH, 6, 32, 1, 0xFFFFFFFF);
    }

    /** Vanilla's tooltip box. */
    public static void tooltip(Canvas c, String text, int mx, int my, int screenW, int screenH) {
        int w = c.textWidth(text) + 6, h = 14;
        int x = Math.min(mx + 10, screenW - w - 2), y = Math.max(2, Math.min(my - 14, screenH - h - 2));
        c.fill(x, y, x + w, y + h, 0xF0100010);
        c.outline(x, y, w, h, 0x505000FF);
        c.text(text, x + 3, y + 3, TEXT, true);
    }
}
