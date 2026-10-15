package dev.dusk.client.gui;

import dev.dusk.client.config.DuskConfig;

/**
 * The launcher's look for the Dusk screens (launcher/src/design/tokens.css):
 * a black outline, a #323232 band and #4a4a4a L-corners at the top-right and
 * bottom-left, the launcher's brand as the wordmark. The frames are drawn by
 * {@link Px} in window pixels, at the launcher's proportions.
 */
public final class Theme {
    private Theme() {}

    public enum Kind { NORMAL, PRIMARY }

    private static final int BLACK = 0xFF000000;
    private static final int BAND = 0xFF323232, CORNER = 0xFF4A4A4A;
    private static final int GOLD_UP = 0xFFFFC600, GOLD_LO = 0xFFDE8105, GOLD_CORNER = 0xFFFFFBCD;
    private static final int SURF_TOP = 0xFF1E1E1E, SURF_BOT = 0xFF2B261C, CTA_BOT = 0xFF413018;

    // ---- the two looks ------------------------------------------------------------

    /**
     * The modern look (launcher/src/design/tokens.css): graphite surfaces,
     * white as the accent, pills and rounded cards, Plus Jakarta Sans. The
     * legacy look is the launcher's pixel frames, kept behind
     * {@code uiStyle: "legacy"} in duskclient.json.
     */
    private static boolean modern = readModern();
    private static int sansDepth;

    private static boolean readModern() {
        return !"legacy".equals(DuskConfig.get().uiStyle);
    }

    public static boolean modern() {
        return modern;
    }

    /** {@code modern} in the modern look, {@code legacy} in the old one. */
    public static int pick(int modern, int legacy) {
        return Theme.modern ? modern : legacy;
    }

    /** Re-reads the look and starts a frame; screens call it before drawing. */
    public static void sync() {
        boolean m = readModern();
        if (m != modern) {
            modern = m;
            palette();
        }
        Px.frameStart();
    }

    public static void setModern(boolean on) {
        DuskConfig.get().uiStyle = on ? "dusk" : "legacy";
        DuskConfig.save();
        sync();
    }

    /**
     * One of a Dusk screen's draw passes: {@code frame} for the first pass of
     * the frame (re-reads the look, see {@link #sync}), {@code sans} to draw
     * its text in the modern look's sans.
     */
    public static void paint(boolean frame, boolean sans, Runnable body) {
        if (frame) sync();
        if (sans) sansPush();
        try {
            body.run();
        } finally {
            if (sans) sansPop();
        }
    }

    /** Text drawn until the matching {@link #sansPop} uses the modern look's sans (when modern). */
    public static void sansPush() {
        sansDepth++;
    }

    public static void sansPop() {
        sansDepth = Math.max(0, sansDepth - 1);
    }

    /** Whether text drawn now takes the sans (modern look, inside a {@link #sansPush}; or a {@link #hudSans} pass). */
    public static boolean sansOn() {
        return modern && sansDepth > 0 || hudSans > 0;
    }

    /**
     * Whether sans text drawn now gets a soft shadow when it asks for one:
     * only in the HUD, where it sits on the world rather than on a panel.
     */
    public static boolean sansShadow() {
        return hudSans > 0;
    }

    /** The soft shadow under sans text in {@code argb}. */
    public static int sansShadowColor(int argb) {
        int a = (argb >>> 24) * 3 / 4;
        return a << 24 | (argb & 0xFCFCFC) >> 3;
    }

    private static int hudSans;

    /** Draws {@code body} (a HUD element) in the sans whatever the look, with its shadows kept. */
    public static void hudSans(Runnable body) {
        hudSans++;
        try {
            body.run();
        } finally {
            hudSans--;
        }
    }

    public static void hudSansPush() {
        hudSans++;
    }

    public static void hudSansPop() {
        hudSans = Math.max(0, hudSans - 1);
    }

    /** Draws {@code body} in the default font even inside a sans page (HUD previews). */
    public static void sansOff(Runnable body) {
        int depth = sansDepth, hud = hudSans;
        sansDepth = 0;
        hudSans = 0;
        try {
            body.run();
        } finally {
            sansDepth = depth;
            hudSans = hud;
        }
    }

