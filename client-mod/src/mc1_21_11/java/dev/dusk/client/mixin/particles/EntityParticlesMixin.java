package dev.dusk.client.mixin.particles;

import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hide Entity Running particles. The original hooked fall damage, which only
 * spawns particles server-side on modern versions; sprint particles are the
 * client-side half.
 */
@Mixin(Entity.class)
public class EntityParticlesMixin {
    @Inject(method = "spawnSprintParticle", at = @At("HEAD"), cancellable = true)
    private void duskclient$hideRunning(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() && ParticleHooks.hideRunning(self)) ci.cancel();
    }
}
