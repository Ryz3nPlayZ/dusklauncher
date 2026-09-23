package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.render.CrosshairRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Swaps the vanilla crosshair sprite for the custom texture. Only the
 * sprite blit is replaced, so the attack indicator and every other part of
 * renderCrosshair keeps running.
 */
@Mixin(Gui.class)
public class CrosshairGuiMixin {
    @WrapOperation(
            method = "renderCrosshair",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
                     ordinal = 0))
    private void duskclient$customCrosshair(GuiGraphics graphics, RenderPipeline pipeline, Identifier sprite,
                                            int x, int y, int width, int height, Operation<Void> original) {
        CustomCrosshair crosshair = CustomCrosshair.instance();
        if (crosshair != null && crosshair.shouldDraw(Minecraft.getInstance())) {
            CrosshairRenderer.render(graphics);
            return;
        }
        original.call(graphics, pipeline, sprite, x, y, width, height);
    }
}
