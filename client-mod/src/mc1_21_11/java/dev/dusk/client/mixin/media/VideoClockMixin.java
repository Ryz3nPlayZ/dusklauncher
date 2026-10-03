package dev.dusk.client.mixin.media;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.media.impl.VideoExporter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The time each frame's timer is advanced to (the first clock read in
 * {@code runTick}, on every version from 1.21.11): while a video exports it
 * is the frame count, so ticks and interpolation step 1/fps per frame.
 */
@Mixin(Minecraft.class)
public abstract class VideoClockMixin {
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J", ordinal = 0))
    private long duskclient$frameClock(Operation<Long> original) {
        return VideoExporter.millis(original.call());
    }
}
