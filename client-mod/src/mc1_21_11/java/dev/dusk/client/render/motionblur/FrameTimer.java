package dev.dusk.client.render.motionblur;

/** Per-frame timing; exposes FPS and the display refresh rate to the pipeline. */
public final class FrameTimer {

    private long lastNano = 0;
    private float currentFPS = 0.0f;
    private float smoothDelta = 0.0f;

    public void beginFrame() {
        long now = System.nanoTime();
        float delta = (now - lastNano) / 1_000_000_000.0f;
        lastNano = now;

        // ignore bad deltas on the first frame or after a long pause. Smoothed:
        // the blur strength scales with FPS, and raw frame-to-frame jitter
        // made the trail length (and the sample count) flicker every frame.
        if (delta > 0 && delta < 1.0f) {
            smoothDelta = smoothDelta == 0 ? delta : smoothDelta + (delta - smoothDelta) * 0.1f;
            currentFPS = 1.0f / smoothDelta;
        } else {
            smoothDelta = 0;
            currentFPS = 0.0f;
        }

        MonitorInfoProvider.updateDisplayInfo();
    }

    public float getFPS() { return currentFPS; }

    public int getRefreshRate() { return MonitorInfoProvider.getRefreshRate(); }
}
