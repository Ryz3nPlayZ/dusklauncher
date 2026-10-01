package dev.dusk.client.mixin.capes;

import dev.dusk.client.render.cape.CapeShapeHolder;
import dev.dusk.client.render.cape.CapeSim;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands this frame's simulated cape shape to the render state. */
@Mixin(PlayerRenderer.class)
public class CapeExtractMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
            at = @At("TAIL"))
    private void duskclient$extractCape(AbstractClientPlayer player, PlayerRenderState state, float partialTick, CallbackInfo ci) {
        ((CapeShapeHolder) state).duskclient$setCapeShape(CapeSim.shape(player, partialTick));
    }
}
