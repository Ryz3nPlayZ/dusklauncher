package dev.dusk.client.mixin.freecam;

import dev.dusk.client.modules.render.Freecam;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No first-person hand floating in front of the free camera. */
@Mixin(GameRenderer.class)
public class FreecamHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void dusk$freecamHand(CallbackInfo ci) {
        if (Freecam.active() != null) ci.cancel();
    }
}
