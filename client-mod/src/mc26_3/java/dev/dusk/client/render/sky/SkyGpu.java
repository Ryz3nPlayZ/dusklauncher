package dev.dusk.client.render.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.dusk.client.mixin.RenderPipelinesAccessor;
import dev.dusk.client.mixin.SkyPipelinesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Draws a custom sky layer: vanilla's sun-and-moon pipeline with the layer's
 * blend, on a sky cube built once. 26.3 draws the whole sky in one render
 * pass, so the layers go into vanilla's.
 */
public final class SkyGpu {
    private static final RenderPipeline[] pipelines = new RenderPipeline[CustomSkyEngine.BLEND_COUNT];
    private static boolean failed;
    private static GpuBuffer cube;

    private SkyGpu() {}

    public static void draw(RenderPass pass, Identifier texture, int blend, Matrix4f modelView, Vector4f color) {
        RenderPipeline pipeline = pipeline(blend);
        GpuBuffer vertices = cube();
        if (pipeline == null || vertices == null) return;
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indices = quads.getBuffer(CustomSkyEngine.CUBE_INDICES);
        pass.pushDebugGroup(() -> "Dusk custom sky");
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(modelView, color));
        pass.setUniform("Sampler0", tex.getTextureView(), tex.getSampler());
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, quads.type());
        pass.drawIndexed(CustomSkyEngine.CUBE_INDICES, 1, 0, 0, 0);
        pass.popDebugGroup();
    }

    private static GpuBuffer cube() {
        if (cube != null) return cube;
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX.getVertexSize() * CustomSkyEngine.CUBE_VERTICES)) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX);
            CustomSkyEngine.cube((x, y, z, u, v) -> builder.addVertex(x, y, z).setUv(u, v));
            try (MeshData mesh = builder.buildOrThrow()) {
                cube = RenderSystem.getDevice().createBuffer(() -> "Dusk custom sky cube", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
        return cube;
    }

    private static RenderPipeline pipeline(int blend) {
        if (pipelines[blend] != null || failed) return pipelines[blend];
        try {
            RenderPipeline.Builder builder = RenderPipeline.builder(SkyPipelinesAccessor.duskclient$skySnippet())
                    .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                    .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                    .withLocation("pipeline/duskclient_sky_" + CustomSkyEngine.blendName(blend))
                    .withVertexShader("core/position_tex")
                    .withFragmentShader("core/position_tex")
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                    .withCull(false)
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS);
            BlendFunction function = function(blend);
            if (function != null) builder.withColorTargetState(new ColorTargetState(function));
            pipelines[blend] = RenderPipelinesAccessor.duskclient$register(builder.build());
            CustomSkyEngine.assignIris(pipelines[blend]);
        } catch (Throwable t) {
            failed = true;
            System.err.println("[DuskClient] custom sky pipeline unavailable: " + t);
        }
        return pipelines[blend];
    }

    private static BlendFunction function(int blend) {
        return switch (blend) {
            case CustomSkyEngine.ALPHA -> new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            case CustomSkyEngine.SUBTRACT -> new BlendFunction(BlendFactor.ONE_MINUS_DST_COLOR, BlendFactor.ZERO);
            case CustomSkyEngine.MULTIPLY -> new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ONE_MINUS_SRC_ALPHA);
            case CustomSkyEngine.DODGE -> new BlendFunction(BlendFactor.ONE, BlendFactor.ONE);
            case CustomSkyEngine.BURN -> new BlendFunction(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR);
            case CustomSkyEngine.SCREEN -> new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_COLOR);
            case CustomSkyEngine.OVERLAY -> new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.SRC_COLOR);
            case CustomSkyEngine.REPLACE -> null;
            default -> new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE);
        };
    }
}
