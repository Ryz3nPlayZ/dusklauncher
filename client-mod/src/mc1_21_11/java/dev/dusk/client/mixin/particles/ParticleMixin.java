package dev.dusk.client.mixin.particles;

import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.render.particles.ParticleTypeHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OverflowParticles: Mixin_ParticleIdentifier + Mixin_ParticleNoClip. */
@Mixin(Particle.class)
public abstract class ParticleMixin implements ParticleTypeHolder {
    @Shadow @Final protected ClientLevel level;
    @Unique private ParticleType<?> duskclient$type;

    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract void setBoundingBox(AABB bb);
    @Shadow protected abstract void setLocationFromBoundingbox();

    @Override
    public ParticleType<?> duskclient$type() { return duskclient$type; }

    @Override
    public void duskclient$setType(ParticleType<?> type) { this.duskclient$type = type; }

    @Inject(method = "move(DDD)V", at = @At("HEAD"), cancellable = true)
    private void duskclient$noClip(double x, double y, double z, CallbackInfo ci) {
        if (this.level != null && !this.level.isClientSide()) return;
        if (Particles.active() && Particles.instance().noClip.get()) {
            this.setBoundingBox(this.getBoundingBox().move(x, y, z));
            this.setLocationFromBoundingbox();
            ci.cancel();
        }
    }
}