    // ---- palette ------------------------------------------------------------------

    public static int TEXT, TEXT_MUTED, TEXT_FAINT, OVERLAY;
    /** Two-tone label pairs (Figma frames 7/8): idle and active. One colour each in the modern look. */
    public static int LABEL_UP, LABEL_LO, ACTIVE_UP, ACTIVE_LO;
    /** The module grid's "enabled" pair and card surfaces. */
    public static int MOSS_UP, MOSS_LO, MOSS_BOT;
    public static int RED_UP, SURFACE, SURFACE_BOT, ACCENT, ACCENT_HOT;
    /** Label pairs the launcher's TT tones use. */
    public static int DIM, GREEN_UP, GREEN_LO, GOLD_LO_TEXT;
    public static int RED_CORNER, GLYPH;
    /** Modern surfaces: the page, a raised control and the step above it. */
    public static int BG, RAISED, RAISED_2;
    /** Hairlines: faint, normal and strong; the hover wash; a slider track. */
    public static int LINE, LINE_2, LINE_3, HOVER, TRACK;
    /** Text on an accent fill (dark ink on the modern white); the status colours. */
    public static int INK, OK, BAD, INFO, WARN;

    static {
        palette();
    }

    private static void palette() {
        if (modern) {
            // the launcher's Clean tone: graphite surfaces, a white accent with dark ink on it
            TEXT = 0xFFF4F4F5; TEXT_MUTED = 0xFFB4B4BB; TEXT_FAINT = 0xFF7F7F88; OVERLAY = 0x8C0B0B0D;
            LABEL_UP = LABEL_LO = 0xFFB4B4BB;
            ACTIVE_UP = ACTIVE_LO = 0xFFF4F4F5;
            MOSS_UP = MOSS_LO = 0xFF5FD38A; MOSS_BOT = 0x295FD38A;
            RED_UP = 0xFFFF6B5E; SURFACE = 0xFF18181B; SURFACE_BOT = 0xFF18181B; ACCENT = 0xFFF4F4F5; ACCENT_HOT = 0xFFFFFFFF;
            DIM = 0xFF7F7F88; GREEN_UP = GREEN_LO = 0xFF5FD38A; GOLD_LO_TEXT = 0xFFC4C4CC;
            RED_CORNER = 0xFFFF6B5E; GLYPH = 0xFFB4B4BB;
            BG = 0xFF0B0B0D; RAISED = 0xFF232327; RAISED_2 = 0xFF2C2C31;
            LINE = 0x14FFFFFF; LINE_2 = 0x24FFFFFF; LINE_3 = 0x38FFFFFF; HOVER = 0x0FFFFFFF; TRACK = 0xFF333338;
            INK = 0xFF111113; OK = 0xFF5FD38A; BAD = 0xFFFF6B5E; INFO = 0xFF7CB4FF; WARN = 0xFFF5C451;
        } else {
            TEXT = 0xFFF4F4F4; TEXT_MUTED = 0xFFB8B8B8; TEXT_FAINT = 0xFF828282; OVERLAY = 0x66000000;
            LABEL_UP = 0xFFC6C6C6; LABEL_LO = 0xFF7B7B7B;
            ACTIVE_UP = 0xFFF2F2F2; ACTIVE_LO = 0xFFA6A6A6;
            MOSS_UP = 0xFF8CCF2D; MOSS_LO = 0xFF468A28; MOSS_BOT = 0xFF202C1D;
            RED_UP = 0xFFFF1100; SURFACE = SURF_TOP; SURFACE_BOT = SURF_BOT; ACCENT = GOLD_UP; ACCENT_HOT = 0xFFFFE04D;
            DIM = 0xFFCFCFCF; GREEN_UP = 0xFF00FF40; GREEN_LO = 0xFF06DD0E; GOLD_LO_TEXT = GOLD_LO;
            RED_CORNER = 0xFFFF8B8E; GLYPH = 0xFFB8B8B8;
            BG = 0xFF101010; RAISED = 0xFF333333; RAISED_2 = 0xFF3C3C3C;
            LINE = BLACK; LINE_2 = BAND; LINE_3 = CORNER; HOVER = 0x22FFFFFF; TRACK = 0xFF141414;
            INK = 0xFF000000; OK = 0xFF00FF40; BAD = 0xFFFF1100; INFO = 0xFF55AAFF; WARN = GOLD_UP;
        }
    }

