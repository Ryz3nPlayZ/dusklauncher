package dev.dusk.client.gui;

import dev.dusk.client.config.DuskConfig;

/**
 * Palette and frame drawing shared by every Dusk screen and widget, in one
 * of two looks the player picks under Preferences:
 * <ul>
 *   <li>{@link Style#DUSK}: the launcher's construction (launcher/src/design/tokens.css):
 *       a black outline, a #323232 band and #4a4a4a L-corners at the top-right and
 *       bottom-left, gold #ffc600/#de8105 for the primary action.</li>
 *   <li>{@link Style#DAWN}: the dawn-client-menu mock: flat near-black plates with a
 *       thin #2a2a30 border that lightens to #555562 on hover, amber #e09f2b focus.</li>
 * </ul>
 * The colour fields are reassigned when the style changes, so widgets that
 * read them always draw in the current look.
 */
public final class Theme {
    private Theme() {}

    public enum Style {
        DUSK("dusk", "Dusk"), DAWN("dawn", "Dawn");

        public final String id, label;

        Style(String id, String label) {
            this.id = id;
            this.label = label;
        }

        static Style of(String id) {
            for (Style s : values()) if (s.id.equalsIgnoreCase(id)) return s;
            return DUSK;
        }
    }

    public enum Kind { NORMAL, PRIMARY, FOCUSED }

    public static int WINDOW_BG, HEADER_BG, ROW_HOVER, BORDER, ACCENT, ACCENT_DIM, ON_ACCENT;
    public static int TEXT, TEXT_MUTED, TEXT_FAINT, TOGGLE_OFF, TRACK, FIELD_BG, CARD_BG, CARD_HOVER;
    public static final int OVERLAY = 0x66000000;
    public static final int OUTLINE = 0x80FFFFFF;
    public static int OUTLINE_HOT;

    // Dusk construction (tokens.css)
    private static final int BLACK = 0xFF000000;
    private static final int BAND = 0xFF323232, BAND_HOT = 0xFF4A4A4A, CORNER = 0xFF4A4A4A, CORNER_HOT = 0xFF6A6A6A;
    private static final int GOLD_UP = 0xFFFFC600, GOLD_LO = 0xFFDE8105, GOLD_CORNER = 0xFFFFFBCD;
    private static final int SURF_TOP = 0xFF1E1E1E, SURF_BOT = 0xFF2B261C, CTA_BOT = 0xFF413018;
    /** Two-tone label pairs (Figma frames 7/8): idle, active, moss "enabled", red heart. */
    public static final int LABEL_UP = 0xFFC6C6C6, LABEL_LO = 0xFF7B7B7B;
    public static final int ACTIVE_UP = 0xFFF2F2F2, ACTIVE_LO = 0xFFA6A6A6;
    public static final int MOSS_UP = 0xFF8CCF2D, MOSS_LO = 0xFF468A28, MOSS_BOT = 0xFF202C1D;
    public static final int RED_UP = 0xFFFF1100, RED_LO = 0xFFDD0626;
    public static final int SURFACE = SURF_TOP, SURFACE_BOT = SURF_BOT;
    // Dawn mock
    private static final int D_PLATE = 0xE6121214, D_PLATE_HOT = 0xF21E1E24, D_EDGE = 0xFF2A2A30, D_EDGE_HOT = 0xFF555562;
    private static final int D_AMBER = 0xFFE09F2B, D_DOT = 0xFFF59E0B;
    private static final int D_STORE_EDGE = 0xFFC4760A, D_STORE_EDGE_HOT = 0xFFFFAA1D, D_STORE_BG = 0xEB1C1610;

    private static Style style;

    static {
        apply(Style.of(DuskConfig.get().uiStyle));
    }

    public static Style style() { return style; }

    public static void setStyle(Style next) {
        apply(next);
        DuskConfig.get().uiStyle = next.id;
        DuskConfig.save();
    }

