package dev.dusk.client.mixin.freecam;

import dev.dusk.client.modules.render.Freecam;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While Freecam is on, your player can't attack, use or pick blocks; clicks are dropped, not queued. */
@Mixin(KeyMapping.class)
public abstract class FreecamKeyMixin {
    @Shadow private int clickCount;

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void dusk$freecamHeld(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.blocksKey((KeyMapping) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
    private void dusk$freecamClick(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.blocksKey((KeyMapping) (Object) this)) {
            clickCount = 0;
            cir.setReturnValue(false);
        }
    }
}
