package dev.dusk.client.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.gui.HudEditorScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Wires the shared HUD into Fabric's HUD element list. 1.21.6–1.21.8 flavour:
 * elements render through GuiGraphics. The crosshair is not replaced
 * here: CrosshairGuiMixin swaps just the vanilla sprite so the attack
 * indicator keeps rendering.
 */
public final class HudHooks {
    private HudHooks() {}

    public static void register() {
        HudElementRegistry.addLast(ResourceLocation.fromNamespaceAndPath("duskclient", "hud"), (graphics, tick) -> {
            Minecraft mc = Minecraft.getInstance();
            ClickTracker.update(mc);
            if (Compat.currentScreen(mc) instanceof HudEditorScreen) return; // the editor draws it
            HudContext ctx = new HudContext(mc, graphics.guiWidth(), graphics.guiHeight(),
                    tick.getGameTimeDeltaPartialTick(true), false);
            HudRenderer.render(new GraphicsCanvas(graphics, mc.font), ctx);
        });
    }
}
