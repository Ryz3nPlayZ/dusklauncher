package dev.dusk.client.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many real screen pixels one of the game's pixels covers, for drawing
 * pixel art that stays crisp on scaled Mac screens.
 *
 * <p>With "looks like 1710 x 1112" on a 2560 x 1664 panel, macOS draws the
 * game at 3420 x 2224 and shrinks the whole frame by about 0.75 (or, with
 * Sharp on Retina off, stretches 1710 by about 1.5). A crosshair texel that
 * is 4 game pixels wide then lands on 2.99 screen pixels in one place and
 * 3.0 in the next, and thin lines blur or go missing. Knowing the ratio
 * lets the crosshair pick a size and spot where every texel edge lands on
 * a real screen pixel.
 *
 * <p>The panel's size comes from {@code system_profiler}, read once on a
 * background thread (it takes about a second). Not AWT: it fights LWJGL 2
 * for the main thread on macOS.
 */
public final class DisplaySnap {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().startsWith("mac");
    private static final Pattern NATIVE = Pattern.compile("\"spdisplays_pixelresolution\"\\s*:\\s*\"spdisplays_(\\d+)x(\\d+)");
    private static final Pattern LOOKS = Pattern.compile("\"_spdisplays_resolution\"\\s*:\\s*\"(\\d+)\\s*x\\s*(\\d+)");
    private static final Pattern MAIN = Pattern.compile("\"spdisplays_main\"\\s*:\\s*\"spdisplays_yes\"");

    private static volatile boolean started;
    /** Native panel pixels and "looks like" points of the main display; 0 until read. */
    private static volatile int nativeW, nativeH, looksW, looksH;

    private DisplaySnap() {}

    /** Starts the one-off read; cheap to call every frame. */
    public static void ensureStarted() {
        if (started || !MAC) return;
        started = true;
        Thread t = new Thread(DisplaySnap::read, "Dusk display probe");
        t.setDaemon(true);
        t.start();
    }

    private static void read() {
        try {
            Process p = new ProcessBuilder("/usr/sbin/system_profiler", "-json", "SPDisplaysDataType")
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            p.waitFor(10, TimeUnit.SECONDS);
            parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // no ratio: the crosshair draws as it always has
        }
    }

    /** Picks the main display's block (the first one when none says main). */
    static void parse(String json) {
        String block = json;
        Matcher main = MAIN.matcher(json);
        if (main.find()) {
            // a display's fields sit in one {...}; take the object round the main flag
            int open = json.lastIndexOf('{', main.start());
            int close = json.indexOf('}', main.end());
            if (open >= 0 && close > open) block = json.substring(open, close);
        }
        Matcher n = NATIVE.matcher(block);
        Matcher l = LOOKS.matcher(block);
        if (!n.find() || !l.find()) return;
        looksW = Integer.parseInt(l.group(1));
        looksH = Integer.parseInt(l.group(2));
        nativeH = Integer.parseInt(n.group(2));
        nativeW = Integer.parseInt(n.group(1)); // written last: known() reads it
    }

    public static boolean known() {
        return nativeW > 0 && looksW > 0;
    }

    /** Screen pixels per game pixel across; 1 when unknown. */
    public static double ratioX() {
        return known() ? nativeW / (double) (looksW * HiDpi.scale()) : 1;
    }

    /** Screen pixels per game pixel down; 1 when unknown. */
    public static double ratioY() {
        return known() ? nativeH / (double) (looksH * HiDpi.scale()) : 1;
    }

    /** How far {@code v * r} is from a whole number. */
    private static double miss(double v, double r) {
        double x = v * r;
        return Math.abs(x - Math.rint(x));
    }

    /**
     * A whole number of game pixels for one texel: the size within 1.5x of
     * {@code want} nearest to it whose width on screen is a whole number of
     * pixels (larger wins a tie), or plain {@code want} rounded when no size
     * that close is crisp, so the crosshair never jumps far from your scale.
     */
    public static int texel(double want, double r) {
        int plain = Math.max(1, (int) Math.round(want));
        if (Math.abs(r - 1) < 1e-6) return plain;
        int lo = Math.max(1, (int) Math.ceil(want / 1.5 - 1e-9)), hi = (int) Math.floor(want * 1.5 + 1e-9);
        int best = -1;
        for (int k = lo; k <= hi; k++) {
            if (miss(k, r) > 0.1) continue;
            if (best < 0 || Math.abs(k - want) < Math.abs(best - want) - 1e-9
                    || (Math.abs(Math.abs(k - want) - Math.abs(best - want)) <= 1e-9 && k > best)) best = k;
        }
        return best > 0 ? best : plain;
    }

    /** A whole game-pixel position near {@code want} that starts on a screen pixel edge. */
    public static int edge(double want, double r) {
        int base = (int) Math.round(want);
        if (Math.abs(r - 1) < 1e-6) return base;
        int best = base;
        double bestMiss = miss(base, r);
        for (int d = 1; d <= 8 && bestMiss > 0.02; d++) {
            for (int x : new int[] {base - d, base + d}) {
                double m = miss(x, r);
                if (m < bestMiss - 1e-9) {
                    best = x;
                    bestMiss = m;
                }
            }
        }
        return best;
    }
}
