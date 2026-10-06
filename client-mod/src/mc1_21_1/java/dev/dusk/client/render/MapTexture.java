package dev.dusk.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A square texture the CPU writes pixels into and uploads when they change
 * (the minimap). Render thread only. Drawn with
 * {@code Canvas.blit(MapTexture.ID, ...)}.
 */
public final class MapTexture {
    public static final String ID = "duskclient:minimap";

    private final NativeImage image;
    private final DynamicTexture texture;

    public MapTexture(int size) {
        image = new NativeImage(size, size, true);
        ResourceLocation id = ResourceLocation.parse(ID);
        texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    /** {@code argb} as everywhere else in the mod; 0 is see-through. */
    public void set(int x, int y, int argb) {
        // this version takes ABGR
        image.setPixelRGBA(x, y, (argb & 0xFF00FF00) | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16);
    }

    public void upload() {
        texture.upload();
    }

    public void close() {
        Minecraft.getInstance().getTextureManager().release(ResourceLocation.parse(ID));
    }
}
