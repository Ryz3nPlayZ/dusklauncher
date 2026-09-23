package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.render.CrosshairRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.2 draws the HUD by extracting render states, so the crosshair sprite
 * blit lives in extractCrosshair. Replacing just that call keeps the
 * attack indicator intact.
 */
@Mixin(Hud.class)
public class CrosshairGuiMixin {
    @WrapOperation(
            method = "extractCrosshair",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
                     ordinal = 0))
    private void duskclient$customCrosshair(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                            int x, int y, int width, int height, Operation<Void> original) {
        CustomCrosshair crosshair = CustomCrosshair.instance();
        if (crosshair != null && crosshair.shouldDraw(Minecraft.getInstance())) {
            CrosshairRenderer.render(graphics);
            return;
        }
        original.call(graphics, pipeline, sprite, x, y, width, height);
    }
}
