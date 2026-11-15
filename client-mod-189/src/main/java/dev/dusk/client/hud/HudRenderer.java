package dev.dusk.client.hud;

import dev.dusk.client.DuskClient;
import dev.dusk.client.module.ModuleManager;
import dev.dusk.client.util.Guard;

public final class HudRenderer {
    private HudRenderer() {}

    public static void render(Canvas c, HudContext ctx) {
        ModuleManager modules = DuskClient.modules();
        if (modules == null) return;
        boolean f3 = !ctx.editing() && ctx.mc().gameSettings.showDebugInfo;
        for (HudElement e : modules.hudElements()) {
            if (!e.enabled()) continue;
            if (f3 && e.hideInF3()) continue;
            int depth = c.depth();
            try {
                if (!ctx.editing() && !e.visible(ctx)) continue;
                draw(c, e, ctx);
            } catch (Throwable t) {
                c.popTo(depth); // a throw mid-draw would leave the matrix pushed
                Guard.fail(e.name() + " (HUD)", e, t);
            }
        }
    }

    public static void draw(Canvas c, HudElement e, HudContext ctx) {
        e.beforeDraw(ctx);
        clampToScreen(e, ctx);
        float s = e.scale();
        int pad = e.background() ? HudElement.PAD : 0;
        c.push();
        c.translate(e.x(), e.y());
        c.scale(s, s);
        if (e.background()) {
            c.fill(0, 0, e.width(ctx) + pad * 2, e.height(ctx) + pad * 2, e.backgroundColor());
        }
        c.translate(pad, pad);
        e.render(c, ctx);
        c.pop();
    }

    public static void clampToScreen(HudElement e, HudContext ctx) {
        int w = e.screenWidth(ctx), h = e.screenHeight(ctx);
        int x = Math.max(0, Math.min(ctx.width() - w, e.x()));
        int y = Math.max(0, Math.min(ctx.height() - h, e.y()));
        if (x != e.x() || y != e.y()) e.setPosition(x, y);
    }
}
