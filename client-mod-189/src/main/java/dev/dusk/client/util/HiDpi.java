package dev.dusk.client.util;

import dev.dusk.client.config.DuskConfig;
import org.lwjgl.opengl.Display;

/**
 * Sharp rendering on Retina Macs.
 *
 * <p>LWJGL 2 makes a 1x window on macOS unless asked for a high-DPI one, and
 * macOS then stretches every frame 2x, so text and menus come out soft and
 * blotchy (every 1.8.9 client has this unless it turns it on). With
 * {@code org.lwjgl.opengl.Display.enableHighDPI} set before the window is
 * made, the GL surface is in real pixels, but LWJGL still reports the window
 * size and the mouse in points. Minecraft's size is kept in pixels (the
 * window size times this scale) and the GUI mouse reads are scaled to match.
 *
 * <p>The GUI scale setting is multiplied by the same factor, so menus keep
 * their physical size and just get twice the detail. "Auto" already picks
 * from the pixel size.
 */
public final class HiDpi {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().startsWith("mac");
    private static boolean enabled;

    private HiDpi() {}

    /** Before Display.create: asks LWJGL for a pixel-sized surface if the setting allows. */
    public static void beforeCreate() {
        if (!MAC) return;
        try {
            enabled = DuskConfig.get().retina;
        } catch (Throwable t) {
            enabled = true;
        }
        if (enabled) System.setProperty("org.lwjgl.opengl.Display.enableHighDPI", "true");
    }

    /** Pixels per point right now: 2 on a Retina screen, 1 elsewhere or when off. */
    public static int scale() {
        if (!enabled) return 1;
        try {
            float f = Display.getPixelScaleFactor();
            return f >= 1f ? Math.round(f) : 1;
        } catch (Throwable t) {
            return 1;
        }
    }

    public static int width() {
        return Display.getWidth() * scale();
    }

    public static int height() {
        return Display.getHeight() * scale();
    }

    /** A mouse coordinate from LWJGL (points) in pixels, like Minecraft's display size. */
    public static int mouse(int points) {
        return points * scale();
    }

    /** The GUI scale option with the pixel factor folded in; 0 (auto) stays auto. */
    public static int guiScale(int option) {
        return option == 0 ? 0 : option * scale();
    }
}
