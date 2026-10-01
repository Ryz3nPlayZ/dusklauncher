package dev.dusk.client.mixin.skinlayers;

import com.mojang.blaze3d.platform.NativeImage;
import dev.dusk.client.render.skin.SkinPixels;
import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a downloaded skin's alpha, since the image is freed once it is on the GPU. */
@Mixin(HttpTexture.class)
public class HttpTextureMixin implements SkinPixels.Holder {
    @Unique
    private volatile int[] duskclient$alpha;

    @Inject(method = "loadCallback", at = @At("HEAD"))
    private void duskclient$keepAlpha(NativeImage image, CallbackInfo ci) {
        duskclient$alpha = SkinPixels.alphaOf(image);
    }

    @Override
    public int[] duskclient$skinAlpha() {
        return duskclient$alpha;
    }
}
