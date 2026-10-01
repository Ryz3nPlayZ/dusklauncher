package dev.dusk.client.gui;

/**
 * The launcher's look for the Dusk screens (launcher/src/design/tokens.css):
 * a black outline, a #323232 band and #4a4a4a L-corners at the top-right and
 * bottom-left, the launcher's brand as the wordmark. The frames are drawn by
 * {@link Px} in window pixels, at the launcher's proportions.
 */
public final class Theme {
    private Theme() {}

    public enum Kind { NORMAL, PRIMARY }

    public static final int TEXT = 0xFFF4F4F4, TEXT_MUTED = 0xFFB8B8B8, TEXT_FAINT = 0xFF828282;
    public static final int OVERLAY = 0x66000000;

    private static final int BLACK = 0xFF000000;
    private static final int BAND = 0xFF323232, CORNER = 0xFF4A4A4A;
    private static final int GOLD_UP = 0xFFFFC600, GOLD_LO = 0xFFDE8105, GOLD_CORNER = 0xFFFFFBCD;
    private static final int SURF_TOP = 0xFF1E1E1E, SURF_BOT = 0xFF2B261C, CTA_BOT = 0xFF413018;
    /** Two-tone label pairs (Figma frames 7/8): idle and active. */
    public static final int LABEL_UP = 0xFFC6C6C6, LABEL_LO = 0xFF7B7B7B;
    public static final int ACTIVE_UP = 0xFFF2F2F2, ACTIVE_LO = 0xFFA6A6A6;
    /** The module grid's moss "enabled" pair and card surfaces. */
    public static final int MOSS_UP = 0xFF8CCF2D, MOSS_LO = 0xFF468A28, MOSS_BOT = 0xFF202C1D;
    public static final int RED_UP = 0xFFFF1100;
    public static final int SURFACE = SURF_TOP, SURFACE_BOT = SURF_BOT, ACCENT = GOLD_UP;

    // ---- frames ---------------------------------------------------------

    /** A clickable plate. */
    public static void button(Canvas c, int x, int y, int w, int h, boolean hover, Kind kind) {
        if (kind == Kind.NORMAL) {
            // Figma frame 7: flat #1e1e1e body inside the #323232 band
            plate(c, x, y, w, h, SURF_TOP, SURF_TOP, hover);
            return;
        }
        Px.box(c, x, y, w, h, Family.ACCENT, hover, false, 0xFF);
    }

    /**
     * The Figma construction with any surface: black outline, #323232 band,
     * a body held flat through 45% then blended from {@code top} to {@code bot},
     * #4a4a4a L-corners.
     */
    public static void plate(Canvas c, int x, int y, int w, int h, int top, int bot, boolean hot) {
        Px.frame(c, x, y, w, h, true, BAND, BAND, CORNER, top, bot, 45, hot, false, 0xFF);
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
        Px.field(c, x, y, w, h, focused);
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
        Px.box(c, x, y, w, h, f, hot, disabled, 0xFF);
    }

    /** A navbar cell: the grey band and corners on a flat surface, no black of its own (the bar supplies it). */
    public static void cell(Canvas c, int x, int y, int w, int h, boolean hot) {
        Px.frame(c, x, y, w, h, false, BAND, BAND, CORNER, SURF_TOP, SURF_TOP, 100, hot, false, 0xFF);
    }

    /** The window-close cell: red band and corners on a red surface. */
    public static void closeCell(Canvas c, int x, int y, int w, int h, boolean hot) {
        int face = hot ? RED_UP : 0xFFDD0626;
        Px.frame(c, x, y, w, h, false, RED_UP, 0xFFDD0626, RED_CORNER, face, face, 100, false, false, 0xFF);
    }

    public static final int RED_CORNER = 0xFFFF8B8E, GLYPH = 0xFFB8B8B8;

