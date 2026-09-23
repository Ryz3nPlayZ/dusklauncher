package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.render.LowFire;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BactroMod's "Fire Height": wraps the first-person fire overlay in a
 * translation so it can sit lower on the screen. Called renderFire up to
 * 26.1 and submitFire from 26.2; the overlay's own pose stack is pushed and
 * popped so nothing else shifts.
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectMixin {
    @Unique
    private static boolean duskclient$firePushed;

    @Inject(method = {"renderFire", "submitFire"}, at = @At("HEAD"), require = 1)
    private static void duskclient$lowerFire(CallbackInfo ci, @Local(argsOnly = true) PoseStack poseStack) {
        float offset = LowFire.offsetY();
        duskclient$firePushed = offset != 0;
        if (!duskclient$firePushed) return;
        poseStack.pushPose();
        poseStack.translate(0, offset, 0);
    }

    @Inject(method = {"renderFire", "submitFire"}, at = @At("RETURN"), require = 1)
    private static void duskclient$restoreFire(CallbackInfo ci, @Local(argsOnly = true) PoseStack poseStack) {
        if (!duskclient$firePushed) return;
        duskclient$firePushed = false;
        poseStack.popPose();
    }
}
