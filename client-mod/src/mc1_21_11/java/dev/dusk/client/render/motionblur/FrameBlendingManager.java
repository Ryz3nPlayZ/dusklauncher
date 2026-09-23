package dev.dusk.client.render.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.dusk.client.mixin.PostChainAccessor;
import dev.dusk.client.mixin.PostPassAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The temporal half of natural-motionblur.
 *
 * Frame blending keeps a ring of recent main-target copies with the time
 * each one was finished, then weights them by how much of a shutter-style
 * exposure window they cover — so the trail is the same length whatever the
 * frame rate, and it decays as frames age out of the window. (That last part
 * is what a plain feedback blend gets wrong: it never lets go.) The ring is
 * 24 deep and gets compacted down to the 12 samplers the shader declares.
 *
 * The accumulation modes are the cheap alternatives: per-pixel max or mix
 * against a single previous frame.
 */
public final class FrameBlendingManager {

    private static final int FRAME_BLEND_UBO_SIZE = 64;
    private static final int ACCUM_UBO_SIZE = 16;
    private static final int MAX_HISTORY = 24;
    private static final int FRAME_BLEND_SAMPLE_LIMIT = 12;
    private static final int UBO_RING_SIZE = 3;

    private static final String MAIN_SAMPLER = "Main";
    private static final String PREV_SAMPLER = "Prev";
    private static final String FRAME_BLEND_UBO = "FrameBlendParamsUniforms";
    private static final String ACCUM_UBO = "AccumulationUniforms";

    private static final Identifier FRAME_BLENDING_ID = Identifier.fromNamespaceAndPath("duskclient", "frame_blending");
    private static final Identifier ACCUMULATION_MAX_ID = Identifier.fromNamespaceAndPath("duskclient", "accumulation_max");
    private static final Identifier ACCUMULATION_MIX_ID = Identifier.fromNamespaceAndPath("duskclient", "accumulation_mix");

    private static final String[] SAMPLE_NAMES = new String[MAX_HISTORY];
    static {
        for (int i = 0; i < MAX_HISTORY; i++) SAMPLE_NAMES[i] = "Sample" + i;
    }

    private static PostChain cachedCombineChain = null;
    private static final ManagedUniformBuffer.Ring combineUBORing =
            new ManagedUniformBuffer.Ring(FRAME_BLEND_UBO, FRAME_BLEND_UBO_SIZE, UBO_RING_SIZE);

    private static final RenderTarget[] historyTargets = new RenderTarget[MAX_HISTORY];
    private static final MutableTextureInput[] historyInputs = new MutableTextureInput[MAX_HISTORY];
    private static final double[] historyTimestamps = new double[MAX_HISTORY];
    private static final int[] weightedHistoryIndices = new int[MAX_HISTORY];
    private static final float[] weightedHistoryWeights = new float[MAX_HISTORY];
    private static final int[] compactHistoryIndices = new int[MAX_HISTORY];
    private static final float[] compactHistoryWeights = new float[MAX_HISTORY];
    private static int historyWriteIndex = 0;
    private static int historyFilled = 0;

    private static float smoothedFPS = 0;

    private static PostChain cachedAccumMaxChain = null;
    private static PostChain cachedAccumMixChain = null;
    private static final ManagedUniformBuffer accumSimpleUBO = new ManagedUniformBuffer(ACCUM_UBO, ACCUM_UBO_SIZE);
    private static RenderTarget accumReadTarget = null;
    private static RenderTarget accumWriteTarget = null;
    private static MutableTextureInput injectedMainInput = null;
    private static MutableTextureInput injectedPrevInput = null;
    private static boolean accumHasPrevious = false;

    private static int targetW = 0;
    private static int targetH = 0;

    private static final Set<String> loadErrorLogged = new HashSet<>();

    private FrameBlendingManager() {}

    public static void applyFrameBlending(GraphicsResourceAllocator allocator, float fps, int refreshRate, float strength) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(client);
        updateSmoothedFPS(fps);

        if (refreshRate <= 0 || strength <= 0.0f) {
            historyWriteIndex = 0;
            historyFilled = 0;
            return;
        }

        ensureTargets(main.width, main.height);

        double now = currentTimeSeconds();
        pushHistoryFrame(main, now);

