package dev.dusk.client.gui;

/**
 * The launcher's look for the Dusk title screen (launcher/src/design/tokens.css):
 * a black outline, a #323232 band and #4a4a4a L-corners at the top-right and
 * bottom-left, the gold DUSK wordmark. The in-game menus use {@link Vanilla}
 * instead, so they sit with the rest of the game's screens.
 */
public final class Theme {
    private Theme() {}

    public enum Kind { NORMAL, PRIMARY }

    public static final int TEXT = 0xFFF4F4F4, TEXT_MUTED = 0xFFB8B8B8, TEXT_FAINT = 0xFF828282;
    public static final int OVERLAY = 0x66000000;

    private static final int BLACK = 0xFF000000;
    private static final int BAND = 0xFF323232, BAND_HOT = 0xFF4A4A4A, CORNER = 0xFF4A4A4A, CORNER_HOT = 0xFF6A6A6A;
    private static final int GOLD_UP = 0xFFFFC600, GOLD_LO = 0xFFDE8105, GOLD_CORNER = 0xFFFFFBCD;
    private static final int SURF_TOP = 0xFF1E1E1E, SURF_BOT = 0xFF2B261C, CTA_BOT = 0xFF413018;
    /** Two-tone label pairs (Figma frames 7/8): idle and active. */
    public static final int LABEL_UP = 0xFFC6C6C6, LABEL_LO = 0xFF7B7B7B;
    public static final int ACTIVE_UP = 0xFFF2F2F2, ACTIVE_LO = 0xFFA6A6A6;
    /** The module grid's moss "enabled" pair and card surfaces. */
    public static final int MOSS_UP = 0xFF8CCF2D, MOSS_LO = 0xFF468A28, MOSS_BOT = 0xFF202C1D;
    public static final int RED_UP = 0xFFFF1100;
    public static final int SURFACE = SURF_TOP, SURFACE_BOT = SURF_BOT, ACCENT = GOLD_UP;
    private static final int FIELD_BG = 0xFF101010;

    // ---- frames ---------------------------------------------------------

    /** A clickable plate. */
    public static void button(Canvas c, int x, int y, int w, int h, boolean hover, Kind kind) {
        if (kind == Kind.NORMAL) {
            // Figma frame 7: flat #1e1e1e body inside the #323232 band
            plate(c, x, y, w, h, hover ? 0xFF252525 : SURF_TOP, hover ? 0xFF252525 : SURF_TOP, hover);
            return;
        }
        c.fill(x, y, x + w, y + h, BLACK);
        int mid = y + h / 2;
        c.fill(x + 1, y + 1, x + w - 1, mid, GOLD_UP);
        c.fill(x + 1, mid, x + w - 1, y + h - 1, GOLD_LO);
        // surface: flat through 45%, then blended down (tokens.css --surf-hold)
        int hold = y + 2 + (h - 4) * 45 / 100;
        c.fill(x + 2, y + 2, x + w - 2, hold, hover ? 0xFF242424 : SURF_TOP);
        c.fillGradient(x + 2, hold, x + w - 2, y + h - 2, hover ? 0xFF242424 : SURF_TOP, CTA_BOT);
        corners(c, x, y, w, h, GOLD_CORNER);
    }

