package dev.dusk.client.render.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Locale;

/**
 * One OptiFine custom sky layer ({@code optifine/sky/worldN/skyM.properties}):
 * a texture on a cube around the camera, faded in and out by time of day,
 * weather, biome and height, and turned with the sun. The rules are
 * OptiFine's, as Nuit Interop (MIT, FlashyReese) reads them.
 */
final class SkyLayer {
    static final int DAY = 24000;
    private static final float UNSET = -1;

    final Identifier texture;
    final int blend;
    /** no fade times given: shown all day */
    final boolean alwaysOn;
    final int startFadeIn, endFadeIn, startFadeOut, endFadeOut;
    final boolean rotate;
    final float speed;
    /** already in sky space: OptiFine's (x, y, z) as (z, y, -x) */
    final float axisX, axisY, axisZ;
    final boolean clear, rain, thunder;
    final float transition;
    /** a "biomes" or "heights" line was given (even one that matches nothing) */
    final boolean biomeCondition, heightCondition;
    /** false for "biomes=!..." — every biome but these */
    final boolean biomeInclusion;
    final List<Identifier> biomes;
    final int[][] heights;
    /** empty: every day */
    final int[][] days;
    final int daysLoop;

    /** biome/height alpha, eased over {@link #transition} seconds */
    private float positionAlpha = UNSET;
    private long positionAlphaMs;

    SkyLayer(Identifier texture, int blend, boolean alwaysOn, int startFadeIn, int endFadeIn, int startFadeOut, int endFadeOut,
             boolean rotate, float speed, float[] axis, boolean clear, boolean rain, boolean thunder, float transition,
             boolean biomeCondition, boolean biomeInclusion, List<Identifier> biomes,
             boolean heightCondition, int[][] heights, int[][] days, int daysLoop) {
        this.texture = texture;
        this.blend = blend;
        this.alwaysOn = alwaysOn;
        this.startFadeIn = startFadeIn;
        this.endFadeIn = endFadeIn;
        this.startFadeOut = startFadeOut;
        this.endFadeOut = endFadeOut;
        this.rotate = rotate;
        this.speed = speed;
        this.axisX = axis[0];
        this.axisY = axis[1];
        this.axisZ = axis[2];
        this.clear = clear;
        this.rain = rain;
        this.thunder = thunder;
        this.transition = transition;
        this.biomeCondition = biomeCondition || !biomes.isEmpty();
        this.biomeInclusion = biomeInclusion;
        this.biomes = biomes;
        this.heightCondition = heightCondition || heights.length > 0;
        this.heights = heights;
        this.days = days;
        this.daysLoop = daysLoop;
    }

    void resetPosition() {
        positionAlpha = UNSET;
        positionAlphaMs = 0;
    }

    /** Outside its fade window, or on a day the loop skips. */
    boolean showing(long dayTime, int timeOfDay) {
        if (!alwaysOn && containsTime(timeOfDay, endFadeOut, startFadeIn)) return false;
        if (days.length == 0) return true;
        long adjusted = dayTime - startFadeIn;
        while (adjusted < 0) adjusted += (long) DAY * daysLoop;
        int day = (int) ((adjusted / DAY) % daysLoop);
        return inAny(day, days);
    }

    float alpha(Level level, BlockPos camera, int timeOfDay, float rainLevel, float thunderLevel) {
        float fade = alwaysOn ? 1 : fadeAlpha(timeOfDay);
        float weather = 0;
        if (clear) weather += 1 - rainLevel;
        if (rain) weather += rainLevel - thunderLevel;
        if (thunder) weather += thunderLevel;
        weather = Mth.clamp(weather, 0, 1);
        return Mth.clamp(positionAlpha(level, camera) * weather * fade, 0, 1);
    }

    /** Degrees around {@link #axisX}/Y/Z for this frame. */
    float rotation(long dayTime, float celestial) {
        float dayStart = 0;
        if (speed != (float) Math.round(speed)) {
            long day = (dayTime + 18000L) / DAY;
            dayStart = (float) ((day * (double) (speed % 1)) % 1);
        }
        return 360 * (dayStart + celestial * speed);
    }

    private float fadeAlpha(int time) {
        if (containsTime(time, endFadeIn, startFadeOut)) return 1;
        if (containsTime(time, startFadeIn, endFadeIn)) {
            return (float) cyclicDistance(startFadeIn, time) / cyclicDistance(startFadeIn, endFadeIn);
        }
        if (containsTime(time, startFadeOut, endFadeOut)) {
            return 1 - (float) cyclicDistance(startFadeOut, time) / cyclicDistance(startFadeOut, endFadeOut);
        }
        return 0;
    }

    private float positionAlpha(Level level, BlockPos camera) {
        if (!biomeCondition && !heightCondition) return 1;
        float target = camera != null && matchesPosition(level, camera) ? 1 : 0;
        long now = System.currentTimeMillis();
        if (positionAlpha == UNSET) {
            positionAlphaMs = now;
            return positionAlpha = target;
        }
        float seconds = (now - positionAlphaMs) / 1000f;
        positionAlphaMs = now;
        return positionAlpha = smooth(positionAlpha, target, seconds, transition);
    }

    private boolean matchesPosition(Level level, BlockPos pos) {
        if (biomeCondition) {
            Holder<Biome> biome = level.getBiome(pos);
            ResourceKey<Biome> key = biome.isBound() ? biome.unwrapKey().orElse(null) : null;
            if (key == null) return false;
            Identifier id = key.identifier();
            boolean listed = false;
            for (Identifier b : biomes) {
                if (sameBiome(b, id)) {
                    listed = true;
                    break;
                }
            }
            if (listed != biomeInclusion) return false;
        }
        return !heightCondition || inAny(pos.getY(), heights);
    }

    /** OptiFine names biomes loosely: "Deep Ocean" is minecraft:deep_ocean. */
    private static boolean sameBiome(Identifier expected, Identifier actual) {
        return expected.equals(actual) || expected.getNamespace().equals(actual.getNamespace())
                && compact(expected.getPath()).equals(compact(actual.getPath()));
    }

    private static String compact(String path) {
        return path.replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    // OptiFine's time and easing maths

    static int normalizeTime(int time) {
        int t = time % DAY;
        return t < 0 ? t + DAY : t;
    }

    /** {@code time} within [start, end], wrapping past midnight. */
    static boolean containsTime(int time, int start, int end) {
        return start <= end ? time >= start && time <= end : time >= start || time <= end;
    }

    static int cyclicDistance(int start, int end) {
        return (end - start + DAY) % DAY;
    }

    static boolean inAny(int value, int[][] ranges) {
        for (int[] r : ranges) {
            if (value >= r[0] && value <= r[1]) return true;
        }
        return false;
    }

    static float smooth(float from, float to, float seconds, float fadeSeconds) {
        if (seconds <= 0) return from;
        float delta = to - from;
        if (fadeSeconds > 0 && seconds < fadeSeconds && Math.abs(delta) > 1e-6f) {
            float updates = fadeSeconds / seconds;
            float correction = 4.61f - 1 / (0.13f + updates / 10);
            return from + delta * Mth.clamp(seconds / fadeSeconds * correction, 0, 1);
        }
        return to;
    }
}
