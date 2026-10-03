package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.VideoExporter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grabs the export frame once the world (and the first-person hand) is
 * drawn and before the HUD goes over it: the same point vanilla takes its
 * world thumbnails.
 */
@Mixin(GameRenderer.class)
public abstract class VideoFrameMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.AFTER))
    private void duskclient$exportFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        VideoExporter.onFrame();
    }
}
