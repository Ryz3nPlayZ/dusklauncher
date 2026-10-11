package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.render.TotemTweaks;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link TotemTweaks}: the item activation (totem pop) animation resized,
 * moved, lengthened or shortened, without spin, zoom or random drift, or not
 * drawn at all. 26.3 has its own copy.
 */
@Mixin(ScreenEffectRenderer.class)
public class TotemPopMixin {
    @Shadow private float itemActivationOffX;
    @Shadow private float itemActivationOffY;

    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void duskclient$hidePop(CallbackInfo ci) {
        if (TotemTweaks.hidden()) ci.cancel();
    }

    @WrapOperation(method = "renderItemActivationAnimation",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
    private void duskclient$popSize(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        float k = TotemTweaks.scale();
        original.call(pose, x * k, y * k, z * k);
    }

    @WrapOperation(method = "renderItemActivationAnimation",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void duskclient$popPosition(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        float[] t = TotemTweaks.translate(x, y, z);
        if (t == null) original.call(pose, x, y, z);
        else original.call(pose, t[0], t[1], t[2]);
    }

    // the pop's length: ticks left counted down from it, and the progress fraction divided by it
    @ModifyExpressionValue(method = "renderItemActivationAnimation", at = @At(value = "CONSTANT", args = "intValue=40"))
    private int duskclient$popTicks(int vanilla) {
        return TotemTweaks.popTicks();
    }

    @ModifyExpressionValue(method = "renderItemActivationAnimation", at = @At(value = "CONSTANT", args = "floatValue=40.0"))
    private float duskclient$popTicksF(float vanilla) {
        return TotemTweaks.popTicks();
    }

    @ModifyExpressionValue(method = "renderItemActivationAnimation", at = @At(value = "CONSTANT", args = "floatValue=900.0"))
    private float duskclient$spin(float vanilla) {
        return TotemTweaks.spinDegrees(vanilla);
    }

    @ModifyExpressionValue(method = "displayItemActivation", at = @At(value = "CONSTANT", args = "intValue=40"))
    private int duskclient$startPop(int vanilla) {
        return TotemTweaks.startPop();
    }

    @Inject(method = "displayItemActivation", at = @At("TAIL"))
    private void duskclient$centrePop(CallbackInfo ci) {
        if (!TotemTweaks.centred()) return;
        itemActivationOffX = 0;
        itemActivationOffY = 0;
    }
}
