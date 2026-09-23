package dev.dusk.client.mixin;

import dev.dusk.client.render.DamageVariants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DamageTint: a critical hit is not a damage type, it is an animation the
 * server sends alongside the hit, so it has to be picked up separately.
 */
@Mixin(ClientPacketListener.class)
public class AnimatePacketMixin {
    @Unique
    private static final int DUSKCLIENT$CRIT_ANIMATION = 4;

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void duskclient$recordCrit(ClientboundAnimatePacket packet, CallbackInfo ci) {
        if (packet.getAction() != DUSKCLIENT$CRIT_ANIMATION) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        Entity entity = minecraft.level.getEntity(packet.getId());
        if (entity instanceof LivingEntity living) DamageVariants.recordCrit(living);
    }
}
