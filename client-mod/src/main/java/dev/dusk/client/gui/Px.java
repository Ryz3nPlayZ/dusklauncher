package dev.dusk.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The launcher's PxBox, PxButton and TT (launcher/src/design/px.css) drawn
 * in the window's own pixels, so the 2px black outline, the 3px band and the
 * 9px L-corners keep the launcher's proportions at every GUI scale instead of
 * snapping to whole GUI pixels.
 *
 * <p>The scale: a {@link #H}-pixel control (vanilla's button height, the row
 * every Dusk menu is built on) is the launcher's 46px h-md row, so one
 * launcher pixel is {@code H / 46} GUI pixels. Everything is rounded to whole
 * window pixels, so edges stay hard.
 *
 * <p>Positions and sizes are GUI pixels, like every other Canvas call, and
 * must be given without a transform of the caller's own: the label's two-tone
 * cut is a screen-space clip.
 */
public final class Px {
    private Px() {}

    /** A standard control's height in GUI pixels: the launcher's h-md row. */
    public static final int H = 20;
    private static final double H_MD = 46;
    /** Launcher widths: a Choice/row button's min-width (140u) and the gap between controls (12u). */
    public static final int MIN_W = Math.round(73 * H / 46f), GAP = 3;

    private static final int BLACK = 0xFF000000;
    private static final int BAND = 0xFF323232, CORNER = 0xFF4A4A4A, SURF = 0xFF1E1E1E;
    private static final int GOLD_UP = 0xFFFFC600, GOLD_LO = 0xFFDE8105, GOLD_CORNER = 0xFFFFFBCD;
    /** The scrollbar thumb's inner gradient (tokens.css --sb-in-*). */
    private static final int SB_TOP = 0xFFC77707, SB_BOT = 0xFF382C1B;

    // ---- the launcher's TT (two-tone uppercase pixel text) -------------------

    /** TT sizes: font-size in launcher pixels at the reference window (26t, 30t...). */
    public enum Size {
        S11(18), S14(23), S16(26), S20(30);

        /** Cap height in launcher pixels: Monocraft's caps are 0.8em. */
        final double cap;

        Size(int t) {
            this.cap = t * 0.575 * 0.8;
        }
    }

    /** TT tones: the top and bottom colours of the hard split. */
    public enum Tone {
        GREY(0xFFC6C6C6, 0xFF7B7B7B),
        ACTIVE(0xFFF2F2F2, 0xFFA6A6A6),
        ACCENT(GOLD_UP, GOLD_LO),
        GREEN(0xFF00FF40, 0xFF06DD0E),
        RED(0xFFFF1100, 0xFFDD0626),
        DIM(0xFFCFCFCF, 0xFFCFCFCF),
        PLAIN(0xFFF0F0F0, 0xFFF0F0F0);

        public final int up, lo;

        Tone(int up, int lo) {
            this.up = up;
            this.lo = lo;
        }
    }

    // ---- scale ---------------------------------------------------------------

    /** Window pixels per GUI pixel. */
    public static double gui() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** Window pixels per launcher pixel. */
    private static double unit() {
        return gui() * H / H_MD;
    }

    /** {@code css} launcher pixels in whole window pixels, never less than one. */
    private static int px(double css) {
        return Math.max(1, (int) Math.round(css * unit()));
    }

    /** One font pixel of a TT label, in window pixels. Pixel type reads small, so this rounds up from .4. */
    private static int fontPx(Size size) {
        return Math.max(1, (int) Math.ceil(size.cap / 7 * unit() - 0.4));
    }

    // ---- boxes -----------------------------------------------------------------

    /** A grey PxButton; dimmed (the launcher's :disabled filter) when not {@code active}. */
    public static void button(Canvas c, int x, int y, int w, int h, boolean hot, boolean active) {
        box(c, x, y, w, h, Theme.Family.GREY, hot && active, !active, 0xFF);
    }

    /** A PxButton or PxBox of any family, faded by {@code alpha} (0-255). */
    public static void box(Canvas c, int x, int y, int w, int h, Theme.Family f, boolean hot, boolean disabled, int alpha) {
        frame(c, x, y, w, h, true, f.up, f.lo, f.corner, SURF, f.bot, f.hold, hot, disabled, alpha);
    }

