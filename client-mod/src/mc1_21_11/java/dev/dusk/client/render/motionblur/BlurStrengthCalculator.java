package dev.dusk.client.render.motionblur;

/**
 * natural-motionblur's refresh-rate scaling: above the display's refresh
 * rate a frame covers less time, so the shutter has to open wider (and take
 * more samples) to keep the same perceived trail length.
 */
public final class BlurStrengthCalculator {

    /**
     * Upper bound on the taps per pixel. The shader takes speed x samples of
     * them, and uncapped (100 x fps/refresh: 400 at 240 fps on 60 Hz) a fast
     * flick cost up to 160 full-screen texture reads per pixel, a frame-time
     * spike right when motion needs to be smooth. The jittered taps hide the
     * coarser steps.
     */
    public static final int MAX_SAMPLES = 32;

    public record Result(float strength, int sampleAmount) {
        public Result {
            sampleAmount = Math.min(sampleAmount, MAX_SAMPLES);
        }
    }

    public Result calculate(float baseStrength, float fps, int refreshRate, boolean scalingEnabled) {
        if (!scalingEnabled) {
            return new Result(baseStrength, 100);
        }

        float fpsOverRefresh = (refreshRate > 0) ? fps / refreshRate : 1.0f;
        if (fpsOverRefresh < 1.0f) fpsOverRefresh = 1.0f;

        float scaledStrength = baseStrength * fpsOverRefresh;
        int sampleAmount = (fpsOverRefresh > 1.0f) ? (int) (100 * fpsOverRefresh) : 100;

        return new Result(scaledStrength, sampleAmount);
    }
}
