package dev.dusk.client.mixin.cosmetics;

import dev.dusk.client.cosmetics.AccessoriesLayer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the accessories layer on both (wide + slim) player renderers.
 * 1.21–1.21.1 flavour: no render states, so the layers look the cosmetics
 * up from the entity themselves; the upside-down flag is applied in
 * {@link UpsideDownMixin}.
 *
 * <p>Extends the target's superclass so {@code addLayer} (protected final
 * on {@link LivingEntityRenderer}) is reachable without an access widener.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin
        extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    protected PlayerRendererMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model,
                                  float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void duskclient$addLayers(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        this.addLayer(new AccessoriesLayer(this));
    }
}
