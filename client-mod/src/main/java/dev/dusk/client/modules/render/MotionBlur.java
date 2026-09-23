package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Motion blur, ported from natural-motionblur. Five algorithms:
 * velocity-based reprojection (the default — it reads the depth buffer and
 * the camera delta, so only things that actually moved smear), a weighted
 * blend of the last few frames over a shutter-style exposure window, a
 * hybrid of the two, and two cheap accumulation modes. The pass runs on
 * the world render before the GUI, so 2D UI stays crisp. The GPU work
 * lives in {@code dev.dusk.client.render.MotionBlurRenderer} and the
 * per-version {@code render.motionblur} package.
 */
public class MotionBlur extends Module {
    /** Mirrors natural-motionblur's {@code ConfigEntries.BlurAlgorithm}; ordinals go to the shader. */
    public enum Algorithm { VELOCITY_BASED, FRAME_BLENDING, HYBRID_BLENDING, ACCUMULATION_MAX, ACCUMULATION_MIX }

    private static final String[] ALGORITHM_NAMES = {
            "Velocity", "Frame blending", "Hybrid", "Accumulation max", "Accumulation mix"
    };

    private static MotionBlur instance;

    private final ChoiceSetting algorithm = add(new ChoiceSetting("algorithm", "Algorithm", ALGORITHM_NAMES[0], ALGORITHM_NAMES));
    // natural-motionblur stores this as a 0..2 float; the editor only has
    // integer sliders, so it is kept here as a percentage of that scale.
    private final IntSetting strength = add(new IntSetting("strength", "Strength", 100, 0, 200, 5, "%"));
    private final BoolSetting refreshRateScaling = add(new BoolSetting("refreshRateScaling", "Scale with refresh rate", true));

    public MotionBlur() {
        super("motion_blur", "Motion Blur", Category.RENDER, "Velocity-based motion blur; the HUD is never blurred.");
        instance = this;
    }

    public static MotionBlur instance() {
        return instance;
    }

    public Algorithm algorithm() {
        int i = algorithm.index();
        Algorithm[] values = Algorithm.values();
        return values[Math.max(0, Math.min(values.length - 1, i))];
    }

    /** natural-motionblur's {@code motionBlurStrength}: 0..2, 1.0 by default. */
    public float strength() {
        return strength.get() / 100.0f;
    }

    public int strengthPercent() {
        return strength.get();
    }

    public boolean refreshRateScaling() {
        return refreshRateScaling.get();
    }

    public boolean usesVelocityBlur() {
        Algorithm a = algorithm();
        return a == Algorithm.VELOCITY_BASED || a == Algorithm.HYBRID_BLENDING;
    }

    public boolean allowsRefreshRateScaling() {
        return algorithm() == Algorithm.VELOCITY_BASED;
    }

    /** True when the module is on and would actually blur something. */
    public boolean active() {
        return enabled() && strength() != 0.0f;
    }
}
