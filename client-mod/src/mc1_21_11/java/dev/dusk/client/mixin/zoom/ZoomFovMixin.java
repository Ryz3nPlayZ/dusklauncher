package dev.dusk.client.mixin.zoom;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.Zoom;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Narrows the world FOV while zooming; the held item keeps its own FOV. */
@Mixin(GameRenderer.class)
public class ZoomFovMixin {
    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 0))
    private float dusk$zoomFov(float original) {
        Zoom m = Zoom.active();
        return m == null ? original : (float) (original / m.divisor());
    }
}
