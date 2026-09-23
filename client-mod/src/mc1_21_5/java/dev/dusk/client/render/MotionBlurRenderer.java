package dev.dusk.client.render;

import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.mixin.PostChainAccessor;
import dev.dusk.client.mixin.PostPassAccessor;
import dev.dusk.client.modules.render.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * Runs the motion-blur post pass once per frame, at the end of
 * GameRenderer.renderLevel (see MotionBlurMixin).
 *
 * The pass ({@code assets/duskclient/post_effect/motion_blur.json}) mixes the
 * freshly rendered main target with the previous blended frame, writes the
 * result into a private history target and copies it back over main. The two
 * "minecraft:main" inputs the chain declares are swapped at runtime for the
 * real main target and the history target, so the pass never reads the target
 * it writes.
 *
 * 1.21.5 flavour: uniforms are plain (no uniform blocks) and are set through
 * the RenderPass consumer PostChain.process takes; inputs bind themselves with
 * RenderPass.bindSampler; targets expose GpuTexture, not texture views.
 */
public final class MotionBlurRenderer {
    private static final ResourceLocation CHAIN_ID = ResourceLocation.fromNamespaceAndPath("duskclient", "motion_blur");
    private static final float MAX_FACTOR = 0.97f;
    private static final long MAX_DT_NANOS = 100_000_000L;

    private static RenderTarget history, scratch;
    private static final SwappableInput mainInput = new SwappableInput("Main");
    private static final SwappableInput prevInput = new SwappableInput("Prev");
    private static boolean hasHistory;
    private static long lastFrameNanos;
    private static boolean failed;
    /** Frames that actually went through the blend pass (gametest hook). */
    private static int blendedFrames;

    private MotionBlurRenderer() {}

    public static int blendedFrames() {
        return blendedFrames;
    }

    /** Called at the tail of GameRenderer.renderLevel on the render thread. */
    public static void afterLevelRender() {
        MotionBlur module = MotionBlur.instance();
        if (module == null || !module.enabled() || failed) {
            if (history != null) release();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = Compat.mainTarget(mc);
        if (main == null || main.width <= 0 || main.height <= 0) return;

        long now = System.nanoTime();
        long dt = lastFrameNanos == 0 ? MAX_DT_NANOS : Math.min(MAX_DT_NANOS, now - lastFrameNanos);
        lastFrameNanos = now;

        try {
            ensureTargets(main.width, main.height);
            if (!hasHistory) {
                copy(main, history);
                hasHistory = true;
                return;
            }
            PostChain current = mc.getShaderManager().getPostChain(CHAIN_ID, LevelTargetBundle.MAIN_TARGETS);
            if (current == null) {
                failed = true; // getPostChain already logged the compile error
                DuskClient.LOGGER.warn("Motion blur disabled: post chain {} failed to load", CHAIN_ID);
                return;
            }
            List<PostPass> passes = ((PostChainAccessor) current).duskclient$passes();
            if (passes.isEmpty()) return;
            PostPass pass = passes.getFirst();
            float factor = blendFactor(module.strengthPercent(), dt);
            mainInput.target = main;
            prevInput.target = history;
            replaceInput(pass, mainInput);
            replaceInput(pass, prevInput);

            current.process(scratch, GraphicsResourceAllocator.UNPOOLED,
                    (RenderPass rp) -> rp.setUniform("blendFactor", factor));
            copy(scratch, main);
            RenderTarget t = history;
            history = scratch;
            scratch = t;
            blendedFrames++;
        } catch (RuntimeException e) {
            failed = true;
            DuskClient.LOGGER.error("Motion blur disabled after a render error", e);
            release();
        }
    }

    /**
     * Fraction of the previous frame kept, frame-rate independent: the trail
     * decays with time constant strength% × 4 ms (50% → 200 ms), capped so a
     * stalled frame can't freeze the picture.
     */
    static float blendFactor(int strengthPercent, long dtNanos) {
        double tauNanos = strengthPercent * 4_000_000.0;
        double f = Math.exp(-dtNanos / tauNanos);
        return (float) Math.min(MAX_FACTOR, Math.max(0, f));
    }

    private static void replaceInput(PostPass pass, SwappableInput input) {
        List<PostPass.Input> inputs = ((PostPassAccessor) pass).duskclient$inputs();
        for (int i = 0; i < inputs.size(); i++) {
            PostPass.Input existing = inputs.get(i);
            if (input.samplerName.equals(samplerNameOf(existing))) {
                if (existing != input) inputs.set(i, input);
                return;
            }
        }
    }

    /** 1.21.5's Input has no samplerName(); read it off the vanilla record types. */
    private static String samplerNameOf(PostPass.Input input) {
        if (input instanceof PostPass.TargetInput t) return t.samplerName();
        if (input instanceof PostPass.TextureInput t) return t.samplerName();
        if (input instanceof SwappableInput s) return s.samplerName;
        return null;
    }

    private static void ensureTargets(int width, int height) {
        if (history != null && history.width == width && history.height == height) return;
        release();
        history = new MainTarget(width, height);
        scratch = new MainTarget(width, height);
    }

    private static void copy(RenderTarget src, RenderTarget dst) {
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                src.getColorTexture(), dst.getColorTexture(), 0, 0, 0, 0, 0, dst.width, dst.height);
    }

    private static void release() {
        if (history != null) history.destroyBuffers();
        if (scratch != null) scratch.destroyBuffers();
        history = scratch = null;
        hasHistory = false;
        lastFrameNanos = 0;
    }

    /** A pass input that samples whatever target we point it at. */
    private static final class SwappableInput implements PostPass.Input {
        private final String samplerName;
        RenderTarget target;

        SwappableInput(String samplerName) {
            this.samplerName = samplerName;
        }

        @Override
        public void addToPass(FramePass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {}

        @Override
        public void bindTo(RenderPass pass, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            pass.bindSampler(samplerName + "Sampler", target.getColorTexture());
        }
    }
}
