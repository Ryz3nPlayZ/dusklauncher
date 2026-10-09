package dev.dusk.client.mixin.shield;

import dev.dusk.client.modules.render.ShieldStatuses;
import dev.dusk.client.render.shield.ShieldStateTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shield Statuses: a shield.break sound attributes a disable to someone
 * (WalksyLib ClientPacketListenerMixin). Hooked after the hop to the client
 * thread rather than at HEAD, so the tracker is only touched there.
 */
@Mixin(ClientPacketListener.class)
public abstract class ShieldSoundMixin {
    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void dusk$shieldBreak(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (ShieldStatuses.active() && packet.getSound().getRegisteredName().toLowerCase(java.util.Locale.ROOT).contains("shield.break")) {
            ShieldStateTracker.handleSoundPacket(packet.getX(), packet.getY(), packet.getZ());
        }
    }
}
