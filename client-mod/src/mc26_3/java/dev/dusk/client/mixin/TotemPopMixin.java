package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.render.TotemPop;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link TotemPop}: the item activation (totem pop) animation drawn smaller,
 * without its random sideways drift, or not at all. 26.3 keeps the drift on
 * the player's render state, so it is dropped where the animation reads it.
 */
@Mixin(ScreenEffectRenderer.class)
public class TotemPopMixin {
    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void duskclient$hidePop(CallbackInfo ci) {
        if (TotemPop.hidden()) ci.cancel();
    }

    @WrapOperation(method = "renderItemActivationAnimation",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
    private void duskclient$popSize(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        float k = TotemPop.scale();
        original.call(pose, x * k, y * k, z * k);
    }

    @ModifyExpressionValue(method = "renderItemActivationAnimation", at = {
            @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/level/PlayerRenderState$ItemActivationRenderState;offX:F"),
            @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/level/PlayerRenderState$ItemActivationRenderState;offY:F")})
    private float duskclient$centrePop(float off) {
        return TotemPop.centred() ? 0 : off;
    }
}
