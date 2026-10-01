package dev.dusk.client.mixin.shield;

import dev.dusk.client.modules.render.ShieldStatuses;
import dev.dusk.client.render.shield.ShieldStateTracker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shield Statuses: entity event 30 is "shield disabled" (WalksyLib LivingEntityMixin). */
@Mixin(LivingEntity.class)
public abstract class ShieldEntityEventMixin {
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void dusk$shieldEvent(byte status, CallbackInfo ci) {
        if (ShieldStatuses.active() && (Object) this instanceof Player player) {
            ShieldStateTracker.handleEntityStatus(player, status);
        }
    }
}
