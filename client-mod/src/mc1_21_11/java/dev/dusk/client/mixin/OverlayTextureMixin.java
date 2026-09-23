package dev.dusk.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.dusk.client.render.OverlayTint;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DamageTint: repaints the red half of the overlay sheet. Every column gets
 * its own colour so the entity renderer can pick one by damage type, and with
 * fading on, each row down the sheet is a step more transparent.
 */
@Mixin(OverlayTexture.class)
public class OverlayTextureMixin implements OverlayTint.Sink {
    @Shadow @Final private DynamicTexture texture;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void duskclient$bind(CallbackInfo ci) {
        OverlayTint.bind(this);
    }

    @Override
    @Unique
    public void duskclient$setOverlayColors(int[] argbByColumn, boolean fade) {
        NativeImage image = this.texture.getPixels();
        if (image == null) return;

        int rows = image.getHeight() / 2;
        for (int x = 0; x < image.getWidth(); x++) {
            int argb = argbByColumn[Math.min(x, argbByColumn.length - 1)];
            int r = argb >> 16 & 0xFF;
            int g = argb >> 8 & 0xFF;
            int b = argb & 0xFF;
            // The stored alpha is how much of the entity shows through, so the
            // pixel's own alpha is its complement.
            int a = 255 - (argb >>> 24);

            for (int y = 0; y < rows; y++) {
                float percent = fade ? 1.0f - ((float) y / (rows - 1)) : 1.0f;
                int alpha = (int) (255 - ((255 - a) * percent));
                image.setPixel(x, y, alpha << 24 | r << 16 | g << 8 | b);
            }
        }

        this.texture.upload();
    }
}
