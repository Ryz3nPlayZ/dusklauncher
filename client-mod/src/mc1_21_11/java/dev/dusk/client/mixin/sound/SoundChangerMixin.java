package dev.dusk.client.mixin.sound;

import dev.dusk.client.modules.misc.SoundChanger;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sound Changer's volumes, applied where the engine works out a sound's
 * volume: when it starts (a zero skips it), each tick for looping sounds, and
 * when a category slider moves.
 */
@Mixin(SoundEngine.class)
public abstract class SoundChangerMixin {
    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void duskclient$soundChanger(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        float vanilla = cir.getReturnValueF();
        float changed = SoundChanger.volume(sound.getIdentifier().getPath(), vanilla);
        if (changed != vanilla) cir.setReturnValue(changed);
    }
}
