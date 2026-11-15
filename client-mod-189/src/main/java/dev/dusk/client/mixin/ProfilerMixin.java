package dev.dusk.client.mixin;

import dev.dusk.client.util.PerfLog;
import net.minecraft.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game already names every part of a frame for its F3 pie chart
 * ("tick", "level", "entities", "updatechunks", ...), but only times them
 * while the chart is up. The frame time log listens to the same names so a
 * slow frame can say which part was slow. Off, this is one field read.
 */
@Mixin(Profiler.class)
public abstract class ProfilerMixin {
    @Inject(method = "startSection", at = @At("HEAD"), require = 0)
    private void dusk$sectionStart(String name, CallbackInfo ci) {
        if (PerfLog.sections) PerfLog.sectionStart(name);
    }

    @Inject(method = "endSection", at = @At("HEAD"), require = 0)
    private void dusk$sectionEnd(CallbackInfo ci) {
        if (PerfLog.sections) PerfLog.sectionEnd();
    }
}
