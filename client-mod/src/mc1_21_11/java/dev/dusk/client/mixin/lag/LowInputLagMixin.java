package dev.dusk.client.mixin.lag;

import dev.dusk.client.modules.misc.LowInputLag;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low Input Lag's two points in the frame; the same on 1.21.11 and every 26.x. */
@Mixin(Minecraft.class)
public class LowInputLagMixin {
    @Inject(method = "runTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V"))
    private void duskclient$beforeMouseRead(boolean advanceGameTime, CallbackInfo ci) {
        LowInputLag.beforeMouseRead();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void duskclient$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
        LowInputLag.frameEnd();
    }
}
