package dev.dusk.client.mixin.particles;

import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OverflowParticles: Mixin_AttackEntityEvent, for always-crit / always-sharpness. */
@Mixin(Player.class)
public class PlayerAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void duskclient$attack(Entity target, CallbackInfo ci) {
        ParticleHooks.onAttack((Player) (Object) this, target);
    }
}
