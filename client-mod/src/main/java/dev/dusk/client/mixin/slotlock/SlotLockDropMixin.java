package dev.dusk.client.mixin.slotlock;

import dev.dusk.client.modules.misc.SlotLock;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Q (and Ctrl+Q) does nothing on a locked hotbar slot. */
@Mixin(LocalPlayer.class)
public class SlotLockDropMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void dusk$lockedDrop(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        if (SlotLock.blocksDrop((LocalPlayer) (Object) this)) cir.setReturnValue(false);
    }
}
