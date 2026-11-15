package dev.dusk.client.mixin.emote;

import dev.dusk.client.emote.EmoteState;
import dev.dusk.client.modules.misc.Emotes;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emotes: once a frame, put this frame of your emote on your render state (null for everyone else). */
@Mixin(AvatarRenderer.class)
public class EmoteExtractMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void duskclient$emote(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        ((EmoteState) state).duskclient$setEmote(Emotes.poseFor(avatar));
    }
}
