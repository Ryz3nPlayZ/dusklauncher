package dev.dusk.client.mixin;

import dev.dusk.client.hud.TpsTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the TPS readout from the server's time updates. */
@Mixin(ClientPacketListener.class)
public class TpsMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void duskclient$trackTps(ClientboundSetTimePacket packet, CallbackInfo ci) {
        TpsTracker.onServerTick(packet.gameTime());
    }
}
