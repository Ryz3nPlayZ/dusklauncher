package dev.dusk.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A square texture the CPU writes pixels into and uploads when they change
 * (one tile of the minimap), smoothed when drawn smaller than it is. Render
 * thread only. Drawn with {@code Canvas.blit(texture.id(), ...)}.
 */
public final class MapTexture {
    private final String id;
    private final NativeImage image;
    private final DynamicTexture texture;

    public MapTexture(String id, int size) {
        this.id = id;
        image = new NativeImage(size, size, true);
        texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        Minecraft.getInstance().getTextureManager().register(ResourceLocation.parse(id), texture);
    }

    /** {@code argb} as everywhere else in the mod; 0 is see-through. */
    public void set(int x, int y, int argb) {
        // this version takes ABGR
        image.setPixelRGBA(x, y, (argb & 0xFF00FF00) | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16);
    }

    public void upload() {
        texture.upload();
    }

    public String id() {
        return id;
    }

    public void close() {
        Minecraft.getInstance().getTextureManager().release(ResourceLocation.parse(id));
    }
}
