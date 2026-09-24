package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.Hitbox;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hitboxes' "Hide stuck arrows" (Combat Hitboxes' {@code StuckStingerMixin}). */
@Mixin(BeeStingerLayer.class)
public class StuckStingerMixin {
    @Inject(method = "numStuck", at = @At("RETURN"), cancellable = true)
    private void duskclient$hideStuck(AvatarRenderState state, CallbackInfoReturnable<Integer> cir) {
        if (Hitbox.hidesArrows()) cir.setReturnValue(0);
    }
}
