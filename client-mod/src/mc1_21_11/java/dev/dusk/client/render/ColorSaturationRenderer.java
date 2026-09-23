package dev.dusk.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.dusk.client.mixin.PostChainAccessor;
import dev.dusk.client.mixin.PostPassAccessor;
import dev.dusk.client.modules.render.ColorSaturation;
import dev.dusk.client.render.motionblur.ClientRenderTargets;
import dev.dusk.client.render.motionblur.GpuBufferUtil;
import dev.dusk.client.render.motionblur.ManagedUniformBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * ColorSaturation's single pass: the grade runs over the main target once the
 * level is drawn and before the GUI goes on, so the HUD keeps its own colours.
 * The uniform block is four floats, written straight into a buffer we own.
 */
public final class ColorSaturationRenderer {

    private static final String UNIFORM_BLOCK = "SaturationConfig";
    /** Four floats, each 4-byte aligned in std140. */
    private static final int UBO_SIZE = 16;

    private static final ManagedUniformBuffer ubo = new ManagedUniformBuffer(UNIFORM_BLOCK, UBO_SIZE);

    private static PostChain cachedChain = null;
    private static boolean loadErrorLogged = false;

    private ColorSaturationRenderer() {}

    public static void invalidate() {
        ubo.reset();
    }

    public static void render(GraphicsResourceAllocator allocator) {
        if (allocator == null || !ColorSaturation.active()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        PostChain chain = chain(client);
        if (chain == null) return;

        List<PostPass> passes = ((PostChainAccessor) chain).duskclient$passes();
        if (passes.isEmpty()) return;

        Map<String, GpuBuffer> uniformBuffers = ((PostPassAccessor) passes.getFirst()).duskclient$customUniforms();
        if (!uniformBuffers.containsKey(UNIFORM_BLOCK)) return;

        GpuBuffer buffer = ubo.put(chain, uniformBuffers, UNIFORM_BLOCK);

        try {
            // std140 order — must match the GLSL block declaration
            GpuBufferUtil.writeStd140(buffer, UBO_SIZE, b -> {
                b.putFloat(ColorSaturation.saturation());
                b.putFloat(ColorSaturation.contrast());
                b.putFloat(ColorSaturation.brightness());
                b.putFloat(ColorSaturation.hue());
            });

            chain.process(ClientRenderTargets.getMain(client), allocator);
        } catch (RuntimeException e) {
            if (ubo.resetIfClosed(e)) return;
            throw e;
        }
    }

    private static PostChain chain(Minecraft client) {
        try {
            PostChain chain = client.getShaderManager().getPostChain(
                    Identifier.fromNamespaceAndPath("duskclient", "color_saturation"),
                    LevelTargetBundle.MAIN_TARGETS);
            loadErrorLogged = false;
            if (chain != cachedChain) cachedChain = chain;
            return cachedChain;
        } catch (Exception e) {
            cachedChain = null;
            if (!loadErrorLogged) {
                loadErrorLogged = true;
                System.err.println("[DuskClient] failed to load the colour saturation shader: " + e.getMessage());
            }
            return null;
        }
    }
}
