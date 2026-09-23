package dev.dusk.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.modules.render.MotionBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Runs the motion-blur post pass once per frame, at the end of
 * GameRenderer.renderLevel (see MotionBlurMixin).
 *
 * The pass mixes the freshly rendered main target with the previous blended
 * frame, writes the result into a private scratch target and copies it back
 * over main and into the history target, so the pass never reads the target
 * it writes.
 *
 * 1.21–1.21.1 flavour: the legacy {@link PostChain}. The chain file
 * ({@code assets/duskclient/shaders/post/motion_blur.json}) declares nothing;
 * the single pass is added in code so it can read main and write our scratch
 * target directly, with the history target bound as an aux sampler. The
 * program itself lives at {@code assets/minecraft/shaders/program/duskclient_motion_blur.*}
 * because EffectInstance only resolves program names in the minecraft namespace.
 */
public final class MotionBlurRenderer {
    private static final ResourceLocation CHAIN_ID = ResourceLocation.fromNamespaceAndPath("duskclient", "shaders/post/motion_blur.json");
    private static final String PROGRAM = "duskclient_motion_blur";
    private static final float MAX_FACTOR = 0.97f;
    private static final long MAX_DT_NANOS = 100_000_000L;

    private static RenderTarget history, scratch;
    private static PostChain chain;
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
            ensureChain(mc, main);
            if (!hasHistory) {
                copy(main, history);
                main.bindWrite(true);
                hasHistory = true;
                return;
            }
            chain.setUniform("blendFactor", blendFactor(module.strengthPercent(), dt));
            chain.process(mc.getTimer().getGameTimeDeltaPartialTick(false));
            copy(scratch, main);
            copy(scratch, history);
            main.bindWrite(true);
            blendedFrames++;
        } catch (Exception e) {
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

    /** (Re)builds the targets and the chain whenever the main target changes size. */
    private static void ensureChain(Minecraft mc, RenderTarget main) throws Exception {
        int width = main.width, height = main.height;
        if (chain != null && history.width == width && history.height == height) return;
        release();
        history = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        scratch = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        history.setClearColor(0, 0, 0, 0);
        scratch.setClearColor(0, 0, 0, 0);
        PostChain c = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN_ID);
        PostPass pass = c.addPass(PROGRAM, main, scratch, false);
        pass.addAuxAsset("PrevSampler", () -> history.getColorTextureId(), width, height);
        c.resize(width, height); // also pushes the ortho matrix onto the new pass
        chain = c;
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
        if (chain != null) chain.close();
        if (history != null) history.destroyBuffers();
        if (scratch != null) scratch.destroyBuffers();
        chain = null;
        history = scratch = null;
        hasHistory = false;
        lastFrameNanos = 0;
    }
}
