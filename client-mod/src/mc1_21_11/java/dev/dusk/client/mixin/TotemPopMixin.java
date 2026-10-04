package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.render.TotemPop;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link TotemPop}: the item activation (totem pop) animation drawn smaller,
 * without its random sideways drift, or not at all.
 */
@Mixin(ScreenEffectRenderer.class)
public class TotemPopMixin {
    @Shadow private float itemActivationOffX;
    @Shadow private float itemActivationOffY;

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

    @Inject(method = "displayItemActivation", at = @At("TAIL"))
    private void duskclient$centrePop(CallbackInfo ci) {
        if (!TotemPop.centred()) return;
        itemActivationOffX = 0;
        itemActivationOffY = 0;
    }
}
