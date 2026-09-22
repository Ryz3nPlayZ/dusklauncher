package dev.dusk.client.mixin.cosmetics;

import dev.dusk.client.cosmetics.AccessoriesLayer;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copy the per-player cosmetics onto the render state once per frame, and
 * apply the two flags that live on vanilla state: upside-down (the
 * "Dinnerbone" effect MinecraftCapes exposes per profile) and extra ears.
 * Also registers the accessories layer on both (wide + slim) renderers.
 *
 * <p>Extends the target's superclass so {@code addLayer} (protected final
 * on {@link LivingEntityRenderer}) is reachable without an access widener.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin<T extends Avatar & ClientAvatarEntity>
        extends LivingEntityRenderer<T, AvatarRenderState, PlayerModel> {
    protected AvatarRendererMixin(EntityRendererProvider.Context context, PlayerModel model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void duskclient$addLayers(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        this.addLayer(new AccessoriesLayer(this));
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void duskclient$extractRenderState(T avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        ExtendedAvatarRenderState ext = (ExtendedAvatarRenderState) state;
        if (!(avatar instanceof Player player)) {
            // mannequins and other avatars: phase 4
            ext.duskclient$setCosmetics(null);
            return;
        }
        PlayerCosmetics c = CosmeticsManager.get(player.getUUID(), player.getGameProfile().name());
        ext.duskclient$setCosmetics(c);
        if (c == PlayerCosmetics.NONE) return;
        if (c.upsideDown() && !state.isUpsideDown) {
            state.isUpsideDown = true;
            state.xRot *= -1.0F;
            state.yRot *= -1.0F;
        }
        if (c.hasEars()) state.showExtraEars = true;
    }
}
