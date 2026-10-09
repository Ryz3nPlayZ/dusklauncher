package dev.dusk.client.mixin.invtweaks;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.modules.misc.ScrollTransfer;
import dev.dusk.client.modules.misc.ScrollableTooltips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scroll Transfer takes the wheel over a container slot. Hooked here rather
 * than on the screen because 1.21.1's container screens have no scroll
 * method of their own; creative's item tabs keep their scrolling. Ctrl and
 * the wheel over a tooltip too big for the screen scroll it instead
 * ({@link ScrollableTooltips}), on any screen.
 */
@Mixin(MouseHandler.class)
public class ScrollTransferMouseMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void dusk$scrollTransfer(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (Compat.currentScreen(minecraft) != null && ScrollableTooltips.scroll(horizontal, vertical)) {
            ci.cancel();
            return;
        }
        if (vertical == 0 || ScrollTransfer.active() == null) return;
        Screen screen = Compat.currentScreen(minecraft);
        if (!(screen instanceof AbstractContainerScreen<?> container) || screen instanceof CreativeModeInventoryScreen) return;
        if (ScrollTransfer.scroll(((ContainerScreenAccessor) container).duskclient$hoveredSlot(), container.getMenu(), vertical)) ci.cancel();
    }
}
