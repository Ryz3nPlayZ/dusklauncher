package dev.dusk.client.hud;

import java.util.Locale;

/** Small number/name formatting helpers for HUD text. */
public final class Fmt {
    private Fmt() {}

    private static final long[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    /**
     * {@code v} with {@code decimals} places, as {@code %.Nf} writes it
     * (halves round up). HUD text asks for this every frame, and
     * String.format parses its pattern on each call, so the usual sizes
     * are built by hand.
     */
    public static String fixed(double v, int decimals) {
        if (decimals < 0 || decimals >= POW10.length || !(Math.abs(v) < 1e12)) {
            return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", v);
        }
        long pow = POW10[decimals];
        double scaled = Math.abs(v) * pow;
        long whole = (long) scaled;
        // %f rounds the shortest decimal form, so 0.15 is a half even though its double is a hair under
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

    /** {@code n} as at least two digits, like {@code %02d}. */
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

    /** "snowy_taiga" -> "Snowy Taiga". */
    public static String title(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[_\\s]+")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    public static String duration(long seconds) {
        return clock(seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /**
     * Flex-HUD's ClockUtils: whether the system locale writes the time
     * without AM/PM, which picks the clocks' default format.
     */
    public static boolean localeIs24Hour() {
        java.text.DateFormat f = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault());
        return !(f instanceof java.text.SimpleDateFormat s) || !s.toPattern().toLowerCase(Locale.ROOT).contains("a");
    }

    /** Flex-HUD's clock pattern: "hh:mm[:ss]", as "HH" in 24-hour or with " a" appended in 12-hour. */
    public static java.time.format.DateTimeFormatter clock(boolean twentyFour, boolean seconds) {
        String p = seconds ? "hh:mm:ss" : "hh:mm";
        p = twentyFour ? p.replace("hh", "HH") : p + " a";
        return java.time.format.DateTimeFormatter.ofPattern(p);
    }

    /** Hue cycles once per {@code periodMs}, for chroma colours. */
    public static int chroma(long periodMs, int offset) {
        float hue = ((System.currentTimeMillis() + offset) % periodMs) / (float) periodMs;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.85f, 1f) & 0xFFFFFF);
    }
}
