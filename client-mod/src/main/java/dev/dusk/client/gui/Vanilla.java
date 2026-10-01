package dev.dusk.client.gui;

/**
 * What is left of the vanilla look in the Dusk menus: its text colours, the
 * tooltip box and hit testing. The controls themselves are the launcher's
 * Px construction (see {@link Px}).
 */
public final class Vanilla {
    private Vanilla() {}

    public static final int TEXT = 0xFFFFFFFF, TEXT_OFF = 0xFFA0A0A0, TEXT_DIM = 0xFFAFAFAF;

    /** Width of a scroll list's bar (drawn by {@link Px#scrollbar}). */
    public static final int SCROLLBAR_W = 6;

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
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
