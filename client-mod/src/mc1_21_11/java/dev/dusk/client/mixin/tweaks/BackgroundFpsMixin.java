package dev.dusk.client.mixin.tweaks;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.dusk.client.modules.misc.BackgroundFps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Caps the frame limit while the window is in the background. */
@Mixin(FramerateLimitTracker.class)
public class BackgroundFpsMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void duskclient$cap(CallbackInfoReturnable<Integer> cir) {
        int limit = BackgroundFps.limit(cir.getReturnValueI());
        if (limit != cir.getReturnValueI()) cir.setReturnValue(limit);
    }
}
