package dev.dusk.client.mixin.cosmetics;

import dev.dusk.client.cosmetics.AccessoriesLayer;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copy the per-player cosmetics onto the render state once per frame and
 * apply the upside-down flag (the "Dinnerbone" effect MinecraftCapes
 * exposes per profile). Also registers the accessories layer on both
 * (wide + slim) renderers. 1.21.2–1.21.8 flavour: PlayerRenderer /
 * PlayerRenderState; extra ears are decided in the ears layer mixin.
 *
 * <p>Extends the target's superclass so {@code addLayer} (protected final
 * on {@link LivingEntityRenderer}) is reachable without an access widener.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin
        extends LivingEntityRenderer<AbstractClientPlayer, PlayerRenderState, PlayerModel> {
    protected PlayerRendererMixin(EntityRendererProvider.Context context, PlayerModel model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void duskclient$addLayers(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        this.addLayer(new AccessoriesLayer(this));
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
            at = @At("TAIL"))
    private void duskclient$extractRenderState(AbstractClientPlayer player, PlayerRenderState state, float partialTick, CallbackInfo ci) {
        ExtendedAvatarRenderState ext = (ExtendedAvatarRenderState) state;
        PlayerCosmetics c = CosmeticsManager.get(player.getUUID(), player.getGameProfile().getName());
        ext.duskclient$setCosmetics(c);
        if (c == PlayerCosmetics.NONE) return;
        if (c.upsideDown() && !state.isUpsideDown) {
            state.isUpsideDown = true;
            state.xRot *= -1.0F;
            state.yRot *= -1.0F;
        }
    }
}
