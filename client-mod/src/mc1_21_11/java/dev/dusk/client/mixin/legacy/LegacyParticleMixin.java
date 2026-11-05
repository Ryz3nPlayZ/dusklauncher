package dev.dusk.client.mixin.legacy;

import dev.dusk.client.modules.render.LegacyCombat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Legacy Combat: no sweep or damage-heart particles; both arrived in 1.9. */
@Mixin(ClientLevel.class)
public class LegacyParticleMixin {
    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void duskclient$hideModernHits(ParticleOptions options, boolean force, boolean decreased,
                                           double x, double y, double z, double xa, double ya, double za,
                                           CallbackInfo ci) {
        ParticleType<?> type = options.getType();
        if ((type == ParticleTypes.SWEEP_ATTACK || type == ParticleTypes.DAMAGE_INDICATOR) && LegacyCombat.oldHits()) {
            ci.cancel();
        }
    }
}
