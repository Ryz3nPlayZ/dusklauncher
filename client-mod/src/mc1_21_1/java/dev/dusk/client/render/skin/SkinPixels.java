package dev.dusk.client.render.skin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;

/**
 * A skin texture's alpha for {@link SkinVoxelCache}. Downloaded skins upload
 * and free their image, so the texture mixin keeps a copy of the alpha as it
 * arrives; bundled skins are read from the pack.
 */
public final class SkinPixels {
    private SkinPixels() {}

    /** Implemented on HttpTexture by the mixin. */
    public interface Holder {
        int[] duskclient$skinAlpha();
    }

    public static int[] alpha(Object id) {
        ResourceLocation texture = (ResourceLocation) id;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getTextureManager().getTexture(texture) instanceof HttpTexture http) {
            // null until the download lands
            return ((Holder) http).duskclient$skinAlpha();
        }
        var resource = mc.getResourceManager().getResource(texture);
        if (resource.isEmpty()) return SkinVoxelCache.UNREADABLE;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            return alphaOf(image);
        } catch (IOException e) {
            return SkinVoxelCache.UNREADABLE;
        }
    }

    public static int[] alphaOf(NativeImage image) {
        return SkinVoxelCache.alphaOf(image.getWidth(), image.getHeight(), image::getPixelRGBA);
    }
}
