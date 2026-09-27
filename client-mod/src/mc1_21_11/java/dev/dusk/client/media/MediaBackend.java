package dev.dusk.client.media;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.media.impl.Recorder;
import dev.dusk.client.media.impl.ReplayPlayer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * The recorder (full replays, or a rolling buffer the clip key saves) and
 * the replay player, behind the one API the shared MEDIA page and HUD use.
 * Built on ReplayMod's approach and file format; see NOTICE.
 */
public final class MediaBackend {
    private MediaBackend() {}

    public static boolean supported() { return true; }

    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(ReplayPlayer::tick);
        ClientTickEvents.END_CLIENT_TICK.register(Recorder::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Recorder.onDisconnect(handler.getConnection());
            ReplayPlayer.onDisconnect(handler.getConnection());
        });
    }

    public static boolean replaying() { return ReplayPlayer.active(); }

    public static boolean recording() { return Recorder.recording(); }

    public static void saveClip() {
        if (!ReplayPlayer.active()) Recorder.saveClip();
    }

    public static void settingsChanged() { Recorder.settingsChanged(); }

    /** @return why it could not start, or null once the replay is loading */
    @Nullable
    public static String watch(Path file) {
        boolean clip = file.toAbsolutePath().normalize().startsWith(Mcpr.clipsDir().toAbsolutePath().normalize());
        return ReplayPlayer.start(file, clip);
    }

    public static void drawHud(Canvas c, HudContext ctx) {
        ReplayPlayer p = ReplayPlayer.current();
        if (p != null) {
            if (Compat.currentScreen(ctx.mc()) != null) return;
            int h = 14, y = ctx.height() - h;
            c.fill(0, y, ctx.width(), ctx.height(), 0x99000000);
            float f = p.duration <= 0 ? 0 : Math.min(1f, p.clock() / (float) p.duration);
            c.fill(0, y, Math.round(ctx.width() * f), y + 1, Theme.ACCENT);
            String left = (p.paused() ? "❚❚ " : "▶ ") + Mcpr.duration(p.clock()) + " / " + Mcpr.duration(p.duration)
                    + "   " + speed(p.speed()) + (p.pov() ? "   player's view" : "");
            c.text(left, 6, y + 4, Theme.TEXT, false);
            String right = "←→ seek  ↑↓ speed  P pause  V view  Esc menu";
            c.text(right, ctx.width() - 6 - c.textWidth(right), y + 4, Theme.TEXT_FAINT, false);
            return;
        }
        if (Recorder.recording()) {
            String s = "● REC " + Mcpr.duration(Recorder.elapsedMs());
            c.text(s, ctx.width() - 6 - c.textWidth(s), 6, Theme.RED_UP, true);
        }
    }

    private static String speed(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    public static String status() {
        if (ReplayPlayer.active()) return "Watching a replay";
        return Recorder.status();
    }
}
