package dev.dusk.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.dusk.client.mixin.RenderPipelinesAccessor;
import dev.dusk.client.modules.render.CustomCrosshair;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;

/**
 * Draws the crosshair grid in place of the vanilla sprite, centred on the
 * exact middle of the screen. Pixels go through the same inverting blend
 * vanilla uses, so the crosshair stays visible on any background.
 */
public final class CrosshairRenderer {
    private static RenderPipeline pipeline;
    private static boolean pipelineFailed;

    private CrosshairRenderer() {}

    public static void render(GuiGraphics graphics) {
        CustomCrosshair crosshair = CustomCrosshair.instance();
        if (crosshair == null) return;

        RenderPipeline blend = crosshair.disableBlending() ? null : pipeline();
        Matrix3x2fStack matrices = graphics.pose();
        matrices.pushMatrix();
        matrices.translate(graphics.guiWidth() / 2.0f, graphics.guiHeight() / 2.0f);
        float scale = crosshair.scaleFactor();
        matrices.scale(scale, scale);
        matrices.translate(-CustomCrosshair.SIZE / 2.0f, -CustomCrosshair.SIZE / 2.0f);

        crosshair.forEachPixel((x, y, argb) -> {
            if (blend != null) graphics.fill(blend, x, y, x + 1, y + 1, argb);
            else graphics.fill(x, y, x + 1, y + 1, argb);
        });

        matrices.popMatrix();
    }

    private static RenderPipeline pipeline() {
        if (pipeline != null || pipelineFailed) return pipeline;
        try {
            pipeline = RenderPipelinesAccessor.duskclient$register(
                    RenderPipeline.builder(RenderPipelinesAccessor.duskclient$guiSnippet())
                            .withLocation("pipeline/duskclient_crosshair")
                            .withBlend(BlendFunction.INVERT)
                            .build());
        } catch (Throwable t) {
            // no inverting pipeline: fall back to plain fills
            pipelineFailed = true;
            System.err.println("[DuskClient] crosshair blend pipeline unavailable: " + t);
        }
        return pipeline;
    }
}
