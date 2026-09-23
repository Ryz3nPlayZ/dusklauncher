package dev.dusk.client.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.gui.HudEditorScreen;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

/**
 * Wires the shared HUD into the vanilla HUD. 1.21–1.21.3 flavour: Fabric only
 * has HudRenderCallback (fires after the whole vanilla HUD); the crosshair is
 * replaced by {@link dev.dusk.client.mixin.CrosshairMixin} instead of a layer
 * swap.
 */
public final class HudHooks {
    private HudHooks() {}

    public static void register() {
        HudRenderCallback.EVENT.register((graphics, tick) -> {
            Minecraft mc = Minecraft.getInstance();
            ClickTracker.update(mc);
            if (Compat.currentScreen(mc) instanceof HudEditorScreen) return; // the editor draws it
            HudContext ctx = new HudContext(mc, graphics.guiWidth(), graphics.guiHeight(),
                    tick.getGameTimeDeltaPartialTick(true), false);
            HudRenderer.render(new GraphicsCanvas(graphics, mc.font), ctx);
        });
    }
}
