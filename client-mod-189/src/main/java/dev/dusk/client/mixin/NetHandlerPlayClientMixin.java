package dev.dusk.client.mixin;

import dev.dusk.client.modules.hud.ComboDisplay;
import dev.dusk.client.util.TpsTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hits, swings and the server clock, for the combo display and the TPS
 * counter. Each handler is entered twice: first on the network thread, where
 * the game only queues the packet and bails out, then again on the client
 * thread. Only the second one counts — acting on the first would count every
 * packet twice and touch the world from the wrong thread.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientMixin {
    private static boolean dusk$onClientThread() {
        return Minecraft.getMinecraft().isCallingFromMinecraftThread();
    }

    @Inject(method = "handleEntityStatus", at = @At("HEAD"))
    private void dusk$entityStatus(S19PacketEntityStatus packet, CallbackInfo ci) {
        if (!dusk$onClientThread()) return;
        ComboDisplay.onEntityStatus(packet.getEntity(Minecraft.getMinecraft().theWorld), packet.getOpCode());
    }

    @Inject(method = "handleAnimation", at = @At("HEAD"))
    private void dusk$swing(S0BPacketAnimation packet, CallbackInfo ci) {
        if (!dusk$onClientThread()) return;
        ComboDisplay.onSwing(packet.getEntityID());
    }

    @Inject(method = "handleTimeUpdate", at = @At("HEAD"))
    private void dusk$timeUpdate(S03PacketTimeUpdate packet, CallbackInfo ci) {
        if (!dusk$onClientThread()) return;
        TpsTracker.onServerTick(packet.getWorldTime());
    }
}
