package dev.dusk.client.mixin.combo;

import dev.dusk.client.modules.hud.ComboDisplay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combo Counter: the server's damage and death events. RETURN is only reached
 * on the client thread; the network thread's pass hands the packet over first.
 */
@Mixin(ClientPacketListener.class)
public abstract class ComboPacketMixin {
    @Inject(method = "handleDamageEvent", at = @At("RETURN"))
    private void dusk$comboDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        ComboDisplay.onDamageEvent(packet);
    }

    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void dusk$comboEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        ComboDisplay.onEntityEvent(packet);
    }
}
