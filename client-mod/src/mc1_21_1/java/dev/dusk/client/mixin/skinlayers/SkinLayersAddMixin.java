package dev.dusk.client.mixin.skinlayers;

import dev.dusk.client.render.skin.SkinLayer3D;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the 3D skin layer to both (wide + slim) player renderers. */
@Mixin(PlayerRenderer.class)
public abstract class SkinLayersAddMixin
        extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    protected SkinLayersAddMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model,
                                 float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void duskclient$addSkinLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        this.addLayer(new SkinLayer3D(this));
    }
}
