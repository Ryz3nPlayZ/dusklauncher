package dev.dusk.client.hud;

import java.util.Locale;

/** Small number/name formatting helpers for HUD text. */
public final class Fmt {
    private Fmt() {}

    public static String fixed(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
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
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** Hue cycles once per {@code periodMs}, for chroma colours. */
    public static int chroma(long periodMs, int offset) {
        float hue = ((System.currentTimeMillis() + offset) % periodMs) / (float) periodMs;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.85f, 1f) & 0xFFFFFF);
    }
}
