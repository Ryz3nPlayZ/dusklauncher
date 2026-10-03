package dev.dusk.client.media;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.media.impl.Recorder;
import dev.dusk.client.media.impl.ReplayPlayer;
import dev.dusk.client.media.impl.VideoExporter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import dev.dusk.client.gui.DuskTitleScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * The recorder (full replays, or a rolling buffer the clip key saves) and
 * the replay player, behind the one API the shared MEDIA page and HUD use.
 * Built on ReplayMod's approach and file format; see NOTICE.
 */
public final class MediaBackend {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Media");

    private MediaBackend() {}

    public static boolean supported() { return true; }

    /** {@code -Ddusk.replay}: the launcher's WATCH, opened once the title screen is up. */
    private static @Nullable String pendingReplay = System.getProperty("dusk.replay");

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (pendingReplay == null || !mc.isGameLoadFinished() || !(Compat.currentScreen(mc) instanceof TitleScreen || Compat.currentScreen(mc) instanceof DuskTitleScreen)) return;
            Path file = Path.of(pendingReplay);
            pendingReplay = null;
            String why = watch(file);
            if (why == null) return;
            // didn't open (another version, a broken file): the media page says why
            LOG.warn("Could not open {} from the launcher: {}", file, why);
            Compat.setScreen(mc, new MediaScreen(Compat.currentScreen(mc), MediaScreen.REPLAYS));
        });
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
            if (VideoExporter.active()) {
                // drawn after the frame is taken, so none of this is in the video
                int h = 14;
                c.fill(0, 0, ctx.width(), h, 0xCC000000);
                c.fill(0, h - 1, Math.round(ctx.width() * VideoExporter.progress()), h, Theme.GREEN_UP);
                c.text("● EXPORTING   " + VideoExporter.status(), 6, 4, Theme.RED_UP, false);
                String right = "Esc progress / cancel";
                c.text(right, ctx.width() - 6 - c.textWidth(right), 4, Theme.TEXT_FAINT, false);
                return;
            }
            // watching someone, their hotbar takes the bottom of the screen
            int h = 14, y = p.pov() ? 0 : ctx.height() - h;
            c.fill(0, y, ctx.width(), y + h, 0x99000000);
            float f = p.duration <= 0 ? 0 : Math.min(1f, p.clock() / (float) p.duration);
            int edge = p.pov() ? y + h - 1 : y;
            c.fill(0, edge, Math.round(ctx.width() * f), edge + 1, Theme.ACCENT);
            if (p.markIn() >= 0 || p.markOut() >= 0) {
                int band = p.pov() ? y + h : y - 2;
                int a = mark(p.markIn() >= 0 ? p.markIn() : 0, p, ctx.width()), b = mark(p.markOut() >= 0 ? p.markOut() : p.duration, p, ctx.width());
                c.fill(a, band, Math.max(a + 1, b), band + 2, Theme.GREEN_UP);
            }
            String who = p.povName();
            String left = (p.paused() ? "❚❚ " : "▶ ") + Mcpr.duration(p.clock()) + " / " + Mcpr.duration(p.duration)
                    + "   " + speed(p.speed()) + (who != null ? "   watching " + who : p.pov() ? "   player's view" : "");
            c.text(left, 6, y + 4, Theme.TEXT, false);
            String right = p.pov() ? "V free camera  , . player  Shift leave  Esc menu" : "←→ seek  ↑↓ speed  P pause  V view  I O mark  Esc menu";
            c.text(right, ctx.width() - 6 - c.textWidth(right), y + 4, Theme.TEXT_FAINT, false);
            return;
        }
        if (Recorder.recording()) {
            String s = "● REC " + Mcpr.duration(Recorder.elapsedMs());
            c.text(s, ctx.width() - 6 - c.textWidth(s), 6, Theme.RED_UP, true);
        }
    }

    private static int mark(long t, ReplayPlayer p, int width) {
        return p.duration <= 0 ? 0 : Math.round(width * Math.min(1f, t / (float) p.duration));
    }

    private static String speed(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    public static String status() {
        if (ReplayPlayer.active()) return "Watching a replay";
        return Recorder.status();
    }
}
