package dev.dusk.client.gui;

/**
 * Rounded rectangles for the modern look, drawn in window pixels: flat
 * fills for the straight runs and anti-aliased quarter discs (or rings) from
 * {@code textures/gui/round.png} for the corners, tinted to the colour.
 *
 * <p>The atlas holds every radius from 1 to 64 at 1:1. Radius {@code r}
 * sits at x {@code r(r-1)/2}, {@code r} square; rows come in 64-pixel blocks,
 * block {@code stroke * 4 + corner} where stroke 0 is the filled disc, 1 and
 * 2 rings that wide, and corner 0-3 is top-left, top-right, bottom-left,
 * bottom-right.
 *
 * <p>The window-pixel calls ({@code *W}) expect the caller to have scaled to
 * window pixels already (see {@link #begin}); the others take GUI pixels.
 */
public final class Round {
    private Round() {}

    private static final String TEX = "duskclient:textures/gui/round.png";
    private static final int TEX_W = 2080, TEX_H = 768, MAX_R = 64, BLOCK = 64;

    /** Scales the canvas to window pixels; pair with {@link #end}. */
    public static void begin(Canvas c) {
        float k = (float) (1 / Px.gui());
        c.push();
        c.scale(k, k);
    }

    public static void end(Canvas c) {
        c.pop();
    }

    /** GUI pixels to whole window pixels. */
    public static int w(double gui) {
        return (int) Math.round(gui * Px.gui());
    }

    private static int clamp(int r, int w, int h) {
        return Math.max(0, Math.min(MAX_R, Math.min(r, Math.min(w, h) / 2)));
    }

    private static void corner(Canvas c, int x, int y, int r, int stroke, int corner, int argb) {
        c.blit(TEX, x, y, r * (r - 1) / 2f, (stroke * 4 + corner) * BLOCK, r, r, TEX_W, TEX_H, argb);
    }

    // ---- window pixels ------------------------------------------------------------

    /** A filled rounded rectangle, {@code r} window pixels to the corner. */
    public static void fillW(Canvas c, int x0, int y0, int x1, int y1, int r, int argb) {
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0 || argb >>> 24 == 0) return;
        r = clamp(r, w, h);
        if (r == 0) {
            c.fill(x0, y0, x1, y1, argb);
            return;
        }
        c.fill(x0 + r, y0, x1 - r, y1, argb);
        if (h > 2 * r) {
            c.fill(x0, y0 + r, x0 + r, y1 - r, argb);
            c.fill(x1 - r, y0 + r, x1, y1 - r, argb);
        }
        corner(c, x0, y0, r, 0, 0, argb);
        corner(c, x1 - r, y0, r, 0, 1, argb);
        corner(c, x0, y1 - r, r, 0, 2, argb);
        corner(c, x1 - r, y1 - r, r, 0, 3, argb);
    }

    /** A rounded outline {@code stroke} (1 or 2) window pixels wide, just inside the box. */
    public static void ringW(Canvas c, int x0, int y0, int x1, int y1, int r, int stroke, int argb) {
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0 || argb >>> 24 == 0) return;
        stroke = Math.max(1, Math.min(2, stroke));
        r = clamp(r, w, h);
        if (r <= stroke) {
            c.fill(x0, y0, x1, y0 + stroke, argb);
            c.fill(x0, y1 - stroke, x1, y1, argb);
            c.fill(x0, y0 + stroke, x0 + stroke, y1 - stroke, argb);
            c.fill(x1 - stroke, y0 + stroke, x1, y1 - stroke, argb);
            return;
        }
        c.fill(x0 + r, y0, x1 - r, y0 + stroke, argb);
        c.fill(x0 + r, y1 - stroke, x1 - r, y1, argb);
        if (h > 2 * r) {
            c.fill(x0, y0 + r, x0 + stroke, y1 - r, argb);
            c.fill(x1 - stroke, y0 + r, x1, y1 - r, argb);
        }
        corner(c, x0, y0, r, stroke, 0, argb);
        corner(c, x1 - r, y0, r, stroke, 1, argb);
        corner(c, x0, y1 - r, r, stroke, 2, argb);
        corner(c, x1 - r, y1 - r, r, stroke, 3, argb);
    }

    /**
     * A soft shadow or glow: {@code steps} widening rounded rectangles, each
     * {@code spread / steps} further out and fainter, shifted down by
     * {@code dy}. The layers pile up toward the middle, so {@code argb}'s
     * alpha is the total at the edge of the box.
     */
    public static void softW(Canvas c, int x0, int y0, int x1, int y1, int r, int spread, int dy, int argb) {
        int a = argb >>> 24;
        if (a == 0 || spread <= 0) return;
        int steps = Math.max(2, Math.min(8, spread / 2));
        int each = Math.max(1, a / steps);
        for (int i = steps; i >= 1; i--) {
            int grow = spread * i / steps;
            fillW(c, x0 - grow, y0 - grow + dy, x1 + grow, y1 + grow + dy, r + grow, each << 24 | argb & 0xFFFFFF);
        }
    }

    // ---- GUI pixels ---------------------------------------------------------------

    /** {@link #fillW} for a box in GUI pixels, {@code r} window pixels to the corner. */
    public static void fill(Canvas c, int x, int y, int w, int h, int r, int argb) {
        begin(c);
        fillW(c, w(x), w(y), w(x + w), w(y + h), r, argb);
        end(c);
    }

    /** A filled box with a ring on top. */
    public static void fill(Canvas c, int x, int y, int w, int h, int r, int argb, int ring) {
        begin(c);
        int x0 = w(x), y0 = w(y), x1 = w(x + w), y1 = w(y + h);
        fillW(c, x0, y0, x1, y1, r, argb);
        ringW(c, x0, y0, x1, y1, r, Px.line(), ring);
        end(c);
    }

    public static void ring(Canvas c, int x, int y, int w, int h, int r, int argb) {
        begin(c);
        ringW(c, w(x), w(y), w(x + w), w(y + h), r, Px.line(), argb);
        end(c);
    }

    /** A pill: fully rounded ends. */
    public static void pill(Canvas c, int x, int y, int w, int h, int argb) {
        fill(c, x, y, w, h, MAX_R, argb);
    }

    /** A filled circle of {@code d} GUI pixels across. */
    public static void dot(Canvas c, int x, int y, int d, int argb) {
        fill(c, x, y, d, d, MAX_R, argb);
    }
}