    /**
     * ALL-CAPS labels from the legacy look in sentence case for the modern
     * one ("SAVE OUTFIT" to "Save outfit"), keeping known initialisms; text
     * with any lower case is left as written.
     */
    public static String sentence(String text) {
        if (!modern || text.isEmpty()) return text;
        int upper = 0, lower = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLowerCase(ch)) lower++;
            else if (Character.isUpperCase(ch)) upper++;
        }
        // mostly capitals: a legacy label, maybe with a name or value spliced in (see caps)
        if (upper == 0 || upper < lower) return text;
        StringBuilder out = new StringBuilder(text.length());
        boolean first = true;
        String[] words = text.split(" ", -1);
        for (int n = 0; n < words.length; n++) {
            String word = words[n];
            if (n > 0) out.append(' ');
            String bare = word.replaceAll("[^A-Za-z0-9]", "");
            boolean caps = !bare.isEmpty() && bare.equals(bare.toUpperCase(java.util.Locale.ROOT))
                    && bare.chars().anyMatch(Character::isLetter) && bare.chars().noneMatch(Character::isDigit);
            if (!caps || KEEP.contains(bare) || bare.equals("I")) {
                out.append(word);
            } else {
                String w = word.toLowerCase(java.util.Locale.ROOT);
                if (first) {
                    int i = 0;
                    while (i < w.length() && !Character.isLetter(w.charAt(i))) i++;
                    if (i < w.length()) w = w.substring(0, i) + Character.toUpperCase(w.charAt(i)) + w.substring(i + 1);
                }
                out.append(w);
            }
            if (!bare.isEmpty()) first = false;
        }
        return out.toString();
    }

    /**
     * A name or value for a legacy all-caps label: upper case in the legacy
     * look, as written in the modern one (where {@link #sentence} leaves it be).
     */
    public static String caps(String text) {
        return modern ? text : text.toUpperCase(java.util.Locale.ROOT);
    }

    private static final java.util.Set<String> KEEP = java.util.Set.of(
            "HUD", "FPS", "CPS", "XP", "UI", "OK", "ID", "RGB", "HEX", "PVP", "MC", "GUI", "TPS", "HP", "AFK", "DM", "URL", "NPC",
            "TNT", "ESP", "FOV", "NBT", "LAN", "MOTD", "RAM", "GPU", "CPU", "PNG", "JPG", "API", "AM", "PM", "MS", "HSV", "VSYNC", "DPI");

    // ---- frames ---------------------------------------------------------

    /** A clickable plate. */
    public static void button(Canvas c, int x, int y, int w, int h, boolean hover, Kind kind) {
        if (modern) {
            Px.box(c, x, y, w, h, kind == Kind.PRIMARY ? Family.ACCENT : Family.GREY, hover, false, 0xFF);
            return;
        }
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
        if (modern) {
            // a card: a faint lift over the window, its hairline brighter on hover
            Round.fill(c, x, y, w, h, Px.radius(h, 14), hot ? 0x12FFFFFF : 0x0AFFFFFF, hot ? LINE_2 : LINE);
            return;
        }
        Px.frame(c, x, y, w, h, true, BAND, BAND, CORNER, top, bot, 45, hot, false, 0xFF);
    }

    /**
     * Pixel text with the launcher's hard two-tone split: {@code up} over the
     * top half of the cap, {@code lo} below. The split is clipped in screen
     * space, so draw it outside any transform.
     */
    public static void label(Canvas c, String text, int x, int y, int up, int lo, float scale) {
        if (modern) {
            sansPush();
            scaledText(c, sentence(text), x, y, up, scale);
            sansPop();
            return;
        }
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
        if (modern) {
            sansPush();
            int w = Math.round(c.textWidth(sentence(text)) * scale);
            sansPop();
            return w;
        }
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
        if (modern) {
            if (hot) Round.pill(c, x, y, w, h, HOVER);
            return;
        }
        Px.frame(c, x, y, w, h, false, BAND, BAND, CORNER, SURF_TOP, SURF_TOP, 100, hot, false, 0xFF);
    }

    /** The window-close cell: red band and corners on a red surface. */
    public static void closeCell(Canvas c, int x, int y, int w, int h, boolean hot) {
        if (modern) {
            if (hot) Round.pill(c, x, y, w, h, 0x33FF6B5E);
            return;
        }
        int face = hot ? RED_UP : 0xFFDD0626;
        Px.frame(c, x, y, w, h, false, RED_UP, 0xFFDD0626, RED_CORNER, face, face, 100, false, false, 0xFF);
    }

    /** The bar's filler: a plain panel with bands top and bottom only. */
    public static void barFill(Canvas c, int x, int y, int w, int h) {
        if (modern) return;
        c.fill(x, y, x + w, y + h, SURF_TOP);
        c.fill(x, y, x + w, y + 1, BAND);
        c.fill(x, y + h - 1, x + w, y + h, BAND);
    }


    public static void vDivider(Canvas c, int x, int y0, int y1) {
        c.fill(x, y0, x + 1, y1, modern ? LINE_2 : BLACK);
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

    /** "Dusk"'s advance in the bold cut, in thousandths of an em. */
    private static final int DUSK_EM = 2424;

    /** Window pixels per font unit for a sans cap half of {@code size} window pixels, on a quarter step. */
    private static float sansStep(int size) {
        return Math.max(0.5f, Math.round(size * 0.5f / 7f * 4) / 4f);
    }

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
        if (modern) {
            int size = MARK_ART * art(unit);
            return (int) Math.ceil((size + size * 0.28 + DUSK_EM * 9.4 * sansStep(size) / 1000.0) / Px.gui());
        }
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
        if (modern) {
            // the app icon and "Dusk" in the bold sans, its cap half the icon's height
            float e = sansStep(size);
            int baseline = wy + Math.round(size / 2f + 3.5f * e);
            sansPush();
            c.push();
            c.translate(wx + Math.round(size * 1.28f), baseline - 7 * e);
            c.scale(e, e);
            c.text(net.minecraft.network.chat.Component.literal("Dusk").withStyle(net.minecraft.ChatFormatting.BOLD),
                    0, 0, alpha255 << 24 | TEXT & 0xFFFFFF, false);
            c.pop();
            sansPop();
            c.pop();
            return;
        }

        int tx = wx + size + MARK_GAP * cell, ty = wy + (size - GLYPH_H * cell) / 2;
        cells(c, LETTER_MASK, tx + cell, ty + cell, cell, cell, alpha255, r -> SHADOW);
        cells(c, LETTER_MASK, tx, ty, cell, cell, alpha255, r -> r < SPLIT ? TEXT_TOP : TEXT_BOT);
        c.pop();
    }

    // ---- the bare icon --------------------------------------------------

    /** Window pixels per art pixel for an icon about {@code h} GUI pixels tall. */
    private static int markScale(int h) {
        return Math.max(1, (int) Math.floor(h * Px.gui() / MARK_ART));
    }

    /** Side of {@link #mark} in GUI pixels. */
    public static int markSize(int h) {
        return (int) Math.ceil(MARK_ART * markScale(h) / Px.gui());
    }

    /** Just the app icon, no box and no type, at most {@code h} GUI pixels square. */
    public static void mark(Canvas c, int x, int y, int h, int alpha) {
        double s = Px.gui();
        int k = markScale(h);
        c.push();
        c.scale((float) (1 / s), (float) (1 / s));
        c.translate((int) Math.round(x * s), (int) Math.round(y * s));
        c.scale(k, k);
        c.blit(MARK, 0, 0, 0, 0, MARK_ART, MARK_ART, MARK_ART, MARK_ART, Math.max(5, alpha) << 24 | 0xFFFFFF);
        c.pop();
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
