package dev.dusk.client.util;

/** Flex-HUD's TpsUtils: the server's tick rate, from the world time in each time-update packet. */
public final class TpsTracker {
    private static final int SAMPLE_COUNT = 5;
    private static final double[] msptSamples = new double[SAMPLE_COUNT];
    private static int index, samples;
    private static double totalMspt;
    private static long lastGameTime = -1, lastUpdateTime = -1;
    private static double averageTps = 20.0;

    private TpsTracker() {}

    public static void onServerTick(long gameTime) {
        long now = System.nanoTime();
        if (lastGameTime != -1) {
            long passedTicks = gameTime - lastGameTime;
            if (passedTicks > 0) {
                double mspt = (now - lastUpdateTime) / 1000000.0 / passedTicks;
                if (samples == SAMPLE_COUNT) totalMspt -= msptSamples[index];
                else samples++;
                msptSamples[index] = mspt;
                totalMspt += mspt;
                index = (index + 1) % SAMPLE_COUNT;
                averageTps = Math.min(20.0, 1000.0 / (totalMspt / samples));
            }
        }
        lastGameTime = gameTime;
        lastUpdateTime = now;
    }

    public static double averageTps() { return averageTps; }

    public static void reset() {
        index = 0;
        samples = 0;
        totalMspt = 0;
        lastGameTime = -1;
        lastUpdateTime = -1;
        averageTps = 20.0;
    }
}
