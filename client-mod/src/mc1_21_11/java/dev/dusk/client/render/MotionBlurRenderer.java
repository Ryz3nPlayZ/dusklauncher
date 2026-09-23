package dev.dusk.client.render;

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
    public static void afterLevelRender() {
        MotionBlurPipeline.applyDeferredTemporalBlur();
        ColorSaturationRenderer.render(MotionBlurPipeline.frameAllocator());
        MotionBlurPipeline.clearFrameAllocator();
    }

    /** Frames that actually went through a blur pass (gametest hook). */
    public static int blendedFrames() {
        return MotionBlurPipeline.blurredFrames();
    }
}
