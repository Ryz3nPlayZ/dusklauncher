package dev.dusk.client.mixin.tweaks;

import dev.dusk.client.modules.render.BlockOutline;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The normal block outline's colour and thickness (the high-contrast backing line is left alone). */
@Mixin(LevelRenderer.class)
public class BlockOutlineMixin {
    private static final String HIT_OUTLINE = "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V";

    @ModifyArg(method = "renderBlockOutline", index = 6, at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1))
    private int duskclient$color(int color) {
        return BlockOutline.color(color);
    }

    @ModifyArg(method = "renderBlockOutline", index = 7, at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1))
    private float duskclient$width(float width) {
        return BlockOutline.width(width);
    }
}
