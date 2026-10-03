package dev.dusk.client.mixin.tweaks;

import dev.dusk.client.modules.render.BlockOutline;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The normal block outline's colour and thickness (the high-contrast backing line is left alone). */
@Mixin(LevelRenderer.class)
public class BlockOutlineMixin {
    private static final String HIT_OUTLINE = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V";

    @ModifyArg(method = "submitBlockOutline", index = 4, at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1))
    private int duskclient$color(int color) {
        return BlockOutline.color(color);
    }

    @ModifyArg(method = "submitBlockOutline", index = 5, at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1))
    private float duskclient$width(float width) {
        return BlockOutline.width(width);
    }
}
