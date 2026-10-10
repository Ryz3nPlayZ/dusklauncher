package dev.dusk.client.render.highlight;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.mixin.RenderPipelinesAccessor;
import dev.dusk.client.mixin.RenderTypeInvoker;
import dev.dusk.client.modules.render.BlockHighlight.Depth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Block Highlight's draws on 26.2+. In the frame's submit pass the batches go
 * into the feature renderer's buffers; otherwise (after the level) they're
 * staged, uploaded once and drawn together on flush.
 */
public final class HighlightGpu {
    private record Queued(StagedVertexBuffer.Draw draw, RenderPipeline pipeline) {}

    private static final List<Queued> queued = new ArrayList<>();
    private static final RenderType[] types = new RenderType[Depth.values().length * 2];
    private static StagedVertexBuffer staged;
    private static StagedVertexBuffer.Draw current;
    private static boolean appended;
    private static Function<RenderType, VertexConsumer> sink;
    private static RenderPipeline linesAlways, linesHidden, fillAlways, fillHidden;
    private static boolean made, failed;

    private HighlightGpu() {}

    /** Whether the submit pass can take the draw this frame. */
    public static boolean featurePhaseUsable() {
        return true;
    }

    /** Draws the highlight into the feature renderer's buffers. */
    public static void drawInto(Function<RenderType, VertexConsumer> into) {
        sink = into;
        try {
            HighlightEngine.drawFeature();
        } finally {
            sink = null;
        }
    }

    public static VertexConsumer begin(boolean lines, Depth depth) {
        if (sink != null) return sink.apply(renderType(lines, depth));
        if (staged == null) staged = new StagedVertexBuffer(() -> "Dusk block highlight", RenderType.SMALL_BUFFER_SIZE);
        current = lines
                ? staged.appendDraw(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, PrimitiveTopology.LINES)
                : staged.appendDraw(DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS, RenderSystem.getProjectionType().vertexSorting());
        appended = true;
        return staged.getVertexBuilder(current);
    }

    public static void end(boolean lines, Depth depth) {
        if (sink != null || current == null) return;
        RenderPipeline pipeline = pipeline(lines, depth);
        if (pipeline != null) queued.add(new Queued(current, pipeline));
        current = null;
    }

    /** Uploads the frame's batches in one go and draws them; the staging buffer wants one upload per frame. */
    public static void flush() {
        if (!appended) return;
        try {
            if (queued.isEmpty()) return;
            staged.upload();
            RenderTarget target = Compat.mainTarget(Minecraft.getInstance());
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null
                    ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            if (color == null) return;
            GpuTextureView depthView = !target.useDepth ? null : RenderSystem.outputDepthTextureOverride != null
                    ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                    .writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            for (Queued q : queued) {
                StagedVertexBuffer.ExecuteInfo info = staged.getExecuteInfo(q.draw());
                if (info == null) continue;
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(() -> "Dusk block highlight", color, Optional.empty(), depthView, OptionalDouble.empty())) {
                    pass.setPipeline(q.pipeline());
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transform);
                    pass.setVertexBuffer(0, info.vertexBuffer().slice());
                    pass.setIndexBuffer(info.indexBuffer(), info.indexType());
                    pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
                }
            }
        } finally {
            queued.clear();
            appended = false;
            staged.endFrame();
        }
    }

    private static RenderType renderType(boolean lines, Depth depth) {
        if (depth == Depth.NORMAL) return lines ? RenderTypes.lines() : RenderTypes.debugQuads();
        int i = depth.ordinal() * 2 + (lines ? 1 : 0);
        if (types[i] == null) {
            RenderPipeline pipeline = pipeline(lines, depth);
            if (pipeline == null) return lines ? RenderTypes.lines() : RenderTypes.debugQuads();
            RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline);
            if (!lines) setup.sortOnUpload();
            types[i] = RenderTypeInvoker.duskclient$create("duskclient_highlight_" + (lines ? "lines_" : "fill_")
                    + depth.name().toLowerCase(Locale.ROOT), setup.createRenderSetup());
        }
        return types[i];
    }

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