    private static void apply(Style s) {
        style = s;
        if (s == Style.DUSK) {
            WINDOW_BG = 0xF01E1E1E;
            HEADER_BG = 0xFF181818;
            ROW_HOVER = 0x14FFFFFF;
            BORDER = BAND;
            ACCENT = GOLD_UP;
            ACCENT_DIM = GOLD_LO;
            ON_ACCENT = 0xFF14140A;
            TEXT = 0xFFF4F4F4;
            TEXT_MUTED = 0xFFB8B8B8;
            TEXT_FAINT = 0xFF828282;
            TOGGLE_OFF = 0xFF323232;
            TRACK = 0xFF2A2A2A;
            FIELD_BG = 0xFF101010;
            CARD_BG = 0xFF181818;
            CARD_HOVER = 0xFF222222;
            OUTLINE_HOT = GOLD_UP;
        } else {
            WINDOW_BG = 0xF0141418;
            HEADER_BG = 0xF218181C;
            ROW_HOVER = 0x12FFFFFF;
            BORDER = 0xFF282830;
            ACCENT = D_AMBER;
            ACCENT_DIM = 0xFF8A5E14;
            ON_ACCENT = 0xFF1A1206;
            TEXT = 0xFFE5E7EB;
            TEXT_MUTED = 0xFF9CA3AF;
            TEXT_FAINT = 0xFF6C727D;
            TOGGLE_OFF = 0xFF2A2A30;
            TRACK = 0xFF24242C;
            FIELD_BG = 0xFF0C0C0F;
            CARD_BG = 0xE61C1C24;
            CARD_HOVER = 0xF0252532;
            OUTLINE_HOT = D_AMBER;
        }
    }

    // ---- frames ---------------------------------------------------------

    /** A window or card: the launcher panel in Dusk, a bordered plate in Dawn. */
    public static void panel(Canvas c, int x, int y, int w, int h) {
        panel(c, x, y, w, h, WINDOW_BG, false);
    }

    public static void panel(Canvas c, int x, int y, int w, int h, int body, boolean hot) {
        if (style == Style.DUSK) {
            c.fill(x, y, x + w, y + h, BLACK);
            c.fill(x + 1, y + 1, x + w - 1, y + h - 1, hot ? BAND_HOT : BAND);
            c.fill(x + 2, y + 2, x + w - 2, y + h - 2, body);
            corners(c, x, y, w, h, hot ? CORNER_HOT : CORNER);
        } else {
            c.fill(x, y, x + w, y + h, body);
            c.outline(x, y, w, h, hot ? D_EDGE_HOT : D_EDGE);
        }
    }

    /** A clickable plate. Draw the label on top with {@link #buttonLabel}. */
    public static void button(Canvas c, int x, int y, int w, int h, boolean hover, Kind kind) {
        if (style == Style.DUSK) {
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
            int bot = kind == Kind.PRIMARY ? CTA_BOT : SURF_BOT;
            int hold = y + 2 + (h - 4) * 45 / 100;
            c.fill(x + 2, y + 2, x + w - 2, hold, hover ? 0xFF242424 : SURF_TOP);
            c.fillGradient(x + 2, hold, x + w - 2, y + h - 2, hover ? 0xFF242424 : SURF_TOP, bot);
            corners(c, x, y, w, h, GOLD_CORNER);
        } else {
            boolean store = kind == Kind.PRIMARY;
            c.fill(x, y, x + w, y + h, store ? D_STORE_BG : hover ? D_PLATE_HOT : D_PLATE);
            c.fill(x + 1, y + 1, x + w - 1, y + 2, hover ? 0x1FFFFFFF : 0x0FFFFFFF);
            int edge = store ? (hover ? D_STORE_EDGE_HOT : D_STORE_EDGE)
                    : kind == Kind.FOCUSED ? D_AMBER : hover ? D_EDGE_HOT : D_EDGE;
            c.outline(x, y, w, h, edge);
            if (kind == Kind.FOCUSED) {
                int dy = y + h / 2 - 1;
                c.fill(x + w + 3, dy - 1, x + w + 7, dy + 3, 0x40F59E0B);
                c.fill(x + w + 4, dy, x + w + 6, dy + 2, D_DOT);
            }
        }
    }

