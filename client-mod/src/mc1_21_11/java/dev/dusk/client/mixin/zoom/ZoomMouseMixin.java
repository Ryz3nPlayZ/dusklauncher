package dev.dusk.client.mixin.zoom;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.modules.render.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While zoomed: the wheel changes the zoom instead of the hotbar slot, and the mouse slows down. */
@Mixin(MouseHandler.class)
public class ZoomMouseMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void dusk$zoomScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        Zoom m = Zoom.active();
        if (m == null || Compat.currentScreen(minecraft) != null || minecraft.player == null) return;
        if (m.scroll(vertical)) ci.cancel();
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void dusk$zoomSensitivity(double elapsed, CallbackInfo ci) {
        Zoom m = Zoom.active();
        if (m == null) return;
        double scale = m.mouseScale();
        accumulatedDX *= scale;
        accumulatedDY *= scale;
    }
}
