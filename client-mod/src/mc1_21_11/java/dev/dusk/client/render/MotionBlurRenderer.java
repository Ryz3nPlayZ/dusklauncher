package dev.dusk.client.render;

import dev.dusk.client.perf.Perf;
import dev.dusk.client.render.motionblur.MotionBlurPipeline;

/**
 * Entry points the mixins call. The work itself lives in
 * {@link dev.dusk.client.render.motionblur.MotionBlurPipeline}, a port of
 * natural-motionblur; this is only the seam the rest of the mod (and the
 * game test) talks to, so the older game lines can keep their own renderer
 * behind the same two names.
 */
public final class MotionBlurRenderer {

    private MotionBlurRenderer() {}

    /**
     * End of GameRenderer.renderLevel: run whatever blur was deferred past the
     * level render, then the colour grade, which wants the blurred frame and
     * has to be done before the GUI is drawn over it.
     */
    private static final Perf.Section BLUR = Perf.part("Motion Blur"), SATURATION = Perf.part("Color Saturation");

    public static void afterLevelRender() {
        long t0 = Perf.start();
        MotionBlurPipeline.applyDeferredTemporalBlur();
        Perf.end(BLUR, Perf.WORLD, t0);
        t0 = Perf.start();
        ColorSaturationRenderer.render(MotionBlurPipeline.frameAllocator());
        Perf.end(SATURATION, Perf.WORLD, t0);
        MotionBlurPipeline.clearFrameAllocator();
    }

    /** Frames that actually went through a blur pass (gametest hook). */
    public static int blendedFrames() {
        return MotionBlurPipeline.blurredFrames();
    }
}
