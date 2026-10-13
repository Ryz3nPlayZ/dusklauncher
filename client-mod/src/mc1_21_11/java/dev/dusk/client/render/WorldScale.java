package dev.dusk.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.dusk.client.mixin.scale.LevelRendererOutlineAccessor;
import dev.dusk.client.modules.render.RenderScale;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.jetbrains.annotations.Nullable;

/**
 * The world pass of {@link RenderScale}: while it runs, the game's main
 * render target is a smaller one (the level's own targets size themselves
 * from it), and when it's done the result is stretched onto the real main
 * target before the GUI is drawn there at full size.
 */
public final class WorldScale {
    private WorldScale() {}

    @Nullable private static TextureTarget target;
    private static boolean active;
    /** The entity outline target was sized to {@link #target}, not the window. */
    private static boolean outlineScaled;

    /** The target {@code Minecraft.getMainRenderTarget()} should hand out, or null for the real one. */
    @Nullable public static RenderTarget override() {
        return active ? target : null;
    }

    /** Before GameRenderer.renderLevel. */
    public static void begin() {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        float f = RenderScale.factor();
        if (f >= 0.999f) {
            if (outlineScaled) {
                RenderTarget outline = outline(mc);
                if (outline != null) outline.resize(main.width, main.height);
                outlineScaled = false;
            }
            return;
        }
        int w = Math.max(1, Math.round(main.width * f)), h = Math.max(1, Math.round(main.height * f));
        if (target == null) target = new TextureTarget("Dusk world scale", w, h, true);
        else if (target.width != w || target.height != h) target.resize(w, h);
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
        RenderTarget outline = outline(mc);
        if (outline != null && (outline.width != w || outline.height != h)) {
            outline.resize(w, h);
            outlineScaled = true;
        }
        active = true;
    }

    /** After the world, its outlines and post effects, before the GUI: stretch it onto the window. */
    public static void end() {
        if (!active) return;
        active = false;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        FilterMode filter = RenderScale.sharp() ? FilterMode.NEAREST : FilterMode.LINEAR;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Dusk world scale", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(filter));
            pass.draw(0, 3);
        }
    }

    @Nullable private static RenderTarget outline(Minecraft mc) {
        return ((LevelRendererOutlineAccessor) mc.levelRenderer).duskclient$entityOutlineTarget();
    }
}
