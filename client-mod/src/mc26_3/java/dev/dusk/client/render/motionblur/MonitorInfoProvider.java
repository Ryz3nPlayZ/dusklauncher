package dev.dusk.client.render.motionblur;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;

/**
 * How fast the window's display refreshes. Re-checked once a second. 26.3
 * runs the window on SDL, which knows the display a window is on whether it
 * is fullscreen or not.
 */
public final class MonitorInfoProvider {

    private static final long CHECK_INTERVAL_NS = 1_000_000_000L;

    private static int lastDisplay = 0;
    private static int lastRefreshRate = 60;
    private static long lastCheckTime = 0;

    private MonitorInfoProvider() {}

    public static void updateDisplayInfo() {
        long now = System.nanoTime();
        if (now - lastCheckTime < CHECK_INTERVAL_NS) return;
        lastCheckTime = now;

        int display = SDLVideo.SDL_GetDisplayForWindow(Minecraft.getInstance().getWindow().handle());
        if (display != lastDisplay) {
            SDL_DisplayMode mode = display == 0 ? null : SDLVideo.SDL_GetCurrentDisplayMode(display);
            float hz = mode == null ? 0 : mode.refresh_rate();
            lastRefreshRate = hz > 0 ? Math.round(hz) : 60;
            lastDisplay = display;
        }
    }

    public static int getRefreshRate() {
        return lastRefreshRate;
    }
}
