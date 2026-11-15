package dev.dusk.client.render;

/**
 * A particle's life, added to every EntityFX by mixin so the Particles module
 * can fade particles out and size their glow without subclassing them.
 */
public interface ParticleAge {
    int dusk$age();
    int dusk$maxAge();
    /** The sprite's scale; the quad's half-width is a tenth of it. */
    float dusk$scale();
}
