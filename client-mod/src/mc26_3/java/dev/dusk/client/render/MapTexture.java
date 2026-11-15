package dev.dusk.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

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
        texture = new DynamicTexture(() -> id, image) {
            {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        };
        Minecraft.getInstance().getTextureManager().register(Identifier.parse(id), texture);
    }

    /** {@code argb} as everywhere else in the mod; 0 is see-through. */
    public void set(int x, int y, int argb) {
        image.setPixel(x, y, argb);
    }

    public void upload() {
        texture.upload();
    }

    public String id() {
        return id;
    }

    public void close() {
        Minecraft.getInstance().getTextureManager().release(Identifier.parse(id));
    }
}
