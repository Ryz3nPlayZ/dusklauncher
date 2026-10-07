package dev.dusk.client.mixin.cosmetics;

import dev.dusk.client.compat.SkinCompat;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A cracked player's Dusk skin wherever their tab-list entry's skin is read
 * (the entity, the tab list, chat heads), in place of the Steve/Alex a
 * profile without Mojang textures gets.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoSkinMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void duskclient$duskSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin original = cir.getReturnValue();
        if (original == null) return;
        PlayerSkin skin = SkinCompat.dusk(((PlayerInfo) (Object) this).getProfile(), original);
        if (skin != original) cir.setReturnValue(skin);
    }
}
