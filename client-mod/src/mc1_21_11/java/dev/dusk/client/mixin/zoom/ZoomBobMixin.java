package dev.dusk.client.mixin.zoom;

import dev.dusk.client.modules.render.Zoom;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Shrinks view bobbing by the zoom, so a zoomed view sways as much on
 * screen as an unzoomed one instead of being magnified with it.
 */
@Mixin(GameRenderer.class)
public class ZoomBobMixin {
    @ModifyArgs(method = "bobView", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void dusk$zoomBobShift(Args args) {
        Zoom m = Zoom.active();
        if (m == null) return;
        float s = m.bobScale();
        if (s == 1) return;
        args.set(0, (float) args.get(0) * s);
        args.set(1, (float) args.get(1) * s);
        args.set(2, (float) args.get(2) * s);
    }

    @ModifyArg(method = "bobView", at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;"))
    private float dusk$zoomBobTilt(float degrees) {
        Zoom m = Zoom.active();
        return m == null ? degrees : degrees * m.bobScale();
    }
}