    /**
     * The whole construction. {@code black} draws the 2px outline (a navbar cell
     * leaves it to the bar); {@code corner} 0 leaves the L-corners off; the
     * surface is {@code top} held flat through {@code hold}% and then blended
     * to {@code bot}. The launcher's hover and disabled filters apply to every
     * colour.
     */
    public static void frame(Canvas c, int x, int y, int w, int h, boolean black, int up, int lo, int corner,
                             int top, int bot, int hold, boolean hot, boolean disabled, int alpha) {
        if (w <= 0 || h <= 0) return;
        double s = gui();
        int x0 = (int) Math.round(x * s), y0 = (int) Math.round(y * s);
        int x1 = (int) Math.round((x + w) * s), y1 = (int) Math.round((y + h) * s);
        int k = black ? px(2) : 0, b = px(3);
        int leg = Math.min(px(9), Math.min(x1 - x0, y1 - y0) / 3);
        c.push();
        c.scale((float) (1 / s), (float) (1 / s));
        if (black) c.fill(x0, y0, x1, y1, tint(BLACK, false, false, alpha));
        int ix0 = x0 + k, iy0 = y0 + k, ix1 = x1 - k, iy1 = y1 - k;
        if (ix1 > ix0 && iy1 > iy0) {
            int mid = (iy0 + iy1) / 2;
            c.fill(ix0, iy0, ix1, mid, tint(up, hot, disabled, alpha));
            c.fill(ix0, mid, ix1, iy1, tint(lo, hot, disabled, alpha));
            int sx0 = ix0 + b, sy0 = iy0 + b, sx1 = ix1 - b, sy1 = iy1 - b;
            if (sx1 > sx0 && sy1 > sy0) {
                int t = tint(top, hot, disabled, alpha), bt = tint(bot, hot, disabled, alpha);
                // the surface gradient spans the padding box; the band covers its edges
                int held = iy0 + (iy1 - iy0) * hold / 100;
                if (held > sy0) c.fill(sx0, sy0, sx1, Math.min(held, sy1), t);
                if (held < sy1) {
                    int from = Math.max(held, sy0);
                    double span = Math.max(1, iy1 - held);
                    c.fillGradient(sx0, from, sx1, sy1, lerp(t, bt, (from - held) / span), lerp(t, bt, (sy1 - held) / span));
                }
            }
            if (corner != 0 && leg > b) {
                int co = tint(corner, hot, disabled, alpha);
                c.fill(ix1 - leg, iy0, ix1, iy0 + b, co);   // top-right, along the top
                c.fill(ix1 - b, iy0, ix1, iy0 + leg, co);   // top-right, down the side
                c.fill(ix0, iy1 - b, ix0 + leg, iy1, co);   // bottom-left, along the bottom
                c.fill(ix0, iy1 - leg, ix0 + b, iy1, co);   // bottom-left, up the side
            }
        }
        c.pop();
    }

    /** A text field: the launcher's panel PxBox (no corners), its band gold while focused. */
    public static void field(Canvas c, int x, int y, int w, int h, boolean focused) {
        int band = focused ? GOLD_LO : BAND;
        frame(c, x, y, w, h, true, band, band, 0, SURF, SURF, 100, false, false, 0xFF);
    }

    /** A popup or list body: the launcher's panel PxBox. */
    public static void panel(Canvas c, int x, int y, int w, int h) {
        frame(c, x, y, w, h, true, BAND, BAND, 0, SURF, SURF, 100, false, false, 0xFF);
    }

    /**
     * The launcher's range input as a Px control: a panel track, the accent
     * band filled up to the value, a small grey handle and the value on top.
     */
    public static void slider(Canvas c, int x, int y, int w, int h, double fraction, String text, boolean hot) {
        double f = Math.max(0, Math.min(1, fraction));
        panel(c, x, y, w, h);
        int handleW = Math.max(6, Math.round(h * 0.4f));
        int hx = x + (int) Math.round(f * (w - handleW));
        if (hx > x + 1) {
            frame(c, x, y, hx - x + handleW / 2, h, true, GOLD_UP, GOLD_LO, 0, SURF, 0xFF413018, 0, false, false, 0xFF);
        }
        box(c, hx, y, handleW, h, Theme.Family.GREY, hot, false, 0xFF);
        drawText(c, text, x, y, w, h, Size.S16, Tone.GREY, hot, false, false, 0xFF);
    }

