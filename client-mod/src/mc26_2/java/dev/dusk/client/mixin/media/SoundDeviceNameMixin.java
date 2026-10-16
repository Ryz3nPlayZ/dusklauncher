package dev.dusk.client.mixin.media;

import com.mojang.blaze3d.audio.Library;
import dev.dusk.client.media.impl.GameAudio;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The speakers' name while the game's sound is on the loopback, so the device checks don't reload it. */
@Mixin(Library.class)
public abstract class SoundDeviceNameMixin {
    @Inject(method = "queryDeviceName", at = @At("HEAD"), cancellable = true)
    private static void duskclient$realName(long device, CallbackInfoReturnable<String> cir) {
        String name = GameAudio.realName(device);
        if (name != null) cir.setReturnValue(name);
    }
}