        int sampleCount = buildWeightedSampleList(now, refreshRate, smoothedFPS, strength);
        if (sampleCount <= 1) {
            return;
        }

        PostChain combineChain = loadFrameBlendChain(client);
        if (combineChain == null) return;
        PostPass combinePass = firstPass(combineChain);
        if (combinePass == null) return;
        Map<String, GpuBuffer> combineUniforms = ((PostPassAccessor) combinePass).duskclient$customUniforms();
        if (!combineUniforms.containsKey(FRAME_BLEND_UBO)) return;

        GpuBuffer combineUBO = combineUBORing.putNext(combineChain, combineUniforms, FRAME_BLEND_UBO);

        try {
            writeBlendParamsUBO(combineUBO, inverseTotalWeight(sampleCount), sampleCount);

            RenderTarget fallback = historyTargets[weightedHistoryIndices[sampleCount - 1]];
            for (int i = 0; i < FRAME_BLEND_SAMPLE_LIMIT; i++) {
                RenderTarget target = (i < sampleCount)
                        ? historyTargets[weightedHistoryIndices[i]]
                        : fallback;
                MutableTextureInput input = historyInputs[i];
                if (input == null) {
                    input = new MutableTextureInput(SAMPLE_NAMES[i], target);
                    historyInputs[i] = input;
                } else {
                    input.setTarget(target);
                }
                setSampler(combinePass, SAMPLE_NAMES[i], input);
            }

            combineChain.process(main, allocator);
        } catch (RuntimeException e) {
            if (combineUBORing.resetIfClosed(e)) return;
            throw e;
        }
    }

    public static void applyAccumulationMax(GraphicsResourceAllocator allocator, float strength) {
        applyAccumulationInternal(allocator, strength * 7.0f, "accumulation_max", true);
    }

    public static void applyAccumulationMix(GraphicsResourceAllocator allocator, float strength) {
        applyAccumulationInternal(allocator, strength * 5.0f, "accumulation_mix", false);
    }

    public static void invalidate() {
        for (int i = 0; i < historyTargets.length; i++) {
            if (historyTargets[i] != null) {
                historyTargets[i].destroyBuffers();
                historyTargets[i] = null;
            }
            historyInputs[i] = null;
            historyTimestamps[i] = 0.0;
        }
        if (accumReadTarget != null) {
            accumReadTarget.destroyBuffers();
            accumReadTarget = null;
        }
        if (accumWriteTarget != null) {
            accumWriteTarget.destroyBuffers();
            accumWriteTarget = null;
        }
        combineUBORing.reset();
        accumSimpleUBO.reset();
        targetW = 0;
        targetH = 0;
        historyWriteIndex = 0;
        historyFilled = 0;
        smoothedFPS = 0;
        Arrays.fill(weightedHistoryIndices, 0);
        Arrays.fill(weightedHistoryWeights, 0.0f);
        Arrays.fill(compactHistoryIndices, 0);
        Arrays.fill(compactHistoryWeights, 0.0f);
        cachedCombineChain = null;
        cachedAccumMaxChain = null;
        cachedAccumMixChain = null;
        injectedMainInput = null;
        injectedPrevInput = null;
        accumHasPrevious = false;
        loadErrorLogged.clear();
    }

    private static void updateSmoothedFPS(float fps) {
        if (fps > 0.0f) {
            smoothedFPS = (smoothedFPS <= 0.0f) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        }
    }

    /** Hybrid mode: how much of the trail the velocity pass has to fill in between blended frames. */
    public static float getHybridVelocityStrength(float fps, int refreshRate, float strength) {
        float baseStrength = Math.max(0.0f, strength);
        if (baseStrength == 0.0f || fps <= 0.0f || refreshRate <= 0) return baseStrength;

        float referenceRate = Math.min(fps, (float) refreshRate);
        float frameSpan = baseStrength * (fps / referenceRate);
        float fillerStrength = Math.min(1.0f, frameSpan);

        float blendFPS = (smoothedFPS <= 0.0f) ? fps : smoothedFPS * 0.85f + fps * 0.15f;
        float blendReferenceRate = Math.min(blendFPS, (float) refreshRate);
        float blendFrameSpan = baseStrength * (blendFPS / blendReferenceRate);
        int sampleCount = Math.min(MAX_HISTORY, (int) Math.ceil(blendFrameSpan - 0.000001f));
        sampleCount = Math.min(sampleCount, Math.min(MAX_HISTORY, historyFilled + 1));

        if (sampleCount > FRAME_BLEND_SAMPLE_LIMIT) {
            int maxGap = 1;
            int previous = 0;
            for (int out = 1; out < FRAME_BLEND_SAMPLE_LIMIT; out++) {
                int source = Math.round((float) out * (sampleCount - 1) / (FRAME_BLEND_SAMPLE_LIMIT - 1));
                maxGap = Math.max(maxGap, source - previous);
                previous = source;
            }
            fillerStrength = Math.max(fillerStrength, maxGap);
        }

        return fillerStrength;
    }

    private static void pushHistoryFrame(RenderTarget src, double timestamp) {
        if (historyTargets[historyWriteIndex] == null) return;
        copyTexture(src, historyTargets[historyWriteIndex]);
        historyTimestamps[historyWriteIndex] = timestamp;
        historyWriteIndex = (historyWriteIndex + 1) % MAX_HISTORY;
        if (historyFilled < MAX_HISTORY) historyFilled++;
    }

    private static int buildWeightedSampleList(double exposureEnd, int refreshRate, float fps, float strength) {
        Arrays.fill(weightedHistoryWeights, 0.0f);
        if (historyFilled <= 0) return 0;

        double referenceRate = (fps > 0.0f)
                ? Math.min(fps, (double) refreshRate)
                : (double) refreshRate;
        double exposureDuration = Math.max(0.0, strength) / referenceRate;
        double exposureStart = exposureEnd - exposureDuration;
        double estimatedFrameTime = (fps > 0.0f) ? 1.0 / fps : 1.0 / refreshRate;
        double totalWeight = 0.0;
        int sampleCount = 0;
        int firstIndex = historyWriteIndex - historyFilled;
        if (firstIndex < 0) firstIndex += MAX_HISTORY;

        for (int i = 0; i < historyFilled; i++) {
            int idx = (firstIndex + i) % MAX_HISTORY;
            double frameEnd = historyTimestamps[idx];
            if (frameEnd <= 0.0) continue;

            double frameStart;
            if (i > 0) {
                int prevIdx = (firstIndex + i - 1) % MAX_HISTORY;
                frameStart = historyTimestamps[prevIdx];
            } else {
                frameStart = frameEnd - estimatedFrameTime;
            }
            if (frameStart >= frameEnd) frameStart = frameEnd - estimatedFrameTime;

            double overlap = Math.min(frameEnd, exposureEnd) - Math.max(frameStart, exposureStart);
            if (overlap > 0.0000001) {
                weightedHistoryIndices[sampleCount] = idx;
                weightedHistoryWeights[sampleCount] = (float) overlap;
                totalWeight += overlap;
                sampleCount++;
            }
        }

        if (sampleCount <= 0) return 0;
        if (totalWeight <= 0.0000001) return 0;

        if (sampleCount > FRAME_BLEND_SAMPLE_LIMIT) {
            sampleCount = compactWeightedSampleList(sampleCount);
        }

        return sampleCount;
    }

    /** More frames in the window than the shader has samplers: pick an even spread and fold the rest of the weight in. */
    private static int compactWeightedSampleList(int sampleCount) {
        if (sampleCount <= FRAME_BLEND_SAMPLE_LIMIT || FRAME_BLEND_SAMPLE_LIMIT <= 0) return sampleCount;
        if (FRAME_BLEND_SAMPLE_LIMIT == 1) {
            float totalWeight = 0.0f;
            for (int i = 0; i < sampleCount; i++) totalWeight += weightedHistoryWeights[i];
            weightedHistoryIndices[0] = weightedHistoryIndices[sampleCount - 1];
            weightedHistoryWeights[0] = totalWeight;
            for (int i = 1; i < sampleCount; i++) weightedHistoryWeights[i] = 0.0f;
            return 1;
        }

        Arrays.fill(compactHistoryWeights, 0.0f);

        for (int out = 0; out < FRAME_BLEND_SAMPLE_LIMIT; out++) {
            int source = Math.round((float) out * (sampleCount - 1) / (FRAME_BLEND_SAMPLE_LIMIT - 1));
            compactHistoryIndices[out] = weightedHistoryIndices[source];
        }

        for (int source = 0; source < sampleCount; source++) {
            int out = Math.round((float) source * (FRAME_BLEND_SAMPLE_LIMIT - 1) / (sampleCount - 1));
            if (out < 0) out = 0;
            if (out >= FRAME_BLEND_SAMPLE_LIMIT) out = FRAME_BLEND_SAMPLE_LIMIT - 1;
            compactHistoryWeights[out] += weightedHistoryWeights[source];
        }

        for (int i = 0; i < FRAME_BLEND_SAMPLE_LIMIT; i++) {
            weightedHistoryIndices[i] = compactHistoryIndices[i];
            weightedHistoryWeights[i] = compactHistoryWeights[i];
        }
        for (int i = FRAME_BLEND_SAMPLE_LIMIT; i < sampleCount; i++) {
            weightedHistoryWeights[i] = 0.0f;
        }

        return FRAME_BLEND_SAMPLE_LIMIT;
    }

    private static float inverseTotalWeight(int sampleCount) {
        float totalWeight = 0.0f;
        for (int i = 0; i < sampleCount; i++) {
            totalWeight += weightedHistoryWeights[i];
        }
        return totalWeight > 0.0f ? 1.0f / totalWeight : 1.0f;
    }

    private static double currentTimeSeconds() {
        return System.nanoTime() * 1.0E-9;
    }

    private static void applyAccumulationInternal(GraphicsResourceAllocator allocator,
                                                  float strength, String shaderName, boolean isMax) {
        Minecraft client = Minecraft.getInstance();
        RenderTarget main = ClientRenderTargets.getMain(client);
        ensureTargets(main.width, main.height);

        if (!accumHasPrevious) {
            copyTexture(main, accumReadTarget);
            accumHasPrevious = true;
            return;
        }

        PostChain chain = loadAccumSimpleChain(client, shaderName, isMax);
        if (chain == null) return;

        PostPass pass = firstPass(chain);
        if (pass == null) return;

        Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).duskclient$customUniforms();
        if (!uniforms.containsKey(ACCUM_UBO)) return;

        GpuBuffer ubo = accumSimpleUBO.put(chain, uniforms, ACCUM_UBO);

        try {
            writeFloatUBO(ubo, strengthToBlendFactor(strength));

            if (injectedMainInput == null) {
                injectedMainInput = new MutableTextureInput(MAIN_SAMPLER, main);
            } else {
                injectedMainInput.setTarget(main);
            }
            setSampler(pass, MAIN_SAMPLER, injectedMainInput);

            if (injectedPrevInput == null) {
                injectedPrevInput = new MutableTextureInput(PREV_SAMPLER, accumReadTarget);
            } else {
                injectedPrevInput.setTarget(accumReadTarget);
            }
            setSampler(pass, PREV_SAMPLER, injectedPrevInput);

            chain.process(accumWriteTarget, allocator);
            copyTexture(accumWriteTarget, main);
            swapAccumTargets();
        } catch (RuntimeException e) {
            if (accumSimpleUBO.resetIfClosed(e)) return;
            throw e;
        }
    }

    private static float strengthToBlendFactor(float strength) {
        return (float) (1.0 - Math.pow(0.5, strength / 3.0));
    }

    private static PostChain loadAccumSimpleChain(Minecraft client, String shaderName, boolean isMax) {
        try {
            PostChain result = client.getShaderManager()
                    .getPostChain(isMax ? ACCUMULATION_MAX_ID : ACCUMULATION_MIX_ID, LevelTargetBundle.MAIN_TARGETS);

            if (isMax && result != cachedAccumMaxChain) {
                cachedAccumMaxChain = result;
                injectedMainInput = null;
                injectedPrevInput = null;
            } else if (!isMax && result != cachedAccumMixChain) {
                cachedAccumMixChain = result;
                injectedMainInput = null;
                injectedPrevInput = null;
            }

            loadErrorLogged.remove(shaderName);
            return result;
        } catch (Exception e) {
            if (loadErrorLogged.add(shaderName)) {
                System.err.println("[DuskClient] failed to load the " + shaderName + " shader: " + e.getMessage());
            }
            return null;
        }
    }

    private static void ensureTargets(int w, int h) {
        if (targetW == w && targetH == h && historyTargets[0] != null && accumReadTarget != null && accumWriteTarget != null) return;
        for (int i = 0; i < historyTargets.length; i++) {
            if (historyTargets[i] != null) historyTargets[i].destroyBuffers();
            historyTargets[i] = new MainTarget(w, h);
            historyInputs[i] = null;
            historyTimestamps[i] = 0.0;
        }
        if (accumReadTarget != null) accumReadTarget.destroyBuffers();
        if (accumWriteTarget != null) accumWriteTarget.destroyBuffers();
        accumReadTarget = new MainTarget(w, h);
        accumWriteTarget = new MainTarget(w, h);
        targetW = w;
        targetH = h;
        historyWriteIndex = 0;
        historyFilled = 0;
        Arrays.fill(weightedHistoryIndices, 0);
        Arrays.fill(weightedHistoryWeights, 0.0f);
        Arrays.fill(compactHistoryIndices, 0);
        Arrays.fill(compactHistoryWeights, 0.0f);
        injectedMainInput = null;
        injectedPrevInput = null;
        accumHasPrevious = false;
    }

    private static void swapAccumTargets() {
        RenderTarget temp = accumReadTarget;
        accumReadTarget = accumWriteTarget;
        accumWriteTarget = temp;
    }

    private static void copyTexture(RenderTarget src, RenderTarget dst) {
        if (src == null || dst == null) return;
        if (src.getColorTexture() == null || dst.getColorTexture() == null) return;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(),
                0, 0, 0, 0, 0, dst.width, dst.height);
    }

    private static PostPass firstPass(PostChain chain) {
        List<PostPass> passes = ((PostChainAccessor) chain).duskclient$passes();
        return passes.isEmpty() ? null : passes.getFirst();
    }

    private static void setSampler(PostPass pass, String samplerName, PostPass.Input replacement) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).duskclient$inputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (samplerName.equals(inputs.get(i).samplerName())) {
                if (inputs.get(i) != replacement) inputs.set(i, replacement);
                return;
            }
        }
    }

    private static void writeFloatUBO(GpuBuffer ubo, float value) {
        GpuBufferUtil.writeStd140(ubo, ACCUM_UBO_SIZE, b -> {
            b.putFloat(value);
            b.putInt(0);
            b.putInt(0);
            b.putInt(0);
        });
    }

    private static void writeBlendParamsUBO(GpuBuffer ubo, float invTotalWeight, int sampleCount) {
        GpuBufferUtil.writeStd140(ubo, FRAME_BLEND_UBO_SIZE, b -> {
            b.putFloat(invTotalWeight);
            b.putInt(sampleCount);
            for (int i = 0; i < FRAME_BLEND_SAMPLE_LIMIT; i++) {
                b.putFloat(i < sampleCount ? weightedHistoryWeights[i] : 0.0f);
            }
            b.putFloat(0.0f);
            b.putFloat(0.0f);
        });
    }

    private static PostChain loadFrameBlendChain(Minecraft client) {
        try {
            PostChain result = client.getShaderManager().getPostChain(FRAME_BLENDING_ID, LevelTargetBundle.MAIN_TARGETS);
            if (result != cachedCombineChain) {
                cachedCombineChain = result;
            }
            loadErrorLogged.remove("frame_blending");
            return result;
        } catch (Exception e) {
            if (loadErrorLogged.add("frame_blending")) {
                System.err.println("[DuskClient] failed to load the frame_blending shader: " + e.getMessage());
            }
            return null;
        }
    }

    /** A pass input that samples whatever target we point it at. */
    private static final class MutableTextureInput implements PostPass.Input {
        private final String samplerName;
        private RenderTarget target;

        MutableTextureInput(String samplerName, RenderTarget target) {
            this.samplerName = samplerName;
            this.target = target;
        }

        void setTarget(RenderTarget target) {
            this.target = target;
        }

        @Override
        public void addToPass(FramePass pass, Map<Identifier, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public GpuTextureView texture(Map<Identifier, ResourceHandle<RenderTarget>> targets) {
            return target.getColorTextureView();
        }

        @Override
        public String samplerName() { return samplerName; }

        @Override
        public boolean bilinear() { return false; }
    }
}
