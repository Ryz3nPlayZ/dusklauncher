package dev.dusk.client.mixin.particles;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.TrackingEmitter;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OverflowParticles: Mixin_CleanEmitterView and Mixin_ApplyMultiplierToEmitters. */
@Mixin(TrackingEmitter.class)
public class TrackingEmitterMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final private ParticleOptions particleType;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void duskclient$cleanView(CallbackInfo ci) {
        if (Particles.active() && Particles.instance().cleanView.get() && this.entity == Minecraft.getInstance().player) {
            ((TrackingEmitter) (Object) this).remove();
            ci.cancel();
        }
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=16"))
    private int duskclient$multiplier(int constant) {
        return ParticleHooks.scaleCount(ParticleHooks.of(this.particleType.getType()), constant);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void duskclient$multiplied(CallbackInfo ci) {
        if (Particles.active()) ParticleHooks.multiplied = true;
    }
}
