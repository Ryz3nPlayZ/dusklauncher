package dev.dusk.client.mixin.particles;

import dev.dusk.client.render.particles.ParticleHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OverflowParticles: Mixin_DealWithBlockParticles (server-sent landing dust)
 * and the invulnerability check. Modern servers send the fall-landing burst
 * as BLOCK particles, so those are hidden along with falling dust. Injections
 * sit after the main-thread hop so the handlers run once, on the client thread.
 */
@Mixin(ClientPacketListener.class)
public class ParticlePacketMixin {
    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"),
            cancellable = true)
    private void duskclient$landingDust(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        var type = packet.getParticle().getType();
        if ((type == ParticleTypes.FALLING_DUST || type == ParticleTypes.BLOCK) && ParticleHooks.hideRunning(null)) ci.cancel();
    }

    @Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
    private void duskclient$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level != null) ParticleHooks.onHitConfirmed(packet.getEntity(level));
    }

    @Inject(method = "handleDamageEvent", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
    private void duskclient$damageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level != null) ParticleHooks.onHitConfirmed(level.getEntity(packet.entityId()));
    }
}
