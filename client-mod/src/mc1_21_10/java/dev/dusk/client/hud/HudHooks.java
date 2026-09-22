package dev.dusk.client.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.gui.HudEditorScreen;
import dev.dusk.client.modules.render.CustomCrosshair;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Wires the shared HUD into Fabric's HUD element list. 1.21.9–1.21.10 flavour:
 * elements render through GuiGraphics.
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
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (graphics, tick) -> {
            Minecraft mc = Minecraft.getInstance();
            CustomCrosshair crosshair = CustomCrosshair.instance();
            if (crosshair != null && crosshair.enabled()) {
                if (crosshair.shouldDraw(mc)) {
                    crosshair.render(new GraphicsCanvas(graphics, mc.font), graphics.guiWidth() / 2, graphics.guiHeight() / 2, mc);
                }
            } else {
                vanilla.render(graphics, tick);
            }
        });
    }
}