    /**
     * The Figma construction with any surface: black outline, #323232 band,
     * a body held flat through 45% then blended from {@code top} to {@code bot},
     * #4a4a4a L-corners. Dawn draws its bordered plate instead.
     */
    public static void plate(Canvas c, int x, int y, int w, int h, int top, int bot, boolean hot) {
        if (style == Style.DUSK) {
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
        } else {
            c.fill(x, y, x + w, y + h, hot ? D_PLATE_HOT : D_PLATE);
            c.outline(x, y, w, h, hot ? D_EDGE_HOT : D_EDGE);
        }
    }

    /** A button's label, centred: Dusk's two-tone grey on normal plates, a flat colour otherwise. */
    public static void buttonLabel(Canvas c, String text, int x, int y, int w, int h, boolean hover, Kind kind) {
        int tx = x + (w - c.textWidth(text)) / 2, ty = y + (h - 7) / 2;
        if (style == Style.DUSK && kind == Kind.NORMAL) {
            label(c, text, tx, ty, hover ? ACTIVE_UP : LABEL_UP, hover ? ACTIVE_LO : LABEL_LO, 1f);
        } else {
            c.text(text, tx, ty, buttonText(hover, kind), false);
        }
    }

    /**
     * Pixel text with the launcher's hard two-tone split: {@code up} over the
     * top half of the cap, {@code lo} below (Dawn: {@code up} only). The split is
     * clipped in screen space, so draw it outside any transform.
     */
    public static void label(Canvas c, String text, int x, int y, int up, int lo, float scale) {
        if (style == Style.DAWN) {
            scaledText(c, text, x, y, up, scale);
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
        return Math.round(c.textWidth(text) * scale);
    }

    /** Flat text at a (possibly fractional) scale. */
    public static void scaledText(Canvas c, String text, int x, int y, int color, float scale) {
        if (scale == 1f) {
            c.text(text, x, y, color, false);
            return;
        }
        c.push();
        c.translate(x, y);
        c.scale(scale, scale);
        c.text(text, 0, 0, color, false);
        c.pop();
    }

    public static int buttonText(boolean hover, Kind kind) {
        if (style == Style.DAWN && kind == Kind.PRIMARY) return hover ? 0xFFFFE6A0 : 0xFFFFAA1D;
        if (style == Style.DUSK && kind == Kind.PRIMARY) return 0xFFFFFFFF;
        return hover ? 0xFFFFFFFF : TEXT;
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

    /** A text-entry box. */
    public static void field(Canvas c, int x, int y, int w, int h, boolean focused) {
        if (style == Style.DUSK) {
            c.fill(x, y, x + w, y + h, BLACK);
            c.fill(x + 1, y + 1, x + w - 1, y + h - 1, focused ? GOLD_LO : BAND);
            c.fill(x + 2, y + 2, x + w - 2, y + h - 2, FIELD_BG);
        } else {
            c.fill(x, y, x + w, y + h, FIELD_BG);
            c.outline(x, y, w, h, focused ? D_AMBER : D_EDGE);
        }
    }

    public static final int SWITCH_W = 22, SWITCH_H = 10;

    public static void switchBox(Canvas c, int sx, int sy, boolean on) {
        int w = SWITCH_W, h = SWITCH_H;
        if (style == Style.DUSK) {
            c.fill(sx, sy, sx + w, sy + h, BLACK);
            c.fill(sx + 1, sy + 1, sx + w - 1, sy + h / 2, on ? GOLD_UP : BAND);
            c.fill(sx + 1, sy + h / 2, sx + w - 1, sy + h - 1, on ? GOLD_LO : BAND);
            c.fill(sx + 2, sy + 2, sx + w - 2, sy + h - 2, on ? CTA_BOT : 0xFF141414);
            int kx = on ? sx + w - h + 1 : sx + 2;
            c.fill(kx, sy + 2, kx + h - 3, sy + h - 2, on ? GOLD_CORNER : CORNER);
        } else {
            c.fill(sx, sy, sx + w, sy + h, on ? 0xFF3A2A10 : FIELD_BG);
            c.outline(sx, sy, w, h, on ? D_AMBER : D_EDGE);
            int kx = on ? sx + w - h + 1 : sx + 1;
            c.fill(kx + 1, sy + 2, kx + h - 3, sy + h - 2, on ? D_AMBER : D_EDGE_HOT);
        }
    }

    public static void scrollbar(Canvas c, int x, int top, int bottom, int barY, int barH) {
        c.fill(x, top, x + 2, bottom, TRACK);
        c.fill(x, barY, x + 2, barY + barH, style == Style.DUSK ? GOLD_LO : TEXT_FAINT);
    }

    /** A thin separator line. */
    public static void divider(Canvas c, int x0, int x1, int y) {
        c.fill(x0, y, x1, y + 1, style == Style.DUSK ? BLACK : BORDER);
    }

    public static void vDivider(Canvas c, int x, int y0, int y1) {
        c.fill(x, y0, x + 1, y1, style == Style.DUSK ? BLACK : BORDER);
    }

    /** Cuts {@code text} to fit {@code max} pixels, ending in "...". */
    public static String ellipsize(Canvas c, String text, int max) {
        if (c.textWidth(text) <= max) return text;
        String cut = text;
        while (!cut.isEmpty() && c.textWidth(cut + "...") > max) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    /** Width of {@link #wordmark} text at {@code scale}. */
    public static int wordmarkWidth(Canvas c, String text, int scale) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) w += c.textWidth(String.valueOf(text.charAt(i))) + 1;
        return (w - 1) * scale;
    }

    /**
     * The big title: letter-spaced, a hard-banded fill and a #3b1000 drop.
     * Dusk: the launcher's two-tone gold split. Dawn: the mock's white to
     * amber to ember gradient, as hard pixel bands. Draw outside any transform
     * (the bands are clipped in screen space).
     */
    public static void wordmark(Canvas c, String text, int x, int y, int scale) {
        int w = wordmarkWidth(c, text, scale);
        drawSpaced(c, text, x, y + scale, scale, 0xFF3B1000);
        int cap = 7 * scale;
        float[] stops;
        int[] colors;
        if (style == Style.DUSK) {
            stops = new float[] {0f, 0.43f, 1.2f};
            colors = new int[] {GOLD_UP, GOLD_LO};
        } else {
            stops = new float[] {0f, 0.2f, 0.5f, 0.8f, 1.2f};
            colors = new int[] {0xFFFFFFFF, 0xFFFFEA78, 0xFFFFA51E, 0xFFE05300};
        }
        for (int i = 0; i < colors.length; i++) {
            int y0 = y + Math.round(stops[i] * cap), y1 = y + Math.round(stops[i + 1] * cap);
            c.scissor(x - scale, y0, x + w + scale, y1);
            drawSpaced(c, text, x, y, scale, colors[i]);
            c.unscissor();
        }
    }

    private static void drawSpaced(Canvas c, String text, int x, int y, int scale, int color) {
        c.push();
        c.translate(x, y);
        c.scale(scale, scale);
        int cx = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            c.text(ch, cx, 0, color, false);
            cx += c.textWidth(ch) + 1;
        }
        c.pop();
    }

    /** The mock's pixel sun (28x16 units) above the wordmark. */
    public static void sun(Canvas c, int x, int y, int unit) {
        int[][] rects = {
                {12, 0, 4, 3, 0xFFFFEC85}, {5, 3, 3, 3, 0xFFFFB424}, {20, 3, 3, 3, 0xFFFFB424},
                {1, 9, 3, 3, 0xFFFFA200}, {24, 9, 3, 3, 0xFFFFA200}, {8, 7, 12, 9, 0xFFFFB833},
        };
        for (int[] r : rects) c.fill(x + r[0] * unit, y + r[1] * unit, x + (r[0] + r[2]) * unit, y + (r[1] + r[3]) * unit, r[4]);
    }
}
