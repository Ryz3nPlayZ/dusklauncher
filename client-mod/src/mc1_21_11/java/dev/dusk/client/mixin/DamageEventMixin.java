package dev.dusk.client.mixin;

import dev.dusk.client.render.DamageVariants;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DamageTint: the client is told what hit an entity right before it flashes. */
@Mixin(LivingEntity.class)
public class DamageEventMixin {
    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void duskclient$recordVariant(DamageSource source, CallbackInfo ci) {
        DamageVariants.record((LivingEntity) (Object) this, source);
    }
}
