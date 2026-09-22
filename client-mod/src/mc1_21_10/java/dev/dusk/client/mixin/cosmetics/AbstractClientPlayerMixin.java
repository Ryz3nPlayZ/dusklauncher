package dev.dusk.client.mixin.cosmetics;

import dev.dusk.client.cosmetics.CosmeticsManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swap the cape/elytra of the skin a player entity reports. This is the one
 * place vanilla's cape and elytra layers read the texture from, so the
 * custom cape shows up everywhere the entity is drawn (world, inventory
 * preview, F5) without touching the layers' logic.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void duskclient$getSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        PlayerSkin original = cir.getReturnValue();
        if (original == null) return;
        PlayerSkin swapped = CosmeticsManager.skinFor(self.getUUID(), self.getGameProfile().name(), original);
        if (swapped != original) cir.setReturnValue(swapped);
    }
}
