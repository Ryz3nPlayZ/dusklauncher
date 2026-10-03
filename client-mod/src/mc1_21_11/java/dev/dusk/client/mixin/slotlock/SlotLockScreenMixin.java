package dev.dusk.client.mixin.slotlock;

import dev.dusk.client.modules.misc.SlotLock;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SlotLock in container screens: locked slots ignore clicks, get a red frame
 * (drawn in slot space right after the items), and the lock key is polled
 * each frame against the hovered slot.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotLockScreenMixin {
    @Shadow protected Slot hoveredSlot;

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void dusk$lockedClick(Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
        if (SlotLock.blocks(slot, button, type.name())) ci.cancel();
    }

    @Inject(method = "renderContents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderSlotHighlightFront(Lnet/minecraft/client/gui/GuiGraphics;)V"))
    private void dusk$lockFrames(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        SlotLock.poll(hoveredSlot);
        SlotLock m = SlotLock.active();
        if (m == null) return;
        for (Slot s : ((AbstractContainerScreen<?>) (Object) this).getMenu().slots) {
            if (!m.isLocked(s)) continue;
            g.fill(s.x, s.y, s.x + 16, s.y + 16, SlotLock.TINT);
            g.fill(s.x, s.y, s.x + 16, s.y + 1, SlotLock.OUTLINE);
            g.fill(s.x, s.y + 15, s.x + 16, s.y + 16, SlotLock.OUTLINE);
            g.fill(s.x, s.y + 1, s.x + 1, s.y + 15, SlotLock.OUTLINE);
            g.fill(s.x + 15, s.y + 1, s.x + 16, s.y + 15, SlotLock.OUTLINE);
        }
    }
}
