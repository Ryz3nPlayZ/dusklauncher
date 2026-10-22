package dev.dusk.client.mixin;

import dev.dusk.client.perf.Perf;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks each frame for the Performance page (frame times, and the once-a-second roll-up). */
@Mixin(Minecraft.class)
public class PerfFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void duskclient$frame(boolean advanceGameTime, CallbackInfo ci) {
        Perf.frame();
    }
}