    /**
     * The Figma construction with any surface: black outline, #323232 band,
     * a body held flat through 45% then blended from {@code top} to {@code bot},
     * #4a4a4a L-corners.
     */
    public static void plate(Canvas c, int x, int y, int w, int h, int top, int bot, boolean hot) {
        c.fill(x, y, x + w, y + h, BLACK);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, hot ? BAND_HOT : BAND);
        if (top == bot) {
            c.fill(x + 2, y + 2, x + w - 2, y + h - 2, top);
        } else {
            int hold = y + 2 + (h - 4) * 45 / 100;
            c.fill(x + 2, y + 2, x + w - 2, hold, top);
            c.fillGradient(x + 2, hold, x + w - 2, y + h - 2, top, bot);
        }
        corners(c, x, y, w, h, hot ? CORNER_HOT : CORNER);
    }

    /**
     * Pixel text with the launcher's hard two-tone split: {@code up} over the
     * top half of the cap, {@code lo} below. The split is clipped in screen
     * space, so draw it outside any transform.
     */
    public static void label(Canvas c, String text, int x, int y, int up, int lo, float scale) {
        int split = y + Math.round(3.5f * scale);
        int r = x + labelWidth(c, text, scale) + 2, b = y + Math.round(c.lineHeight() * scale) + 2;
        c.scissor(x - 1, y - 1, r, split);
        scaledText(c, text, x, y, up, scale);
        c.unscissor();
        c.scissor(x - 1, split, r, b);
        scaledText(c, text, x, y, lo, scale);
        c.unscissor();
    }

    public static int labelWidth(Canvas c, String text, float scale) {
        return Math.round(c.textWidth(text) * scale);
    }

    /** Flat text at a (possibly fractional) scale. */
    public static void scaledText(Canvas c, String text, int x, int y, int color, float scale) {
        scaledText(c, text, x, y, color, scale, false);
    }

    public static void scaledText(Canvas c, String text, int x, int y, int color, float scale, boolean shadow) {
        if (scale == 1f) {
            c.text(text, x, y, color, shadow);
            return;
        }
        c.push();
        c.translate(x, y);
        c.scale(scale, scale);
        c.text(text, 0, 0, color, shadow);
        c.pop();
    }

    /** A text-entry box on a plate: black outline, band (gold while focused), dark well. */
    public static void field(Canvas c, int x, int y, int w, int h, boolean focused) {
        c.fill(x, y, x + w, y + h, BLACK);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, focused ? GOLD_LO : BAND);
        c.fill(x + 2, y + 2, x + w - 2, y + h - 2, FIELD_BG);
    }

    // ---- the launcher's families (launcher/src/design/px.css) --------------------

    /** A PxBox family: band top/bottom, corner, surface bottom and how long the surface holds flat. */
    public enum Family {
        PANEL(BAND, BAND, 0, SURF_TOP, 100),
        GREY(BAND, BAND, CORNER, SURF_BOT, 13),
        ACCENT(GOLD_UP, GOLD_LO, GOLD_CORNER, CTA_BOT, 0),
        GREEN(0xFF00FF40, 0xFF06DD0E, 0xFFCDFFE1, 0xFF123D1C, 45),
        INSTALL(BAND, BAND, CORNER, 0xFF16241A, 45),
        SOFT(BAND, BAND, CORNER, CTA_BOT, 45);

        final int up, lo, corner, bot, hold;

        Family(int up, int lo, int corner, int bot, int hold) {
            this.up = up;
            this.lo = lo;
            this.corner = corner;
            this.bot = bot;
            this.hold = hold;
        }
    }

    /** Label pairs the launcher's TT tones use. */
    public static final int DIM = 0xFFCFCFCF, GREEN_UP = 0xFF00FF40, GREEN_LO = 0xFF06DD0E, GOLD_LO_TEXT = GOLD_LO;

    /** The launcher's hover (brightness 1.18) and disabled (grayscale .7, brightness .72) filters. */
    public static int filter(int argb, boolean hot, boolean disabled) {
        if (!hot && !disabled) return argb;
        int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        float k = hot ? 1.18f : 0.72f;
        if (disabled) {
            float grey = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = Math.round(r + (grey - r) * 0.7f);
            g = Math.round(g + (grey - g) * 0.7f);
            b = Math.round(b + (grey - b) * 0.7f);
        }
        r = Math.min(255, Math.round(r * k));
        g = Math.min(255, Math.round(g * k));
        b = Math.min(255, Math.round(b * k));
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** A launcher PxBox: black outline, the family's band (split at mid-height), surface, L-corners. */
    public static void box(Canvas c, int x, int y, int w, int h, Family f, boolean hot, boolean disabled) {
        c.fill(x, y, x + w, y + h, BLACK);
        band(c, x + 1, y + 1, w - 2, h - 2, f, hot, disabled);
    }

    /** A navbar cell: the grey band and corners on a flat surface, no black of its own (the bar supplies it). */
    public static void cell(Canvas c, int x, int y, int w, int h, boolean hot) {
        int band = filter(BAND, hot, false);
        c.fill(x, y, x + w, y + h, band);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, filter(SURF_TOP, hot, false));
        corners(c, x - 1, y - 1, w + 2, h + 2, filter(CORNER, hot, false));
    }

    /** The window-close cell: red band and corners on a red surface. */
    public static void closeCell(Canvas c, int x, int y, int w, int h, boolean hot) {
        int mid = y + h / 2;
        c.fill(x, y, x + w, mid, RED_UP);
        c.fill(x, mid, x + w, y + h, 0xFFDD0626);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, hot ? RED_UP : 0xFFDD0626);
        corners(c, x - 1, y - 1, w + 2, h + 2, RED_CORNER);
    }

    public static final int RED_CORNER = 0xFFFF8B8E, GLYPH = 0xFFB8B8B8;

    /** The bar's filler: a plain panel with bands top and bottom only. */
    public static void barFill(Canvas c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, SURF_TOP);
        c.fill(x, y, x + w, y + 1, BAND);
        c.fill(x, y + h - 1, x + w, y + h, BAND);
    }

    private static void band(Canvas c, int x, int y, int w, int h, Family f, boolean hot, boolean disabled) {
        int up = filter(f.up, hot, disabled), lo = filter(f.lo, hot, disabled);
        int mid = y + h / 2;
        c.fill(x, y, x + w, mid, up);
        c.fill(x, mid, x + w, y + h, lo);
        int top = filter(SURF_TOP, hot, disabled), bot = filter(f.bot, hot, disabled);
        int sx = x + 1, sy = y + 1, sw = w - 2, sh = h - 2;
        int hold = sy + sh * f.hold / 100;
        if (hold > sy) c.fill(sx, sy, sx + sw, hold, top);
        if (hold < sy + sh) c.fillGradient(sx, hold, sx + sw, sy + sh, top, bot);
        if (f.corner != 0) corners(c, x - 1, y - 1, w + 2, h + 2, filter(f.corner, hot, disabled));
    }

    public static void vDivider(Canvas c, int x, int y0, int y1) {
        c.fill(x, y0, x + 1, y1, BLACK);
    }

    /** The launcher's L-corners: top-right and bottom-left only, on the band. */
    private static void corners(Canvas c, int x, int y, int w, int h, int color) {
        int leg = Math.max(2, Math.min(4, Math.min(w, h) / 5));
        int r = x + w - 2, b = y + h - 2;
        c.fill(r - leg + 1, y + 1, r + 1, y + 2, color);
        c.fill(r, y + 1, r + 1, y + 1 + leg, color);
        c.fill(x + 1, b, x + 1 + leg, b + 1, color);
        c.fill(x + 1, b - leg + 1, x + 2, b + 1, color);
    }

    /** Cuts {@code text} to fit {@code max} pixels, ending in "...". */
    public static String ellipsize(Canvas c, String text, int max) {
        if (c.textWidth(text) <= max) return text;
        String cut = text;
        while (!cut.isEmpty() && c.textWidth(cut + "...") > max) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    // ---- wordmark -------------------------------------------------------

    /**
     * DUSK in the game font's own glyphs (5x7, what Monocraft is drawn
     * after), bolded the way the font does it: each glyph again one pixel right.
     */
    private static final String[][] LETTERS = {
            {"1111.", "1...1", "1...1", "1...1", "1...1", "1...1", "1111."},
            {"1...1", "1...1", "1...1", "1...1", "1...1", "1...1", ".111."},
            {".1111", "1....", "1....", ".111.", "....1", "....1", "1111."},
            {"1...1", "1..1.", "111..", "1..1.", "1...1", "1...1", "1...1"},
    };
    private static final int GLYPH_W = 5, GLYPH_H = 7, BOLD_W = GLYPH_W + 1, LETTER_GAP = 2;
    private static final int LETTERS_W = LETTERS.length * (BOLD_W + LETTER_GAP) - LETTER_GAP;
    /** Rows above this are the light gold, the rest the deep one: a hard split like the buttons'. */
    private static final int SPLIT = 4;
    private static final int OUTLINE = 0x12061E, DROP = 0x5A1A4A;
    private static final boolean[][] LETTER_MASK = mask(LETTERS_W, GLYPH_H, (r, col) -> {
        int l = col / (BOLD_W + LETTER_GAP), lc = col % (BOLD_W + LETTER_GAP);
        if (lc >= BOLD_W) return false;
        String row = LETTERS[l][r];
        return lc < GLYPH_W && row.charAt(lc) == '1' || lc > 0 && row.charAt(lc - 1) == '1';
    });
    /** The letters and their drop one cell below, grown by a cell all round. */
    private static final boolean[][] OUTLINE_MASK = mask(LETTERS_W + 2, GLYPH_H + 3, (r, col) -> {
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int rr = r - 1 + dr, cc = col - 1 + dc;
                if (cc < 0 || cc >= LETTERS_W) continue;
                if (rr >= 0 && rr < GLYPH_H && LETTER_MASK[rr][cc] || rr >= 1 && rr <= GLYPH_H && LETTER_MASK[rr - 1][cc]) {
                    return true;
                }
            }
        }
        return false;
    });

    private interface Cell { boolean at(int r, int col); }

    private static boolean[][] mask(int w, int h, Cell cell) {
        boolean[][] m = new boolean[h][w];
        for (int r = 0; r < h; r++) {
            for (int col = 0; col < w; col++) m[r][col] = cell.at(r, col);
        }
        return m;
    }

    /** Width of {@link #wordmark} at {@code unit} pixels per font pixel. */
    public static int wordmarkWidth(int unit) {
        return (LETTERS_W + 2) * unit;
    }

    /** Height of {@link #wordmark}: the outlined letters and their drop. */
    public static int wordmarkHeight(int unit) {
        return (GLYPH_H + 3) * unit;
    }

    public static void wordmark(Canvas c, int x, int y, int unit) {
        wordmark(c, x, y, unit, 1f);
    }

    /**
     * The title: bold DUSK, gold over deep gold split hard across the
     * middle, a plum drop and a dark outline so it holds on any backdrop,
     * faded by {@code alpha}.
     */
    public static void wordmark(Canvas c, int x, int y, int unit, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        cells(c, OUTLINE_MASK, x, y, unit, a, r -> OUTLINE);
        cells(c, LETTER_MASK, x + unit, y + 2 * unit, unit, a, r -> DROP);
        cells(c, LETTER_MASK, x + unit, y + unit, unit, a, r -> r < SPLIT ? GOLD_UP : GOLD_LO);
    }

    private interface RowColor { int at(int r); }

    /** Fills a cell mask in horizontal runs, each row one flat colour. */
    private static void cells(Canvas c, boolean[][] m, int x, int y, int unit, float a, RowColor color) {
        for (int r = 0; r < m.length; r++) {
            int argb = Math.max(5, Math.round(255 * a)) << 24 | color.at(r) & 0xFFFFFF;
            for (int col = 0; col < m[r].length; col++) {
                if (!m[r][col]) continue;
                int end = col;
                while (end + 1 < m[r].length && m[r][end + 1]) end++;
                c.fill(x + col * unit, y + r * unit, x + (end + 1) * unit, y + (r + 1) * unit, argb);
                col = end;
            }
        }
    }
}
