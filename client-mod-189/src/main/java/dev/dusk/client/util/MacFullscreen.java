package dev.dusk.client.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * macOS fullscreen without rebuilding the window.
 *
 * <p>LWJGL 2 switches fullscreen by destroying the Cocoa window and making a
 * new one. On current macOS that frees the view while AppKit still has work
 * queued for it, and the game crashes. Instead we ask the existing window to
 * go into macOS's own fullscreen (the green button), which LWJGL already
 * allows. The window keeps its GL context and vanilla's resize check picks up
 * the new size.
 *
 * <p>LWJGL keeps the NSWindow pointer as the first field of the struct behind
 * {@code MacOSXDisplay.window}. The JDK's AWT library has a native that calls
 * {@code toggleFullScreen:} on a raw NSWindow pointer on the AppKit thread;
 * it never touches the Java object it's called on.
 */
public final class MacFullscreen {
    private static final Logger LOG = LogManager.getLogger("DuskClient");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().startsWith("mac");

    private static boolean failed;
    private static Method toggle;
    private static Object receiver;
    private static Field windowField;
    private static Object displayImpl;

    private MacFullscreen() {}

    /** Whether fullscreen goes through macOS instead of LWJGL. */
    public static boolean active() {
        return MAC && !failed;
    }

    /** The window size to create before going fullscreen. */
    public static void startWindowed(int width, int height) throws org.lwjgl.LWJGLException {
        Display.setDisplayMode(new DisplayMode(width, height));
    }

    /** Whether the window currently fills its screen. */
    public static boolean isFullscreen() {
        DisplayMode desktop = Display.getDesktopDisplayMode();
        return Display.getWidth() >= desktop.getWidth() && Display.getHeight() >= desktop.getHeight();
    }

    /** Asks macOS to move the window into or out of fullscreen. False if that isn't possible. */
    public static boolean toggle() {
        if (!active()) return false;
        try {
            if (toggle == null) bind();
            ByteBuffer info = (ByteBuffer) windowField.get(displayImpl);
            if (info == null) return false;
            long nsWindow = info.duplicate().order(ByteOrder.nativeOrder()).getLong(0);
            if (nsWindow == 0) return false;
            toggle.invoke(receiver, nsWindow);
            return true;
        } catch (Throwable t) {
            failed = true;
            LOG.warn("macOS fullscreen unavailable, falling back to LWJGL's", t);
            return false;
        }
    }

    /**
     * OptiFine checks the fullscreen state once per change and then does the
     * switch itself through LWJGL (the crashing path), or toggles "resizable",
     * which LWJGL uses to leave macOS fullscreen. Mark the new state as
     * already handled so it leaves the window alone.
     */
    public static void syncOptiFine(boolean full) {
        try {
            Class<?> config = Class.forName("Config", true, MacFullscreen.class.getClassLoader());
            Field f = config.getDeclaredField("fullscreenModeChecked");
            Field d = config.getDeclaredField("desktopModeChecked");
            f.setAccessible(true);
            d.setAccessible(true);
            f.setBoolean(null, full);
            d.setBoolean(null, !full);
        } catch (Throwable ignored) {
            // no OptiFine, or a version without these fields
        }
    }

    private static void bind() throws Exception {
        java.awt.Toolkit.getDefaultToolkit(); // loads the library that holds the native

        Field impl = Display.class.getDeclaredField("display_impl");
        impl.setAccessible(true);
        displayImpl = impl.get(null);
        windowField = displayImpl.getClass().getDeclaredField("window");
        windowField.setAccessible(true);

        Class<?> cls = Class.forName("sun.lwawt.macosx.CPlatformWindow");
        Method m = cls.getDeclaredMethod("_toggleFullScreenMode", long.class);
        m.setAccessible(true);

        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object unsafe = uf.get(null);
        receiver = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
        toggle = m;
    }
}
