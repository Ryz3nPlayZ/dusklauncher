package dev.dusk.client.mixin.capes;

import dev.dusk.client.render.cape.CapeShapeHolder;
import dev.dusk.client.render.cape.CapeSim;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands this frame's simulated cape shape to the render state. */
@Mixin(AvatarRenderer.class)
public class CapeExtractMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void duskclient$extractCape(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        ((CapeShapeHolder) state).duskclient$setCapeShape(CapeSim.shape(avatar, partialTick));
    }
}
