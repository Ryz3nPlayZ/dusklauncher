package dev.dusk.client.mixin.shield;

import dev.dusk.client.render.shield.ShieldTint;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Shield Statuses: a patterned shield's plate is drawn by the banner pattern
 * code; its first call is the base, which takes the tint like the plain
 * plate does. The pattern layers keep their dye colours, as in the original.
 */
@Mixin(BannerRenderer.class)
public abstract class ShieldPatternTintMixin {
    @ModifyArg(method = "submitPatterns", index = 6, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private static int dusk$tintBase(int tint) {
        return ShieldTint.apply(tint);
    }
}
