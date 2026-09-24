package dev.dusk.client.mixin.particles;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OverflowParticles: Mixin_TrackRenderState, Mixin_ParticleFading,
 * Mixin_ApplyCustomParticleColors and Mixin_ParticleScaling. The colour and
 * size reads only occur in the six-argument extractRotatedQuad overload. The
 * full descriptor is required: a bare name matches both overloads in dev, but
 * Loom remaps it to just one intermediary name (the four-argument one), which
 * fails the required injection and crashes a production launch.
 */
@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin {
    @Shadow protected float alpha;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void duskclient$trackAndFade(CallbackInfo ci) {
        Particle self = (Particle) (Object) this;
        Particles.Entry entry = ParticleHooks.of(self);
        if (entry == null) return;
        if (!entry.enabled.get() && !entry.isBlocks()) {
            ci.cancel();
            return;
        }
        this.alpha = ParticleHooks.fadedAlpha(entry, ((ParticleAccessor) self).duskclient$age(), self.getLifetime(), this.alpha);
    }

    @ModifyExpressionValue(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/particle/SingleQuadParticle;rCol:F"))
    private float duskclient$red(float original) {
        return duskclient$adjust(original, 16);
    }

    @ModifyExpressionValue(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/particle/SingleQuadParticle;gCol:F"))
    private float duskclient$green(float original) {
        return duskclient$adjust(original, 8);
    }

    @ModifyExpressionValue(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/particle/SingleQuadParticle;bCol:F"))
    private float duskclient$blue(float original) {
        return duskclient$adjust(original, 0);
    }

    @ModifyExpressionValue(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/particle/SingleQuadParticle;alpha:F"))
    private float duskclient$alpha(float original) {
        return duskclient$adjust(original, 24);
    }

    @ModifyExpressionValue(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;getQuadSize(F)F"))
    private float duskclient$scale(float original) {
        Particles.Entry entry = ParticleHooks.of((Particle) (Object) this);
        return entry == null ? original : original * entry.sizeScale();
    }

    @Unique
    private float duskclient$adjust(float original, int shift) {
        Particles.Entry entry = ParticleHooks.of((Particle) (Object) this);
        if (entry == null) return original;
        return entry.color((entry.argb() >>> shift) & 0xFF, original);
    }
}