    /**
     * The launcher's .scroll thumb: a 2px black housing either side, a closed
     * accent ring (split at mid-height) and the #C77707 to #382C1B inner
     * gradient. The track is clear.
     */
    public static void scrollbar(Canvas c, int x, int w, int barY, int barH) {
        double s = gui();
        int x0 = (int) Math.round(x * s), x1 = (int) Math.round((x + w) * s);
        int y0 = (int) Math.round(barY * s), y1 = (int) Math.round((barY + barH) * s);
        int k = px(2), b = px(3), mid = (y0 + y1) / 2;
        c.push();
        c.scale((float) (1 / s), (float) (1 / s));
        c.fill(x0, y0, x1, y1, BLACK);
        c.fill(x0 + k, y0, x1 - k, mid, GOLD_UP);
        c.fill(x0 + k, mid, x1 - k, y1, GOLD_LO);
        if (x1 - x0 > 2 * (k + b) && y1 - y0 > 2 * b) c.fillGradient(x0 + k + b, y0 + b, x1 - k - b, y1 - b, SB_TOP, SB_BOT);
        c.pop();
    }

    // ---- labels -------------------------------------------------------------------

    /** A TT label centred in a box, uppercase, in the grey tone (brighter while hot). */
    public static void label(Canvas c, String text, int x, int y, int w, int h, boolean hot, boolean disabled, int alpha) {
        label(c, text, x, y, w, h, Size.S16, Tone.GREY, hot, disabled, false, alpha);
    }

    /**
     * A TT label in a box: uppercase bold pixel text cut hard into {@code tone}'s
     * two colours, centred (or from the left with the launcher's 30t padding
     * when {@code left}), stepped down a size and then cut short when it does
     * not fit.
     */
    public static void label(Canvas c, String text, int x, int y, int w, int h, Size size, Tone tone,
                             boolean hot, boolean disabled, boolean left, int alpha) {
        drawText(c, text.toUpperCase(Locale.ROOT), x, y, w, h, size, tone, hot, disabled, left, alpha);
    }

    /** As {@link #label} but keeping the text's case, for values whose case means something. */
    public static void value(Canvas c, String text, int x, int y, int w, int h, Tone tone, boolean hot, boolean left) {
        drawText(c, text, x, y, w, h, Size.S16, tone, hot, false, left, 0xFF);
    }

    /** Horizontal padding inside a Px control (the launcher's 30t), in GUI pixels. */
    public static int pad() {
        return (int) Math.ceil(30 * 0.575 * unit() / gui());
    }

    private static void drawText(Canvas c, String text, int x, int y, int w, int h, Size size, Tone tone,
                                 boolean hot, boolean disabled, boolean left, int alpha) {
        if (text.isEmpty() || w <= 0) return;
        double s = gui();
        int f = fontPx(size);
        int room = (int) Math.round((w - 2 * (left ? pad() : Math.min(pad(), 4))) * s);
        Component shown = bold(text);
        while (f > 1 && width(c, shown) * f > room) f--;
        if (width(c, shown) * f > room) shown = bold(cut(c, text, room / f));
        int tw = width(c, shown) * f;
        double fx = left ? (x + pad()) * s : (x + w / 2.0) * s - tw / 2.0;
        // put the cut (three font rows under the cap) on a GUI pixel, where the clip can go
        double ideal = (y + h / 2.0) * s - 7 * f / 2.0;
        int split = (int) Math.round((ideal + 3 * f) / s);
        double fy = split * s - 3 * f;
        int up = tint(tone.up, hot, disabled, alpha), lo = tint(tone.lo, hot, disabled, alpha);
        int gx0 = (int) Math.floor(fx / s) - 1, gx1 = (int) Math.ceil((fx + tw) / s) + 1;
        int gy0 = (int) Math.floor(fy / s) - 1, gy1 = (int) Math.ceil((fy + 9 * f) / s) + 1;
        c.scissor(gx0, gy0, gx1, split);
        glyphs(c, shown, fx, fy, f, s, up);
        c.unscissor();
        c.scissor(gx0, split, gx1, gy1);
        glyphs(c, shown, fx, fy, f, s, lo);
        c.unscissor();
    }

