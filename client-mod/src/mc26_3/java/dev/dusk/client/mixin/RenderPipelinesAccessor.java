package dev.dusk.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The GUI, line and filled-debug pipeline snippets and the pipeline registry
 * are private; the crosshair registers its inverting-blend variant from them,
 * and Block Highlight its through-walls and hidden-only variants.
 */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Accessor("GUI_SNIPPET")
    static RenderPipeline.Snippet duskclient$guiSnippet() {
        throw new AssertionError();
    }

    @Accessor("LINES_SNIPPET")
    static RenderPipeline.Snippet duskclient$linesSnippet() {
        throw new AssertionError();
    }

    @Accessor("DEBUG_FILLED_SNIPPET")
    static RenderPipeline.Snippet duskclient$debugFilledSnippet() {
        throw new AssertionError();
    }

    @Invoker("register")
    static RenderPipeline duskclient$register(RenderPipeline pipeline) {
        throw new AssertionError();
    }
}
