package dev.dusk.client.mixin;

import dev.dusk.client.hud.PingTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the Ping readout from the answers to its ping requests. */
@Mixin(ClientPacketListener.class)
public class PingMixin {
    @Inject(method = "handlePongResponse", at = @At("HEAD"))
    private void duskclient$trackPing(ClientboundPongResponsePacket packet, CallbackInfo ci) {
        PingTracker.onPong(packet.time());
    }
}
