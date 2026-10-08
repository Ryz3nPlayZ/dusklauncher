package dev.dusk.client.mixin.shield;

import dev.dusk.client.render.shield.ShieldTint;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Shield Statuses: 26.x draws handle and plate as one model; the pattern layers come after, untinted. */
@Mixin(ShieldSpecialRenderer.class)
public abstract class ShieldTintMixin {
    @ModifyArg(method = "submit", index = 5, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;IIILnet/minecraft/client/resources/model/sprite/SpriteId;Lnet/minecraft/client/resources/model/sprite/SpriteGetter;I)V"))
    private int dusk$tint(int tint) {
        return ShieldTint.apply(tint);
    }
}
