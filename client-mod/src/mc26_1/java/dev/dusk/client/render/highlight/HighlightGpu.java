package dev.dusk.client.render.highlight;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.mixin.RenderPipelinesAccessor;
import dev.dusk.client.modules.render.BlockHighlight.Depth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Block Highlight's draws on 26.1: each batch is built, uploaded and drawn straight away. */
public final class HighlightGpu {
    private static ByteBufferBuilder bytes;
    private static BufferBuilder builder;
    private static RenderPipeline linesAlways, linesHidden, fillAlways, fillHidden;
    private static boolean made, failed;

    private HighlightGpu() {}

    public static VertexConsumer begin(boolean lines, Depth depth) {
        if (bytes == null) bytes = new ByteBufferBuilder(786432);
        builder = lines
                ? new BufferBuilder(bytes, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH)
                : new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        return builder;
    }

    public static void end(boolean lines, Depth depth) {
        BufferBuilder b = builder;
        builder = null;
        if (b == null) return;
        try (MeshData mesh = b.build()) {
            RenderPipeline pipeline = pipeline(lines, depth);
            if (mesh == null || pipeline == null) return;
            MeshData.DrawState state = mesh.drawState();
            GpuBuffer vertices = state.format().uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(state.mode());
            GpuBuffer indices = sequential.getBuffer(state.indexCount());
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                    .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            RenderTarget target = Compat.mainTarget(Minecraft.getInstance());
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null
                    ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            GpuTextureView depthView = !target.useDepth ? null : RenderSystem.outputDepthTextureOverride != null
                    ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "Dusk block highlight", color, OptionalInt.empty(), depthView, OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indices, sequential.type());
                pass.drawIndexed(0, 0, state.indexCount(), 1);
            }
        }
    }

    /** Batches are drawn as they end here. */
    public static void flush() {}

    private static RenderPipeline pipeline(boolean lines, Depth depth) {
        return switch (depth) {
            case NORMAL -> lines ? RenderPipelines.LINES : RenderPipelines.DEBUG_QUADS;
            case ALWAYS_PASS -> {
                make();
                yield lines ? linesAlways : fillAlways;
            }
            case HIDDEN_ONLY -> {
                make();
                yield lines ? linesHidden : fillHidden;
            }
        };
    }

    private static void make() {
        if (made || failed) return;
        try {
            linesAlways = build(RenderPipelinesAccessor.duskclient$linesSnippet(), "lines_always", true);
            linesHidden = build(RenderPipelinesAccessor.duskclient$linesSnippet(), "lines_hidden", false);
            fillAlways = build(RenderPipelinesAccessor.duskclient$debugFilledSnippet(), "fill_always", true);
            fillHidden = build(RenderPipelinesAccessor.duskclient$debugFilledSnippet(), "fill_hidden", false);
            made = true;
        } catch (Throwable t) {
            failed = true;
            System.err.println("[DuskClient] block highlight pipelines unavailable: " + t);
        }
    }

    /** Through blocks, or only where something is in front. */
    private static RenderPipeline build(RenderPipeline.Snippet snippet, String name, boolean alwaysPass) {
        return RenderPipelinesAccessor.duskclient$register(RenderPipeline.builder(snippet)
                .withLocation("pipeline/duskclient_highlight_" + name)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(alwaysPass ? CompareOp.ALWAYS_PASS : CompareOp.LESS_THAN, true))
                .build());
    }
}
