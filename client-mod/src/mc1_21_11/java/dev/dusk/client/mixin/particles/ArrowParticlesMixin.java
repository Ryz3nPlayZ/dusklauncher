package dev.dusk.client.mixin.particles;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OverflowParticles: Mixin_ApplyMultiplierToArrows (the critical-arrow trail). */
@Mixin(AbstractArrow.class)
public class ArrowParticlesMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=4", ordinal = 0))
    private int duskclient$count(int constant) {
        return ParticleHooks.scaleCount(ParticleHooks.of(ParticleTypes.CRIT), constant);
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "doubleValue=4.0D"))
    private double duskclient$spacing(double constant) {
        Particles.Entry crit = ParticleHooks.of(ParticleTypes.CRIT);
        if (crit == null || crit.multiplierValue() == 1f) return constant;
        return (int) (constant * crit.multiplierValue());
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void duskclient$multiplied(CallbackInfo ci) {
        if (Particles.active()) ParticleHooks.multiplied = true;
    }
}
