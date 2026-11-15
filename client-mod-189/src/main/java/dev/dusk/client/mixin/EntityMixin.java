package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.Particles;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particles marked full-bright report maximum light so the render stops
 * darkening them. Everything else, entity or particle, keeps its own value.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getBrightnessForRender", at = @At("RETURN"), cancellable = true)
    private void dusk$fullBright(float partialTicks, CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof EntityFX && Particles.isBright((EntityFX) (Object) this)) {
            cir.setReturnValue(0xF000F0);
        }
    }
}