    /** Width in window pixels of {@link #tt} text at {@code fx} window pixels to the font pixel across. */
    public static int ttWidth(Canvas c, String text, int fx) {
        return width(c, bold(text.toUpperCase(Locale.ROOT))) * fx;
    }

    /**
     * TT at an explicit size, for brand type: {@code fx} by {@code fy} window
     * pixels to the font pixel (the launcher's nav labels are stretched 1.4
     * wide), its cap centred on window row {@code cy} with the cut snapped to
     * a GUI pixel.
     */
    public static void tt(Canvas c, String text, int wx, int cy, int fx, int fy, Tone tone, int alpha) {
        Component shown = bold(text.toUpperCase(Locale.ROOT));
        double s = gui();
        int split = (int) Math.round((cy - 7 * fy / 2.0 + 3 * fy) / s);
        int wy = (int) Math.round(split * s) - 3 * fy;
        int tw = width(c, shown) * fx;
        int gx0 = (int) Math.floor(wx / s) - 1, gx1 = (int) Math.ceil((wx + tw) / s) + 1;
        int gy0 = (int) Math.floor(wy / s) - 1, gy1 = (int) Math.ceil((wy + 9 * fy) / s) + 1;
        int up = tint(tone.up, false, false, alpha), lo = tint(tone.lo, false, false, alpha);
        for (int half = 0; half < 2; half++) {
            c.scissor(gx0, half == 0 ? gy0 : split, gx1, half == 0 ? split : gy1);
            c.push();
            c.translate((float) (wx / s), (float) (wy / s));
            c.scale((float) (fx / s), (float) (fy / s));
            c.text(shown, 0, 0, half == 0 ? up : lo, false);
            c.pop();
            c.unscissor();
        }
    }

    /** Width of a TT label in GUI pixels, to lay controls out around. */
    public static int labelWidth(Canvas c, String text, Size size) {
        return (int) Math.ceil(width(c, bold(text.toUpperCase(Locale.ROOT))) * fontPx(size) / gui());
    }

    /** A glyph centred in a box, about half its height (the launcher's icon cells), in a TT tone's top colour. */
    public static void glyph(Canvas c, Icons icon, int x, int y, int w, int h, Tone tone, boolean hot, boolean disabled, int alpha) {
        double s = gui();
        int f = Math.max(1, (int) Math.round(Math.min(w, h) * s * 0.47 / Math.max(icon.width(), icon.height())));
        double fx = (x + w / 2.0) * s - icon.width() * f / 2.0, fy = (y + h / 2.0) * s - icon.height() * f / 2.0;
        c.push();
        c.translate((float) (Math.round(fx) / s), (float) (Math.round(fy) / s));
        c.scale((float) (1 / s), (float) (1 / s));
        icon.draw(c, 0, 0, tint(tone.up, hot, disabled, alpha), f);
        c.pop();
    }

    private static Component bold(String text) {
        return Component.literal(text).withStyle(ChatFormatting.BOLD);
    }

    /** The drawn width in font pixels: the font's advance leaves a pixel after the last glyph. */
    private static int width(Canvas c, Component text) {
        return Math.max(0, c.textWidth(text) - 1);
    }

    private static String cut(Canvas c, String text, int max) {
        String cut = text;
        while (!cut.isEmpty() && width(c, bold(cut + "...")) > max) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    private static void glyphs(Canvas c, Component text, double fx, double fy, int f, double s, int argb) {
        c.push();
        c.translate((float) (Math.round(fx) / s), (float) (Math.round(fy) / s));
        c.scale((float) (f / s), (float) (f / s));
        c.text(text, 0, 0, argb, false);
        c.pop();
    }

    // ---- colour ------------------------------------------------------------------

    /** The launcher's filters on one colour, then {@code alpha}. */
    private static int tint(int argb, boolean hot, boolean disabled, int alpha) {
        int filtered = Theme.filter(argb, hot, disabled);
        int a = (filtered >>> 24) * Math.max(0, Math.min(255, alpha)) / 255;
        return Math.max(alpha < 0xFF ? 5 : 0, a) << 24 | filtered & 0xFFFFFF;
    }

    private static int lerp(int from, int to, double t) {
        t = Math.max(0, Math.min(1, t));
        int a = (int) Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * t);
        int r = (int) Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int g = (int) Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
