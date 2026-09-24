package dev.dusk.client.mixin.particles;

import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.render.particles.ParticleHooks;
import dev.dusk.client.render.particles.ParticleTypeHolder;
import net.minecraft.client.particle.BreakingItemParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** OverflowParticles: Mixin_SetParticleIds. Tags every particle with its type and drops disabled ones. */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    @Unique private ParticleType<?> duskclient$currentType;

    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"), cancellable = true)
    private void duskclient$captureType(ParticleOptions options, double x, double y, double z, double xs, double ys, double zs,
                                        CallbackInfoReturnable<Particle> cir) {
        duskclient$currentType = options.getType();
        if (!duskclient$shouldSpawn(duskclient$currentType)) cir.setReturnValue(null);
    }

    @ModifyArg(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;add(Lnet/minecraft/client/particle/Particle;)V"))
    private Particle duskclient$setType(Particle particle) {
        ParticleHooks.tag(particle, duskclient$currentType);
        return particle;
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void duskclient$handleRawParticle(Particle particle, CallbackInfo ci) {
        if (particle instanceof BreakingItemParticle) ParticleHooks.tag(particle, ParticleTypes.ITEM);
        // Digging/destroy particles are added directly; tag them so the Blocks fade applies.
        if (particle instanceof TerrainParticle && particle instanceof ParticleTypeHolder h && h.duskclient$type() == null) {
            ParticleHooks.tag(particle, ParticleTypes.BLOCK);
        }
        if (particle instanceof ParticleTypeHolder h && !duskclient$shouldSpawn(h.duskclient$type())) ci.cancel();
    }

    @Unique
    private static boolean duskclient$shouldSpawn(ParticleType<?> type) {
        Particles.Entry entry = ParticleHooks.of(type);
        return entry == null || entry.enabled.get();
    }
}
