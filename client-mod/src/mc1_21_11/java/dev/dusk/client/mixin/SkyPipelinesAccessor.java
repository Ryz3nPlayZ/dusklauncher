package dev.dusk.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The snippet vanilla's sun and moon pipeline is built on, for the custom sky's per-blend copies. */
@Mixin(RenderPipelines.class)
public interface SkyPipelinesAccessor {
    @Accessor("MATRICES_PROJECTION_SNIPPET")
    static RenderPipeline.Snippet duskclient$skySnippet() {
        throw new AssertionError();
    }
}
