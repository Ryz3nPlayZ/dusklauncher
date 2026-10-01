package dev.dusk.client.mixin.shield;

import dev.dusk.client.render.shield.ShieldTint;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Shield Statuses: tints the handle and the unpatterned plate. */
@Mixin(ShieldSpecialRenderer.class)
public abstract class ShieldTintMixin {
    @ModifyArg(method = "submit", index = 8, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ZZILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;I)V"))
    private int dusk$tint(int tint) {
        return ShieldTint.apply(tint);
    }
}
