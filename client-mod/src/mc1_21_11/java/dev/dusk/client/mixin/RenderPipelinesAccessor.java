package dev.dusk.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The GUI pipeline snippet and the pipeline registry are private; the
 * crosshair needs both to register its inverting-blend variant.
 */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Accessor("GUI_SNIPPET")
    static RenderPipeline.Snippet duskclient$guiSnippet() {
        throw new AssertionError();
    }

    @Invoker("register")
    static RenderPipeline duskclient$register(RenderPipeline pipeline) {
        throw new AssertionError();
    }
}
