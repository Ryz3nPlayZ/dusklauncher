package dev.dusk.client.mixin.particles;

import dev.dusk.client.modules.render.TotemTweaks;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link TotemTweaks}: no pop particles, or none from your own pops (TotemTweaker's removeParticles). */
@Mixin(ParticleEngine.class)
public class TotemEmitterMixin {
    @Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V",
            at = @At("HEAD"), cancellable = true)
    private void duskclient$hideTotemParticles(Entity entity, ParticleOptions options, int lifetime, CallbackInfo ci) {
        if (options.getType() == ParticleTypes.TOTEM_OF_UNDYING && TotemTweaks.hideParticles(entity)) ci.cancel();
    }
}
