package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.Particles;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import dev.dusk.client.render.ParticleGlow;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every enum-particle spawn passes through here, so the Particles module
 * gates it (hidden, multiplier's missing share, clean view) and styles what
 * lands (size, colour, no-clip, brightness, fade). Extra copies for
 * multipliers above 1x spawn through a recursive call, guarded so they don't
 * multiply themselves.
 */
@Mixin(EffectRenderer.class)
public abstract class EffectRendererMixin {
    @Unique
    private int dusk$spawning;

    @Inject(method = "spawnEffectParticle", at = @At("HEAD"), cancellable = true, require = 0)
    private void dusk$gate(int particleId, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed,
                           int[] args, CallbackInfoReturnable<EntityFX> cir) {
        if (dusk$spawning > 0) return;
        if (Particles.gate(particleId, x, y, z) == Particles.CANCEL) cir.setReturnValue(null);
    }

    /** After every particle is drawn: the glow halos on top. */
    @Inject(method = "renderParticles", at = @At("RETURN"), require = 0)
    private void dusk$glow(Entity entity, float partialTicks, CallbackInfo ci) {
        ParticleGlow.render(partialTicks);
    }

    @Inject(method = "spawnEffectParticle", at = @At("RETURN"), require = 0)
    private void dusk$style(int particleId, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed,
                            int[] args, CallbackInfoReturnable<EntityFX> cir) {
        EntityFX fx = cir.getReturnValue();
        if (fx == null) return;
        Particles.apply(fx, particleId);
        if (dusk$spawning > 0) return;
        int extra = Particles.extraCount(particleId);
        if (extra <= 0) return;
        dusk$spawning = 1;
        try {
            EffectRenderer self = (EffectRenderer) (Object) this;
            for (int i = 0; i < extra; i++) {
                self.spawnEffectParticle(particleId, x, y, z, xSpeed, ySpeed, zSpeed, args);
            }
        } finally {
            dusk$spawning = 0;
        }
    }
}
