package dev.dusk.client.media;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Recording and replays hook 1.21.11's connection and protocol classes, so
 * below 1.21.11 there is no backend: MEDIA shows screenshots and whatever
 * recordings exist, and says why it can't make or play them.
 */
public final class MediaBackend {
    private static final String UNSUPPORTED = "Recording and replays need Minecraft 1.21.11 or newer.";

    private MediaBackend() {}

    public static boolean supported() { return false; }

    public static void init() {}

    public static boolean replaying() { return false; }

    public static boolean recording() { return false; }

    public static void saveClip() {}

    public static void settingsChanged() {}

    /** @return why it could not start, or null once the replay is loading */
    @Nullable
    public static String watch(Path file) { return UNSUPPORTED; }

    public static void drawHud(Canvas c, HudContext ctx) {}

    public static String status() { return UNSUPPORTED; }
}
