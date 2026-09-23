package dev.dusk.client.mixin;

import dev.dusk.client.render.MotionBlurRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blends the finished world render before the GUI goes on top of it. */
@Mixin(GameRenderer.class)
public abstract class MotionBlurMixin {
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void duskclient$afterRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        MotionBlurRenderer.afterLevelRender();
    }
}
