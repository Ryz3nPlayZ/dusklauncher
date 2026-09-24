package dev.dusk.client.mixin.particles;

import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OverflowParticles: Mixin_CustomParticleSpawner (multiplier / disable at the
 * spawn call) and Mixin_CancelBreakingParticles / Mixin_CancelDiggingParticles.
 */
@Mixin(value = ClientLevel.class, priority = 999)
public class ClientLevelParticlesMixin {
    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void duskclient$spawn(ParticleOptions options, boolean ignoreRange, boolean decreased,
                                  double x, double y, double z, double xo, double yo, double zo, CallbackInfo ci) {
        duskclient$spawn(options, ignoreRange, x, y, z, xo, yo, zo, ci);
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void duskclient$spawnNoRange(ParticleOptions options, double x, double y, double z,
                                         double xo, double yo, double zo, CallbackInfo ci) {
        duskclient$spawn(options, false, x, y, z, xo, yo, zo, ci);
    }

    @Unique
    private void duskclient$spawn(ParticleOptions options, boolean ignoreRange, double x, double y, double z,
                                  double xo, double yo, double zo, CallbackInfo ci) {
        if (ParticleHooks.multiplied) {
            ParticleHooks.multiplied = false;
            return;
        }
        Particles.Entry entry = ParticleHooks.of(options.getType());
        if (entry == null) return;
        if (!entry.enabled.get()) {
            ci.cancel();
            return;
        }
        if (entry.multiplierValue() == 1f) return;
        ParticleHooks.spawn(options, entry, (Level) (Object) this, ignoreRange, x, y, z, xo, yo, zo);
        ci.cancel();
    }

    @Inject(method = {"addDestroyBlockEffect", "addBreakingBlockEffect"}, at = @At("HEAD"), cancellable = true)
    private void duskclient$cancelBlockParticles(CallbackInfo ci) {
        if (!Particles.active()) return;
        Particles.Entry blocks = Particles.blocks();
        if (!blocks.enabled.get() || blocks.hideDigging.get()) ci.cancel();
    }
}
