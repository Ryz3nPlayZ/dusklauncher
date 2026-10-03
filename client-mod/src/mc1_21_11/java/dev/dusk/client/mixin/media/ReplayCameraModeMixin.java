package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.ReplayPlayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The viewer has no tab-list entry to read a game mode from; it is a
 * spectator. While it stands in for a watched player's first-person hand
 * it wears their skin.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class ReplayCameraModeMixin {
    @Inject(method = "gameMode", at = @At("HEAD"), cancellable = true)
    private void duskclient$viewerMode(CallbackInfoReturnable<GameType> cir) {
        if (ReplayPlayer.active() && (Object) this instanceof LocalPlayer) cir.setReturnValue(GameType.SPECTATOR);
    }

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void duskclient$watchedSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        if (!((Object) this instanceof LocalPlayer) || !ReplayPlayer.active()) return;
        Player watched = ReplayPlayer.watched();
        if (watched instanceof AbstractClientPlayer other) cir.setReturnValue(other.getSkin());
    }
}
