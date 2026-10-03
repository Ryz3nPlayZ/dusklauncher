package dev.dusk.client.mixin.nametag;

import dev.dusk.client.render.nametag.NametagHooks;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PolyNametag's nametag removal, decided per entity while its render state is
 * extracted. The original kept the last extracted entity on the renderer and
 * read it back at submit time, which picks the wrong entity once a frame
 * extracts every entity before submitting any. Players who use Dusk also get
 * the Dusk mark in front of their name here.
 */
@Mixin(EntityRenderer.class)
public abstract class NametagStateMixin {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void duskclient$filterNametag(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state.nameTag == null) return;
        state.nameTag = NametagHooks.keepNametag(entity, state) ? NametagHooks.badged(entity, state.nameTag) : null;
    }
}
