package dev.dusk.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.dusk.client.mixin.RenderPipelinesAccessor;
import dev.dusk.client.modules.render.CrosshairIndicator;
import dev.dusk.client.modules.render.CustomCrosshair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;

import java.util.function.Consumer;

/**
 * Draws the crosshair grid in place of the vanilla sprite, on the spot
 * vanilla's sits. Pixels go through the same inverting blend
 * vanilla uses, so the crosshair stays visible on any background.
 */
public final class CrosshairRenderer {
    private static RenderPipeline pipeline;
    private static boolean pipelineFailed;

    private CrosshairRenderer() {}

    public static void render(GuiGraphics graphics) {
        CustomCrosshair crosshair = CustomCrosshair.instance();
        if (crosshair == null) return;
        draw(graphics, crosshair.scaleFactor(), !crosshair.disableBlending(), crosshair::forEachPixel);
    }

    /** Crosshair Indicator's brackets, over whichever crosshair was just drawn. */
    public static void indicator(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        CrosshairIndicator indicator = CrosshairIndicator.active(mc);
        if (indicator == null) return;
        draw(graphics, CrosshairIndicator.scaleFactor(mc), indicator.invert(), indicator::forEachPixel);
    }

    private static void draw(GuiGraphics graphics, float scale, boolean invert, Consumer<CustomCrosshair.PixelSink> pixels) {
        RenderPipeline blend = invert ? pipeline() : null;
        Matrix3x2fStack matrices = graphics.pose();
        matrices.pushMatrix();
        matrices.translate(CustomCrosshair.centre(graphics.guiWidth()), CustomCrosshair.centre(graphics.guiHeight()));
        matrices.scale(scale, scale);
        matrices.translate(-CustomCrosshair.SIZE / 2.0f, -CustomCrosshair.SIZE / 2.0f);

        pixels.accept((x, y, argb) -> {
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
