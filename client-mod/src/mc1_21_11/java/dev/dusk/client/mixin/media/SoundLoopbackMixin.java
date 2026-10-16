package dev.dusk.client.mixin.media;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Library;
import dev.dusk.client.media.impl.GameAudio;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

/**
 * Puts the game's sound on a loopback device when videos record game audio
 * ({@link GameAudio}); the real device keeps playing it. Leaves the library
 * alone otherwise.
 */
@Mixin(Library.class)
public abstract class SoundLoopbackMixin {
    @Inject(method = "openDeviceOrFallback", at = @At("RETURN"), cancellable = true)
    private static void duskclient$loopback(CallbackInfoReturnable<Long> cir) {
        long device = cir.getReturnValue();
        long swapped = GameAudio.opened(device);
        if (swapped != device) cir.setReturnValue(swapped);
    }

    @WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false))
    private long duskclient$loopbackFormat(long device, IntBuffer attributes, Operation<Long> original) {
        long context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer own = GameAudio.attributes(device, stack);
            context = original.call(device, own != null ? own : attributes);
        }
        GameAudio.contextCreated(device, context);
        return context;
    }

    @Inject(method = "isCurrentDeviceDisconnected", at = @At("HEAD"), cancellable = true)
    private void duskclient$realDisconnected(CallbackInfoReturnable<Boolean> cir) {
        if (GameAudio.routed()) cir.setReturnValue(GameAudio.realDisconnected());
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void duskclient$stopLoopback(CallbackInfo ci) {
        GameAudio.shutdown();
    }
}
