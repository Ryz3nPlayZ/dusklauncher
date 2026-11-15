package dev.dusk.client.mixin;

import dev.dusk.client.render.ParticleAge;
import net.minecraft.client.particle.EntityFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The particle's life counters and scale, for the Particles module's fade and glow. */
@Mixin(EntityFX.class)
public abstract class EntityFXMixin implements ParticleAge {
    @Shadow
    protected int particleAge;

    @Shadow
    protected int particleMaxAge;

    @Shadow
    protected float particleScale;

    @Override
    public float dusk$scale() {
        return particleScale;
    }

    @Override
    public int dusk$age() {
        return particleAge;
    }

    @Override
    public int dusk$maxAge() {
        return particleMaxAge;
    }
}
