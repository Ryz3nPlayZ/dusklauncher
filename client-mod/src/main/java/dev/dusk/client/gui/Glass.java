package dev.dusk.client.gui;

/**
 * The modern look's menu controls for the title screen and the pause menu,
 * in the launcher's own language: full pills on a near-solid dark surface
 * with a hairline edge, one white primary pill (the launcher's Play), and
 * sentence-case sans labels. Round cells of the same surface hold the icon
 * shortcuts. Drawn in window pixels so the edges and the type stay crisp.
 */
public final class Glass {
    private Glass() {}

    /** The launcher's raised control surface (rgb 20 20 23), nearly opaque so the scene never muddies a label. */
    private static final int FILL = 0xEB141417, FILL_HOT = 0xF5242429, FILL_OFF = 0xB3141417;
    private static final int PRIMARY = Theme.ACCENT, PRIMARY_HOT = 0xFFFFFFFF;

    /** A pill: the dark surface, or white when {@code primary}. */
    public static void bar(Canvas c, int x, int y, int w, int h, boolean hot, boolean disabled, boolean primary) {
        if (w <= 0 || h <= 0) return;
        Round.begin(c);
        int x0 = Round.w(x), y0 = Round.w(y), x1 = Round.w(x + w), y1 = Round.w(y + h);
        int r = (y1 - y0 + 1) / 2;
        boolean lit = hot && !disabled;
        if (primary && !disabled) {
            // the launcher's --glow-acc: a soft white haze under the pill
            Round.softW(c, x0, y0, x1, y1, r, Px.px(10), Px.px(3), lit ? 0x40FFFFFF : 0x24FFFFFF);
            Round.fillW(c, x0, y0, x1, y1, r, lit ? PRIMARY_HOT : PRIMARY);
        } else {
            Round.fillW(c, x0, y0, x1, y1, r, disabled ? FILL_OFF : lit ? FILL_HOT : FILL);
            Round.ringW(c, x0, y0, x1, y1, r, Px.line(), lit ? Theme.LINE_3 : Theme.LINE_2);
        }
        Round.end(c);
    }

    /** A round cell for an icon shortcut, or a pill when wider than tall. */
    public static void cell(Canvas c, int x, int y, int w, int h, boolean hot) {
        Round.begin(c);
        int x0 = Round.w(x), y0 = Round.w(y), x1 = Round.w(x + w), y1 = Round.w(y + h);
        int r = (y1 - y0 + 1) / 2;
        Round.fillW(c, x0, y0, x1, y1, r, hot ? FILL_HOT : FILL);
        Round.ringW(c, x0, y0, x1, y1, r, Px.line(), hot ? Theme.LINE_3 : Theme.LINE_2);
        Round.end(c);
    }

    /** The glyph colour on a {@link #cell}. */
    public static int glyph(boolean hot) {
        return hot ? Theme.TEXT : Theme.TEXT_MUTED;
    }

    /** The label colour on a {@link #bar}. */
    public static int text(boolean hot, boolean disabled, boolean primary) {
        if (disabled) return 0x66F4F4F5;
        if (primary) return Theme.INK;
        return hot ? 0xFFFFFFFF : Theme.TEXT;
    }

    /** {@code text} in sentence case, semibold sans, centred in a {@link #bar}; it steps down and then cuts short when narrow. */
    public static void label(Canvas c, String text, int x, int y, int w, int h, int argb) {
        Px.sans(c, Theme.sentence(text), x, y, w, h, h >= 22 ? Px.Size.S20 : Px.Size.S16, argb, true, false);
    }
}
