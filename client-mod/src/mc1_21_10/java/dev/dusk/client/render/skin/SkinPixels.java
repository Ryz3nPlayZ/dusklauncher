package dev.dusk.client.render.skin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;

/** A skin texture's alpha for {@link SkinVoxelCache}: downloaded skins keep their pixels, bundled ones are read from the pack. */
public final class SkinPixels {
    private SkinPixels() {}

    public static int[] alpha(Object id) {
        ResourceLocation texture = (ResourceLocation) id;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getTextureManager().getTexture(texture) instanceof DynamicTexture dynamic) {
            NativeImage image = dynamic.getPixels();
            return image == null ? SkinVoxelCache.UNREADABLE : alphaOf(image);
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
        return SkinVoxelCache.alphaOf(image.getWidth(), image.getHeight(), image::getPixel);
    }
}