    /** The bar's filler: a plain panel with bands top and bottom only. */
    public static void barFill(Canvas c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, SURF_TOP);
        c.fill(x, y, x + w, y + 1, BAND);
        c.fill(x, y + h - 1, x + w, y + h, BAND);
    }


    public static void vDivider(Canvas c, int x, int y0, int y1) {
        c.fill(x, y0, x + 1, y1, BLACK);
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
     * The brand: the dusk app icon, then DUSK in heavy two-cell-stem pixel
     * letters in the accent's gold (the sun in the icon), cut hard into its
     * two tones like every launcher label and dropped a cell onto a dark
     * shadow. Drawn in window pixels so the icon's art pixels and the letter
     * cells all land on whole pixels at any GUI scale.
     */
    private static final String[][] LETTERS = {
            {"11111.", "11..11", "11..11", "11..11", "11..11", "11..11", "11111."},
            {"11..11", "11..11", "11..11", "11..11", "11..11", "11..11", ".1111."},
            {".11111", "11....", "11....", ".1111.", "....11", "....11", "11111."},
            {"11..11", "11.11.", "1111..", "111...", "1111..", "11.11.", "11..11"},
    };
    private static final String MARK = "duskclient:textures/gui/dusk_mark.png";
    /** The icon's pixel art is 26x26; it is drawn {@code art} window pixels to the art pixel. */
    private static final int MARK_ART = 26;
    /** The icon in launcher-wordmark cells, as before, so callers keep their layout. */
    private static final int MARK_CELLS = 11;
    private static final int GLYPH_W = 6, GLYPH_H = 7, LETTER_GAP = 1, MARK_GAP = 3;
    private static final int LETTERS_W = LETTERS.length * (GLYPH_W + LETTER_GAP) - LETTER_GAP;
    /** Cap height over icon height. */
    private static final float CAP = 0.6f;
    private static final int TEXT_TOP = 0xFFC600, TEXT_BOT = 0xDE8105, SPLIT = 3, SHADOW = 0x3A1E00;
    private static final boolean[][] LETTER_MASK = mask(LETTERS_W, GLYPH_H, (r, col) -> {
        int l = col / (GLYPH_W + LETTER_GAP), lc = col % (GLYPH_W + LETTER_GAP);
        return lc < GLYPH_W && LETTERS[l][r].charAt(lc) == '1';
    });

    private interface Cell { boolean at(int r, int col); }

    private static boolean[][] mask(int w, int h, Cell cell) {
        boolean[][] m = new boolean[h][w];
        for (int r = 0; r < h; r++) {
            for (int col = 0; col < w; col++) m[r][col] = cell.at(r, col);
        }
        return m;
    }

    /** Window pixels per icon art pixel, for a wordmark {@code unit} GUI pixels to the cell. */
    private static int art(int unit) {
        return Math.max(1, Math.round(MARK_CELLS * unit * (float) Px.gui() / MARK_ART));
    }

    /** Window pixels per letter cell. */
    private static int letterCell(int unit) {
        return Math.max(1, Math.round(MARK_ART * art(unit) * CAP / GLYPH_H));
    }

    /** Width of {@link #wordmark} in GUI pixels at {@code unit}. */
    public static int wordmarkWidth(int unit) {
        int cell = letterCell(unit);
        return (int) Math.ceil((MARK_ART * art(unit) + (MARK_GAP + LETTERS_W + 1) * cell) / Px.gui());
    }

    /** Height of {@link #wordmark} in GUI pixels: the icon, which the letters are centred on. */
    public static int wordmarkHeight(int unit) {
        return (int) Math.ceil(MARK_ART * art(unit) / Px.gui());
    }

    public static void wordmark(Canvas c, int x, int y, int unit) {
        wordmark(c, x, y, unit, 1f);
    }

    /** The brand, faded by {@code alpha}. */
    public static void wordmark(Canvas c, int x, int y, int unit, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        int alpha255 = Math.max(5, Math.round(255 * a));
        double s = Px.gui();
        int art = art(unit), cell = letterCell(unit), size = MARK_ART * art;
        int wx = (int) Math.round(x * s), wy = (int) Math.round(y * s);
        c.push();
        c.scale((float) (1 / s), (float) (1 / s));
        c.push();
        c.translate(wx, wy);
        c.scale(art, art);
        c.blit(MARK, 0, 0, 0, 0, MARK_ART, MARK_ART, MARK_ART, MARK_ART, alpha255 << 24 | 0xFFFFFF);
        c.pop();

        int tx = wx + size + MARK_GAP * cell, ty = wy + (size - GLYPH_H * cell) / 2;
        cells(c, LETTER_MASK, tx + cell, ty + cell, cell, cell, alpha255, r -> SHADOW);
        cells(c, LETTER_MASK, tx, ty, cell, cell, alpha255, r -> r < SPLIT ? TEXT_TOP : TEXT_BOT);
        c.pop();
    }

    // ---- the menu brand -------------------------------------------------

    /**
     * The launcher's nav brand cell (Nav.tsx), for the menu: the app icon and
     * DUSK in grey TT stretched 1.4 wide on a grey PxBox {@code h} GUI pixels
     * tall. Proportions follow nav.css (icon 76 of 88, padding 20 / gap 14 /
     * 28, cap about 0.3 of the icon); the icon and the type are sized in whole
     * window pixels so the pixel art stays crisp.
     */
    private record Brand(int k, int icon, int fy, int fx, int padL, int gap, int padR) {
        static Brand at(int h) {
            double hh = h * Px.gui();
            int k = Math.max(1, (int) Math.floor(hh * 76 / 88 / MARK_ART));
            int icon = MARK_ART * k;
            int fy = Math.max(1, (int) Math.round(icon * 0.3 / 7));
            int fx = Math.max(1, (int) Math.round(fy * 1.4));
            int padL = (int) Math.round((hh - icon) / 2 + hh * 14 / 88);
            return new Brand(k, icon, fy, fx, padL, (int) Math.round(hh * 14 / 88), (int) Math.round(hh * 28 / 88));
        }
    }

    /** Width of {@link #brand} in GUI pixels. */
    public static int brandWidth(Canvas c, int h) {
        Brand b = Brand.at(h);
        return (int) Math.ceil((b.padL + b.icon + b.gap + Px.ttWidth(c, "DUSK", b.fx) + b.padR) / Px.gui());
    }

    public static void brand(Canvas c, int x, int y, int h, int alpha) {
        Brand b = Brand.at(h);
        double s = Px.gui();
        Px.box(c, x, y, brandWidth(c, h), h, Family.GREY, false, false, alpha);
        int wx = (int) Math.round(x * s), wy = (int) Math.round(y * s), wh = (int) Math.round(h * s);
        int iy = wy + (wh - b.icon) / 2;
        c.push();
        c.scale((float) (1 / s), (float) (1 / s));
        c.translate(wx + b.padL, iy);
        c.scale(b.k, b.k);
        c.blit(MARK, 0, 0, 0, 0, MARK_ART, MARK_ART, MARK_ART, MARK_ART, Math.max(5, alpha) << 24 | 0xFFFFFF);
        c.pop();
        Px.tt(c, "DUSK", wx + b.padL + b.icon + b.gap, wy + wh / 2, b.fx, b.fy, Px.Tone.GREY, alpha);
    }

    private interface RowColor { int at(int r); }

    /** Fills a cell mask in horizontal runs, each row one flat colour. */
    private static void cells(Canvas c, boolean[][] m, int x, int y, int cw, int ch, int alpha255, RowColor color) {
        for (int r = 0; r < m.length; r++) {
            int argb = alpha255 << 24 | color.at(r) & 0xFFFFFF;
            for (int col = 0; col < m[r].length; col++) {
                if (!m[r][col]) continue;
                int end = col;
                while (end + 1 < m[r].length && m[r][end + 1]) end++;
                c.fill(x + col * cw, y + r * ch, x + (end + 1) * cw, y + (r + 1) * ch, argb);
                col = end;
            }
        }
    }
}
