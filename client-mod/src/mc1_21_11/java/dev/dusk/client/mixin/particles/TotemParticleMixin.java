package dev.dusk.client.mixin.particles;

import dev.dusk.client.modules.render.TotemTweaks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TotemParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link TotemTweaks}: pop particles resized, sped up or slowed, longer or shorter lived, and recoloured. */
@Mixin(TotemParticle.class)
public abstract class TotemParticleMixin extends SimpleAnimatedParticle {
    private TotemParticleMixin(ClientLevel level, double x, double y, double z, SpriteSet sprites, float gravity) {
        super(level, x, y, z, sprites, gravity);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void duskclient$tweak(CallbackInfo ci) {
        if (!TotemTweaks.tweaksParticles()) return;
        this.quadSize *= TotemTweaks.particleSize();
        this.lifetime = Math.max(1, Math.round(this.lifetime * TotemTweaks.particleLifetime()));
        float speed = TotemTweaks.particleSpeed();
        this.xd *= speed;
        this.yd *= speed;
        this.zd *= speed;
        int colour = TotemTweaks.particleColour(this.random.nextFloat(), this.random.nextFloat());
        if (colour != -1) this.setColor(colour);
    }
}
