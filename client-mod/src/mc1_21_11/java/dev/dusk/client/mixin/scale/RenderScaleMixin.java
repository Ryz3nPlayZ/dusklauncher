package dev.dusk.client.mixin.scale;

import dev.dusk.client.render.WorldScale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render Scale's world pass: from just before the level to just before the GUI. */
@Mixin(GameRenderer.class)
public abstract class RenderScaleMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void duskclient$scaleWorld(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        WorldScale.begin();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void duskclient$stretchWorld(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        WorldScale.end();
    }
}
