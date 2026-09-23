package dev.dusk.client.render;

import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.shaders.Uniform;
import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.mixin.PostChainAccessor;
import dev.dusk.client.mixin.PostPassAccessor;
import dev.dusk.client.modules.render.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

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
 * 1.21.2–1.21.4 flavour: pre-GpuDevice. The pass owns a CompiledShaderProgram
 * whose uniforms are set directly (blendFactor must NOT be listed in the
 * post_effect pass, or the pass would reset it every frame); inputs bind GL
 * texture ids; targets are copied with glBlitFramebuffer.
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
                main.bindWrite(true);
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
            Uniform blend = pass.getShader().getUniform("blendFactor");
            if (blend != null) blend.set(blendFactor(module.strengthPercent(), dt));
            mainInput.target = main;
            prevInput.target = history;
            replaceInput(pass, mainInput);
            replaceInput(pass, prevInput);

            current.process(scratch, GraphicsResourceAllocator.UNPOOLED);
            copy(scratch, main);
            main.bindWrite(true);
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

    /** Input has no samplerName() here; read it off the vanilla record types. */
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

    /** Same recipe as RenderTarget.copyDepthFrom, for the colour attachment. */
    private static void copy(RenderTarget src, RenderTarget dst) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, src.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dst.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, src.width, src.height, 0, 0, dst.width, dst.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
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
        public void bindTo(CompiledShaderProgram program, Map<ResourceLocation, ResourceHandle<RenderTarget>> targets) {
            program.bindSampler(samplerName + "Sampler", target.getColorTextureId());
        }
    }
}
