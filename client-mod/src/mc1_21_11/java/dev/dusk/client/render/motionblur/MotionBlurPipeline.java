package dev.dusk.client.render.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.dusk.client.mixin.PostChainAccessor;
import dev.dusk.client.mixin.PostPassAccessor;
import dev.dusk.client.modules.render.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * natural-motionblur's ShaderManager: the per-frame driver for every blur
 * algorithm. The level renderer feeds it the frame's matrices and camera
 * delta (see the LevelRenderer mixin), then calls the pre-entity pass, and
 * the post-entity pass runs at the end of the level render. Velocity work
 * goes through the velocity_* post chains; the temporal algorithms are
 * deferred to {@link #applyDeferredTemporalBlur()} after the level is done.
 */
public final class MotionBlurPipeline {

    private static final FrameTimer frameTimer = new FrameTimer();
    private static final CameraState cameraState = new CameraState();
    private static final BlurStrengthCalculator strengthCalc = new BlurStrengthCalculator();

    private static GraphicsResourceAllocator frameAllocator = null;
    private static boolean deferredTemporalBlurApplied = false;

    private static PostChain cachedPreProcessor = null;
    private static PostChain cachedF5Processor = null;
    private static PostChain cachedPostProcessor = null;
    private static final Set<String> loadErrorLogged = new HashSet<>();

    private static final int UBO_SIZE = 304;
    private static final ManagedUniformBuffer preEntityUBO = new ManagedUniformBuffer("PreEntityBlurUniforms", UBO_SIZE);
    private static final ManagedUniformBuffer f5EntityUBO = new ManagedUniformBuffer("PreEntityBlurUniforms", UBO_SIZE);
    private static final ManagedUniformBuffer postRenderUBO = new ManagedUniformBuffer("PostRenderBlurUniforms", UBO_SIZE);

    /** Passes the velocity blur actually runs through the level render. */
    private enum BlurPass { NORMAL_PRE, SPECIAL_F5, NORMAL_POST }

    /** Frames that actually went through a blur pass (gametest hook). */
    private static int blurredFrames;

    private MotionBlurPipeline() {}

    public static void captureAllocator(GraphicsResourceAllocator allocator) { frameAllocator = allocator; }

    public static void clearFrameAllocator() { frameAllocator = null; }

    /** The frame's allocator, for the other post passes that run alongside the blur. */
    public static GraphicsResourceAllocator frameAllocator() { return frameAllocator; }

    public static void beginFrame() {
        frameTimer.beginFrame();
        deferredTemporalBlurApplied = false;
    }

    public static float getCurrentFPS() { return frameTimer.getFPS(); }

    public static int blurredFrames() { return blurredFrames; }

    public static void invalidate() {
        preEntityUBO.reset();
        f5EntityUBO.reset();
        postRenderUBO.reset();
        FrameBlendingManager.invalidate();
    }

    public static void setFrameMotionBlur(Matrix4fc modelView, Matrix4fc prevModelView,
                                          Matrix4fc projection, Matrix4fc prevProjection,
                                          float dx, float dy, float dz) {
        cameraState.setFrame(modelView, prevModelView, projection, prevProjection, dx, dy, dz);
    }

    public static void applyPreEntityBlur()          { if (shouldRun()) applyBlurInternal(BlurPass.NORMAL_PRE, true); }
    public static void applyF5EntityRideBlur()       { if (shouldRun()) applyBlurInternal(BlurPass.SPECIAL_F5, true); }
    public static void applyPostRenderVelocityOnly() { if (shouldRun()) applyBlurInternal(BlurPass.NORMAL_POST, false); }

    public static void applyDeferredTemporalBlur() {
        if (deferredTemporalBlurApplied || frameAllocator == null || !shouldRun()) return;

        MotionBlur config = MotionBlur.instance();
        switch (config.algorithm()) {
            case FRAME_BLENDING, HYBRID_BLENDING -> {
                applyFrameBlendingInternal();
                deferredTemporalBlurApplied = true;
                blurredFrames++;
            }
            case ACCUMULATION_MAX -> {
                FrameBlendingManager.applyAccumulationMax(frameAllocator, config.strength());
                deferredTemporalBlurApplied = true;
                blurredFrames++;
            }
            case ACCUMULATION_MIX -> {
                FrameBlendingManager.applyAccumulationMix(frameAllocator, config.strength());
                deferredTemporalBlurApplied = true;
                blurredFrames++;
            }
            default -> {}
        }
    }

    private static boolean shouldRun() {
        MotionBlur config = MotionBlur.instance();
        return config != null && config.active();
    }

    private static void applyBlurInternal(BlurPass pass, boolean includeTemporal) {
        if (frameAllocator == null) return;

        MotionBlur config = MotionBlur.instance();
        Minecraft client = Minecraft.getInstance();

        // the temporal algorithms do their work after the level render instead
        if (includeTemporal) {
            switch (config.algorithm()) {
                case FRAME_BLENDING, ACCUMULATION_MAX, ACCUMULATION_MIX -> { return; }
                default -> {}
            }
        } else if (!config.usesVelocityBlur()) {
            return;
        }

        BlurStrengthCalculator.Result blur = calculateVelocityBlur(config);
        RenderTarget mainTarget = ClientRenderTargets.getMain(client);
        float viewW = mainTarget.width;
        float viewH = mainTarget.height;
        int algo = config.algorithm().ordinal();

        switch (pass) {
            case NORMAL_PRE -> {
                PostChain p = getPreProcessor(client);
                if (p != null) {
                    writeAndRun(p, "PreEntityBlurUniforms", preEntityUBO, blur.strength(), viewW, viewH, algo, blur.sampleAmount(), client);
                }
            }
            case SPECIAL_F5 -> {
                PostChain p = getF5Processor(client);
                if (p != null) {
                    writeAndRun(p, "PreEntityBlurUniforms", f5EntityUBO, blur.strength(), viewW, viewH, algo, blur.sampleAmount(), client);
                }
            }
            case NORMAL_POST -> {
                PostChain p = getPostProcessor(client);
                if (p != null) {
                    writeAndRun(p, "PostRenderBlurUniforms", postRenderUBO, blur.strength(), viewW, viewH, algo, blur.sampleAmount(), client);
                }
                if (includeTemporal && config.algorithm() == MotionBlur.Algorithm.HYBRID_BLENDING) {
                    applyFrameBlendingInternal();
                }
            }
        }
    }

    private static void applyFrameBlendingInternal() {
        if (frameAllocator == null) return;
        MotionBlur config = MotionBlur.instance();
        FrameBlendingManager.applyFrameBlending(
                frameAllocator,
                frameTimer.getFPS(),
                frameTimer.getRefreshRate(),
                config.strength());
    }

    private static BlurStrengthCalculator.Result calculateVelocityBlur(MotionBlur config) {
        float fps = frameTimer.getFPS();
        int refreshRate = frameTimer.getRefreshRate();

        if (config.algorithm() == MotionBlur.Algorithm.HYBRID_BLENDING) {
            float fillerStrength = FrameBlendingManager.getHybridVelocityStrength(fps, refreshRate, config.strength());
            int sampleAmount = Math.max(100, Math.round(100.0f * fillerStrength));
            return new BlurStrengthCalculator.Result(fillerStrength, sampleAmount);
        }

        return strengthCalc.calculate(
                config.strength(),
                fps,
                refreshRate,
                config.refreshRateScaling() && config.allowsRefreshRateScaling());
    }

    // Shader cache

    private static PostChain getPreProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_pre", "pre-entity");
        if (result == null) { cachedPreProcessor = null; return null; }
        if (result != cachedPreProcessor) cachedPreProcessor = result;
        return cachedPreProcessor;
    }

    private static PostChain getF5Processor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_f5", "F5/entity-riding");
        if (result == null) { cachedF5Processor = null; return null; }
        if (result != cachedF5Processor) cachedF5Processor = result;
        return cachedF5Processor;
    }

    private static PostChain getPostProcessor(Minecraft client) {
        PostChain result = loadProcessor(client, "velocity_post", "post-render");
        if (result == null) { cachedPostProcessor = null; return null; }
        if (result != cachedPostProcessor) cachedPostProcessor = result;
        return cachedPostProcessor;
    }

    private static PostChain loadProcessor(Minecraft client, String shaderName, String displayName) {
        try {
            PostChain chain = client.getShaderManager().getPostChain(
                    Identifier.fromNamespaceAndPath("duskclient", shaderName),
                    LevelTargetBundle.MAIN_TARGETS);
            loadErrorLogged.remove(shaderName);
            return chain;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName)) {
                System.err.println("[DuskClient] failed to load the " + displayName + " blur shader: " + e.getMessage());
            }
            return null;
        }
    }

    // UBO writing

    private static void writeAndRun(PostChain processor, String uboKey, ManagedUniformBuffer managedUBO,
                                    float blendFactor, float viewW, float viewH, int blurAlgorithm,
                                    int sampleAmount, Minecraft client) {
        List<PostPass> passes = ((PostChainAccessor) processor).duskclient$passes();
        if (passes.isEmpty()) return;

        Map<String, GpuBuffer> uniformBuffers = ((PostPassAccessor) passes.getFirst()).duskclient$customUniforms();
        if (!uniformBuffers.containsKey(uboKey)) return;

        // swap the shader loader's placeholder buffer for ours and close the old one
        GpuBuffer ubo = managedUBO.put(processor, uniformBuffers, uboKey);

        try {
            // std140 order — must match the GLSL block declaration
            GpuBufferUtil.writeStd140(ubo, UBO_SIZE, b -> {
                b.putMat4f(cameraState.getMvInverse());
                b.putMat4f(cameraState.getProjInverse());
                b.putMat4f(cameraState.getPrevModelView());
                b.putMat4f(cameraState.getPrevProjection());
                b.putVec3(cameraState.getDx(), cameraState.getDy(), cameraState.getDz());
                b.putVec2(viewW, viewH);
                b.putFloat(blendFactor);
                b.putInt(sampleAmount);
                b.putInt(blurAlgorithm);
                b.putInt(1);
            });

            processor.process(ClientRenderTargets.getMain(client), frameAllocator);
            blurredFrames++;
        } catch (RuntimeException e) {
            if (managedUBO.resetIfClosed(e)) return;
            throw e;
        }
    }
}
