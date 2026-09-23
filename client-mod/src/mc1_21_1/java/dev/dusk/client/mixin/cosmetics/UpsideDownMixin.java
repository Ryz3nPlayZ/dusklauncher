package dev.dusk.client.mixin.cosmetics;

import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The "Dinnerbone" effect MinecraftCapes exposes per profile. 1.21–1.21.1
 * flavour: vanilla decides it by name in this one static helper, which both
 * the body flip (setupRotations) and the head-rotation flip (render) consult,
 * so widening it here covers everything the render-state flag does later.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class UpsideDownMixin {
    @Inject(method = "isEntityUpsideDown", at = @At("RETURN"), cancellable = true)
    private static void duskclient$isEntityUpsideDown(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !(entity instanceof AbstractClientPlayer player)) return;
        PlayerCosmetics c = CosmeticsManager.get(player.getUUID(), player.getGameProfile().getName());
        if (c != null && c != PlayerCosmetics.NONE && c.upsideDown()) cir.setReturnValue(true);
    }
}
