package dev.dusk.client.render.motionblur;

/** Per-frame timing; exposes FPS and the display refresh rate to the pipeline. */
public final class FrameTimer {

    private long lastNano = 0;
    private float currentFPS = 0.0f;

    public void beginFrame() {
        long now = System.nanoTime();
        float delta = (now - lastNano) / 1_000_000_000.0f;
        lastNano = now;

        // ignore bad deltas on the first frame or after a long pause
        currentFPS = (delta > 0 && delta < 1.0f) ? 1.0f / delta : 0.0f;

        MonitorInfoProvider.updateDisplayInfo();
    }

    public float getFPS() { return currentFPS; }

    public int getRefreshRate() { return MonitorInfoProvider.getRefreshRate(); }
}
