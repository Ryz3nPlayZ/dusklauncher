package dev.dusk.client.hud;

import dev.dusk.client.DuskClient;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Sans;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.media.MediaBackend;
import dev.dusk.client.module.ModuleManager;
import dev.dusk.client.modules.misc.Waypoints;
import dev.dusk.client.modules.render.LightOverlay;
import dev.dusk.client.modules.render.Zoom;
import dev.dusk.client.perf.Perf;
import dev.dusk.client.social.SocialNotifier;

/** Draws every enabled {@link HudElement} at its anchor with its scale. */
public final class HudRenderer {
    private static final Perf.Section ZOOM = Perf.part("Zoom"), LIGHT = Perf.part("Light Overlay"),
            WAYPOINTS = Perf.part("Waypoints"), MEDIA = Perf.part("Recording HUD"), POPUPS = Perf.part("Friend popups");

    private HudRenderer() {}

    /** Books the time since {@code t0} to {@code s}'s HUD slot; the new start. */
    private static long lap(Perf.Section s, long t0) {
        long now = Perf.start();
        s.acc(Perf.HUD, now - t0);
        return now;
    }

    /** Whether HUD text takes the smooth sans (Preferences, on versions that have it). */
    public static boolean smooth() {
        return Sans.supported() && DuskConfig.get().hudSmoothFont;
    }

    public static void render(Canvas c, HudContext ctx) {
        ModuleManager modules = DuskClient.modules();
        if (modules == null) return;
        Theme.sync();
        if (!ctx.editing()) {
            long t0 = Perf.start();
            Zoom.drawOverlay(c, ctx);
            t0 = lap(ZOOM, t0);
            LightOverlay.draw(c, ctx);
            t0 = lap(LIGHT, t0);
            Waypoints.drawMarkers(c, ctx);
            lap(WAYPOINTS, t0);
        }
        boolean f3 = !ctx.editing() && ctx.mc().getDebugOverlay().showDebugScreen();
        for (HudElement e : modules.hudElements()) {
            if (!e.enabled()) continue;
            if (f3 && e.hideInF3()) continue;
            if (!ctx.editing() && !e.visible(ctx)) continue;
            long t0 = Perf.start();
            draw(c, e, ctx);
            Perf.end(e.perf(), Perf.HUD, t0);
        }
        if (!ctx.editing()) {
            long t0 = Perf.start();
            MediaBackend.drawHud(c, ctx);
            t0 = lap(MEDIA, t0);
            SocialNotifier.draw(c, ctx.width());
            lap(POPUPS, t0);
        }
    }

    public static void draw(Canvas c, HudElement e, HudContext ctx) {
        boolean smooth = smooth();
        if (smooth) Theme.hudSansPush();
        try {
            e.beforeDraw(ctx);
            clampToScreen(e, ctx);
            float s = e.scale();
            int pad = e.background() ? HudElement.pad() : 0;
            c.push();
            c.translate(e.x(), e.y());
            c.scale(s, s);
            if (e.background()) {
                int w = e.width(ctx) + pad * 2, h = e.height(ctx) + pad * 2;
                if (Theme.modern()) Round.fill(c, 0, 0, w, h, Round.w(3), e.backgroundColor());
                else c.fill(0, 0, w, h, e.backgroundColor());
            }
            c.translate(pad, pad);
            e.render(c, ctx);
            c.pop();
        } finally {
            if (smooth) Theme.hudSansPop();
        }
    }

    /** Keeps the box on screen after window resizes or GUI-scale changes. */
    public static void clampToScreen(HudElement e, HudContext ctx) {
        int w = e.screenWidth(ctx), h = e.screenHeight(ctx);
        int x = Math.max(0, Math.min(ctx.width() - w, e.x()));
        int y = Math.max(0, Math.min(ctx.height() - h, e.y()));
        if (x != e.x() || y != e.y()) e.setPosition(x, y);
    }
}
