package dev.dusk.client.media.impl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.OptionalInt;
import net.minecraft.client.renderer.RenderPipelines;
import org.jetbrains.annotations.Nullable;

/**
 * The recorder's frame at the video's size, drawn on the GPU, so a Retina
 * window's frames cross to the encoder at a quarter of the bytes. Linear
 * sampling at half the size is an exact 2x2 average, the common case.
 */
final class FrameScale {
    private static @Nullable TextureTarget target;

    private FrameScale() {}

    /** {@code src} at {@code w} x {@code h}: itself when it is that size already. On the render thread. */
    static RenderTarget to(RenderTarget src, int w, int h) {
        if (src.width == w && src.height == h) return src;
        TextureTarget t = target;
        if (t == null) target = t = new TextureTarget("Dusk recorder scale", w, h, false);
        else if (t.width != w || t.height != h) t.resize(w, h);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Dusk recorder scale", t.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", src.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        return t;
    }

    /** Frees the target. On the render thread. */
    static void release() {
        TextureTarget t = target;
        target = null;
        if (t != null) t.destroyBuffers();
    }
}
