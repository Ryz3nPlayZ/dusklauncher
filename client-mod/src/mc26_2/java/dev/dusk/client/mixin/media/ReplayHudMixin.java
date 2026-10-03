package dev.dusk.client.mixin.media;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.media.impl.ReplayPlayer;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 moved the HUD out of Gui into Hud; otherwise as on 1.21.11.
 *
 * The replay viewer is a spectator, so the HUD would draw the spectator
 * bar even while looking through a player's eyes. While watching someone
 * it draws for their game mode instead: hotbar, hearts, armor, and (for
 * the recorded player, whose hunger and experience are known) food and XP.
 */
@Mixin(Hud.class)
public abstract class ReplayHudMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    private GameType duskclient$watchedMode(MultiPlayerGameMode gameMode, Operation<GameType> original) {
        GameType mode = ReplayPlayer.hudMode();
        return mode != null ? mode : original.call(gameMode);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;canHurtPlayer()Z"))
    private boolean duskclient$watchedHurts(MultiPlayerGameMode gameMode, Operation<Boolean> original) {
        GameType mode = ReplayPlayer.hudMode();
        return mode != null ? mode.isSurvival() : original.call(gameMode);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasExperience()Z"))
    private boolean duskclient$watchedExperience(MultiPlayerGameMode gameMode, Operation<Boolean> original) {
        GameType mode = ReplayPlayer.hudMode();
        return mode != null ? mode.isSurvival() && ReplayPlayer.watchingRecorder() : original.call(gameMode);
    }

    /** Another player's hunger never reached the recording; an always-full bar would be a lie. */
    @Inject(method = {"renderFood", "extractFood"}, at = @At("HEAD"), cancellable = true)
    private void duskclient$unknownHunger(CallbackInfo ci) {
        if (ReplayPlayer.watched() != null && !ReplayPlayer.watchingRecorder()) ci.cancel();
    }
}
