package dev.dusk.client.mixin.particles;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.Particles;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** OverflowParticles: Mixin_StaticParticleColor. 26.1+ renamed getLightColor to getLightCoords (see its layer). */
@Mixin(SingleQuadParticle.class)
public class ParticleLightMixin {
    private static final int FULL_BRIGHT = 15728880;

    @WrapOperation(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;getLightColor(F)I"))
    private int duskclient$staticColor(SingleQuadParticle instance, float partialTicks, Operation<Integer> original) {
        return Particles.active() && Particles.instance().staticColor.get() ? FULL_BRIGHT : original.call(instance, partialTicks);
    }
}
