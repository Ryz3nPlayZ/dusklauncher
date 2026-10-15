package dev.dusk.client.gui;

/**
 * What is left of the vanilla look in the Dusk menus: its text colours, the
 * tooltip box and hit testing. The controls themselves are the launcher's
 * Px construction (see {@link Px}).
 */
public final class Vanilla {
    private Vanilla() {}

    public static final int TEXT = 0xFFFFFFFF, TEXT_OFF = 0xFFA0A0A0, TEXT_DIM = 0xFFAFAFAF;

    /** A tooltip's box: vanilla's purple-edged one, or the modern look's rounded dark pop. */
    public static void popBox(Canvas c, int x, int y, int w, int h) {
        if (!Theme.modern()) {
            c.fill(x, y, x + w, y + h, 0xF0100010);
            c.outline(x, y, w, h, 0x505000FF);
            return;
        }
        Round.begin(c);
        int x0 = Round.w(x), y0 = Round.w(y), x1 = Round.w(x + w), y1 = Round.w(y + h), r = Round.w(6);
        Round.softW(c, x0, y0, x1, y1, r, Round.w(6), Round.w(2), 0x66000000);
        Round.fillW(c, x0, y0, x1, y1, r, 0xF71A1C3A);
        Round.ringW(c, x0, y0, x1, y1, r, Px.line(), Theme.LINE_2);
        Round.end(c);
    }

    /** Width of a scroll list's bar (drawn by {@link Px#scrollbar}). */
    public static final int SCROLLBAR_W = 6;

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    /** Vanilla's tooltip box, or a rounded dark pop in the modern look. */
    public static void tooltip(Canvas c, String text, int mx, int my, int screenW, int screenH) {
        if (Theme.modern()) {
            int w = c.textWidth(text) + 12, h = 17;
            int x = Math.max(2, Math.min(mx + 10, screenW - w - 2)), y = Math.max(2, Math.min(my - 16, screenH - h - 2));
            popBox(c, x, y, w, h);
            c.text(text, x + 6, y + 5, Theme.TEXT, false);
            return;
        }
        int w = c.textWidth(text) + 6, h = 14;
        int x = Math.min(mx + 10, screenW - w - 2), y = Math.max(2, Math.min(my - 14, screenH - h - 2));
        c.fill(x, y, x + w, y + h, 0xF0100010);
        c.outline(x, y, w, h, 0x505000FF);
        c.text(text, x + 3, y + 3, TEXT, true);
    }
}
