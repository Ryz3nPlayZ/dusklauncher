package dev.dusk.client.render.sky;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.mixin.RenderPipelinesAccessor;
import dev.dusk.client.mixin.SkyPipelinesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Draws a custom sky layer: vanilla's sun-and-moon pipeline with the layer's blend, on a sky cube built once. */
public final class SkyGpu {
    private static final RenderPipeline[] pipelines = new RenderPipeline[CustomSkyEngine.BLEND_COUNT];
    private static boolean failed;
    private static GpuBuffer cube;

    private SkyGpu() {}

    public static void draw(Identifier texture, int blend, Matrix4f modelView, Vector4f color) {
        RenderPipeline pipeline = pipeline(blend);
        GpuBuffer vertices = cube();
        if (pipeline == null || vertices == null) return;
        Minecraft mc = Minecraft.getInstance();
        AbstractTexture tex = mc.getTextureManager().getTexture(texture);
        RenderTarget target = Compat.mainTarget(mc);
        GpuTextureView colorView = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        GpuTextureView depthView = !target.useDepth ? null : RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView, color, new Vector3f(), new Matrix4f());
        RenderSystem.AutoStorageIndexBuffer quads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indices = quads.getBuffer(CustomSkyEngine.CUBE_INDICES);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Dusk custom sky", colorView, OptionalInt.empty(), depthView, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.bindTexture("Sampler0", tex.getTextureView(), tex.getSampler());
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, quads.type());
            pass.drawIndexed(0, 0, CustomSkyEngine.CUBE_INDICES, 1);
        }
    }

    private static GpuBuffer cube() {
        if (cube != null) return cube;
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX.getVertexSize() * CustomSkyEngine.CUBE_VERTICES)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
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
                    .withLocation("pipeline/duskclient_sky_" + CustomSkyEngine.blendName(blend))
                    .withVertexShader("core/position_tex")
                    .withFragmentShader("core/position_tex")
                    .withSampler("Sampler0")
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS);
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
            case CustomSkyEngine.ALPHA -> new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
            case CustomSkyEngine.SUBTRACT -> new BlendFunction(SourceFactor.ONE_MINUS_DST_COLOR, DestFactor.ZERO);
            case CustomSkyEngine.MULTIPLY -> new BlendFunction(SourceFactor.DST_COLOR, DestFactor.ONE_MINUS_SRC_ALPHA);
            case CustomSkyEngine.DODGE -> new BlendFunction(SourceFactor.ONE, DestFactor.ONE);
            case CustomSkyEngine.BURN -> new BlendFunction(SourceFactor.ZERO, DestFactor.ONE_MINUS_SRC_COLOR);
            case CustomSkyEngine.SCREEN -> new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_COLOR);
            case CustomSkyEngine.OVERLAY -> new BlendFunction(SourceFactor.DST_COLOR, DestFactor.SRC_COLOR);
            case CustomSkyEngine.REPLACE -> null;
            default -> new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE);
        };
    }
}
