package dev.dusk.client.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Small number and time formatting helpers for HUD text, as in the modern build. */
public final class Fmt {
    private Fmt() {}

    private static final long[] POW10 = {1, 10, 100, 1000, 10000, 100000, 1000000};

    /** {@code v} with {@code decimals} places, as {@code %.Nf} writes it, without String.format each frame. */
    public static String fixed(double v, int decimals) {
        if (decimals < 0 || decimals >= POW10.length || !(Math.abs(v) < 1e12)) {
            return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", v);
        }
        long pow = POW10[decimals];
        double scaled = Math.abs(v) * pow;
        long whole = (long) scaled;
        if (scaled - whole >= 0.5 - 1e-9) whole++;
        StringBuilder sb = new StringBuilder(12);
        if (v < 0 || v == 0 && 1 / v < 0) sb.append('-');
        sb.append(whole / pow);
        if (decimals > 0) {
            String frac = Long.toString(whole % pow);
            sb.append('.');
            for (int i = frac.length(); i < decimals; i++) sb.append('0');
            sb.append(frac);
        }
        return sb.toString();
    }

    public static StringBuilder pad2(StringBuilder sb, long n) {
        if (n >= 0 && n < 10) sb.append('0');
        return sb.append(n);
    }

    /** "m:ss", or "h:mm:ss" once {@code h} is above zero. */
    public static String clock(long h, long m, long s) {
        StringBuilder sb = new StringBuilder(10);
        if (h > 0) pad2(sb.append(h).append(':'), m);
        else sb.append(m);
        return pad2(sb.append(':'), s).toString();
    }

    public static String duration(long seconds) {
        return clock(seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /** Whether the system locale writes the time without AM/PM. */
    public static boolean localeIs24Hour() {
        DateFormat f = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault());
        return !(f instanceof SimpleDateFormat) || !((SimpleDateFormat) f).toPattern().toLowerCase(Locale.ROOT).contains("a");
    }

    /** "hh:mm[:ss]", as "HH" in 24-hour or with " a" appended in 12-hour. */
    public static SimpleDateFormat clock(boolean twentyFour, boolean seconds) {
        String p = seconds ? "hh:mm:ss" : "hh:mm";
        p = twentyFour ? p.replace("hh", "HH") : p + " a";
        return new SimpleDateFormat(p, Locale.getDefault());
    }

    public static int chroma(long periodMs, int offset) {
        float hue = ((System.currentTimeMillis() + offset) % periodMs) / (float) periodMs;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.85f, 1f) & 0xFFFFFF);
    }

    public static int lerpColor(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (int) (aa + (ba - aa) * t) << 24 | (int) (ar + (br - ar) * t) << 16
                | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
    }
}
