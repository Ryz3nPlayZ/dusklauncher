package dev.dusk.client.mixin.slotlock;

import dev.dusk.client.modules.misc.SlotLock;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Q (and Ctrl+Q) does nothing on a locked hotbar slot. 26.3 drops through the game mode, not the player. */
@Mixin(MultiPlayerGameMode.class)
public class SlotLockGameModeMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void dusk$lockedDrop(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (SlotLock.blocksDrop(player)) ci.cancel();
    }
}
