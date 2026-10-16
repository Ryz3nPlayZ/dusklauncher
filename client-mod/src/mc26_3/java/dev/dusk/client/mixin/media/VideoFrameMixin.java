package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.ScreenRecorder;
import dev.dusk.client.media.impl.VideoExporter;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grabs the export frame once the world (and the first-person hand) is
 * drawn and before the HUD goes over it: the same point vanilla takes its
 * world thumbnails. The screen recorder takes its frame here when it
 * leaves the HUD out, and at the end of the frame otherwise.
 */
@Mixin(GameRenderer.class)
public abstract class VideoFrameMixin {
    @Inject(method = "render()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER))
    private void duskclient$exportFrame(CallbackInfo ci) {
        VideoExporter.onFrame();
        ScreenRecorder.onFrame(false);
    }

    /** The screen recorder's frame with the HUD and any menu on it: the finished picture. */
    @Inject(method = "render()V", at = @At("TAIL"))
    private void duskclient$screenFrame(CallbackInfo ci) {
        ScreenRecorder.onFrame(true);
    }
}
