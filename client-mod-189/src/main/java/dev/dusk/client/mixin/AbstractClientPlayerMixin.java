package dev.dusk.client.mixin;

import dev.dusk.client.cosmetics.CosmeticsManager;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The cape every renderer asks for: a Dusk or MinecraftCapes cape wins over
 * the official one, and official capes disappear when the player hides them.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getLocationCape", at = @At("RETURN"), cancellable = true)
    private void dusk$cape(CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation vanilla = cir.getReturnValue();
        ResourceLocation ours = CosmeticsManager.capeFor((AbstractClientPlayer) (Object) this, vanilla);
        if (ours != vanilla) cir.setReturnValue(ours);
    }
}
