package dev.dusk.client.mixin;

import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.modules.render.CrosshairIndicator;
import dev.dusk.client.modules.render.CustomCrosshair;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom crosshair for 1.21–1.21.3, where Fabric has no HUD layer API to
 * replace the vanilla crosshair element: draw ours in place of
 * Gui.renderCrosshair while the module is on.
 */
@Mixin(Gui.class)
public abstract class CrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void duskclient$customCrosshair(GuiGraphics graphics, DeltaTracker tick, CallbackInfo ci) {
        CustomCrosshair crosshair = CustomCrosshair.instance();
        if (crosshair == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!crosshair.shouldDraw(mc)) return; // vanilla keeps drawing, indicator and all
        ci.cancel();
        crosshair.render(new GraphicsCanvas(graphics, mc.font), CustomCrosshair.centre(graphics.guiWidth()), CustomCrosshair.centre(graphics.guiHeight()), mc);
        indicator(graphics, mc);
    }

    /** Crosshair Indicator's brackets over vanilla's crosshair (the cancelled path draws them itself). */
    @Inject(method = "renderCrosshair", at = @At("TAIL"))
    private void duskclient$crosshairIndicator(GuiGraphics graphics, DeltaTracker tick, CallbackInfo ci) {
        indicator(graphics, Minecraft.getInstance());
    }

    @Unique
    private static void indicator(GuiGraphics graphics, Minecraft mc) {
        CrosshairIndicator indicator = CrosshairIndicator.active(mc);
        if (indicator != null) {
            indicator.render(new GraphicsCanvas(graphics, mc.font), CustomCrosshair.centre(graphics.guiWidth()), CustomCrosshair.centre(graphics.guiHeight()), mc);
        }
    }
}
