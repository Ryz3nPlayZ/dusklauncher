package dev.dusk.client.mixin.media;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.media.impl.ReplayPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spectators have no first-person hand. Looking through a player's eyes in
 * a replay, draw theirs: {@link ReplayHandStateMixin} lends the viewer what
 * they hold and how they swing, and {@link ReplayCameraModeMixin} their skin.
 */
@Mixin(GameRenderer.class)
public abstract class ReplayHandMixin {
    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    private GameType duskclient$watchedHand(MultiPlayerGameMode gameMode, Operation<GameType> original) {
        GameType mode = ReplayPlayer.hudMode();
        return mode != null ? mode : original.call(gameMode);
    }
}
