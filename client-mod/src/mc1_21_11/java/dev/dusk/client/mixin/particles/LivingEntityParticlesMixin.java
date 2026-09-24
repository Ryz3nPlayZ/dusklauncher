package dev.dusk.client.mixin.particles;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OverflowParticles: Mixin_CleanView and Mixin_MultiplyDeathParticles. */
@Mixin(LivingEntity.class)
public class LivingEntityParticlesMixin {
    @WrapWithCondition(method = "tickEffects", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private boolean duskclient$cleanView(Level level, ParticleOptions particle,
                                         double x, double y, double z, double xs, double ys, double zs) {
        if (!Particles.active() || !Particles.instance().cleanView.get()) return true;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson() || (Object) this != mc.player) return true;
        return !(particle.getType() == ParticleTypes.ENTITY_EFFECT || particle instanceof ColorParticleOption);
    }

    @ModifyExpressionValue(method = "makePoofParticles", at = @At(value = "CONSTANT", args = "intValue=20"))
    private int duskclient$poofMultiplier(int constant) {
        return ParticleHooks.scaleCount(ParticleHooks.of(ParticleTypes.EXPLOSION), constant);
    }

    @Inject(method = "makePoofParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void duskclient$poofMultiplied(CallbackInfo ci) {
        if (Particles.active()) ParticleHooks.multiplied = true;
    }
}
