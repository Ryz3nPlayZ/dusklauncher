package dev.dusk.client.mixin.qol;

import dev.dusk.client.modules.hud.ItemPickups;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link ItemPickups}: the server's "player took this item" packet. Read at
 * HEAD, while the item entity still holds its stack (the handler shrinks or
 * removes it); the network thread's first pass only hands the packet over,
 * so it is skipped.
 */
@Mixin(ClientPacketListener.class)
public abstract class ItemPickupMixin {
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void dusk$itemPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || ItemPickups.active() == null || mc.player == null || mc.level == null) return;
        if (packet.getPlayerId() != mc.player.getId()) return;
        Entity entity = mc.level.getEntity(packet.getItemId());
        if (entity instanceof ItemEntity item) ItemPickups.picked(item.getItem(), packet.getAmount());
    }
}
