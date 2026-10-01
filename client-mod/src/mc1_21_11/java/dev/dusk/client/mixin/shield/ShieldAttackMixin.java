package dev.dusk.client.mixin.shield;

import dev.dusk.client.modules.render.ShieldStatuses;
import dev.dusk.client.render.shield.ShieldStateTracker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shield Statuses: remembers axe hits on players (WalksyLib MultiPlayerGameModeMixin). */
@Mixin(MultiPlayerGameMode.class)
public abstract class ShieldAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void dusk$shieldAttack(Player player, Entity target, CallbackInfo ci) {
        if (ShieldStatuses.active() && target instanceof Player targetPlayer) {
            ShieldStateTracker.handlePlayerAttack(targetPlayer);
        }
    }
}
