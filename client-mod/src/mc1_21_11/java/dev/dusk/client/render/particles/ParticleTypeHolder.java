package dev.dusk.client.render.particles;

import net.minecraft.core.particles.ParticleType;

/** Duck interface mixed into Particle: which ParticleType spawned it. */
public interface ParticleTypeHolder {
    ParticleType<?> duskclient$type();

    void duskclient$setType(ParticleType<?> type);
}
