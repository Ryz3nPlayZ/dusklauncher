package dev.dusk.client.mixin.totem;

import dev.dusk.client.modules.hud.TotemCounter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Totem Counter: every player's totem pops, from the server's entity events (client thread only). */
@Mixin(ClientPacketListener.class)
public abstract class TotemPopCountMixin {
    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void duskclient$countPop(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        TotemCounter.onEntityEvent(packet);
    }
}
