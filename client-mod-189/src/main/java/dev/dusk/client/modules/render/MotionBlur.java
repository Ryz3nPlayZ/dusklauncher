package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.render.MotionBlurRenderer;

/**
 * The 1.21.11 motion blur (ported from natural-motionblur): velocity-based
 * reprojection by default, so only what actually moved on screen smears,
 * plus frame blending, a hybrid of the two and two cheap accumulation
 * modes. It runs inside the world render, before the hand and the HUD, so
 * neither is blurred. The GPU side is {@link MotionBlurRenderer}.
 */
public class MotionBlur extends Module {
    /** The same order as 1.21.11, so a copied config picks the same one. */
    public enum Algorithm { VELOCITY_BASED, FRAME_BLENDING, HYBRID_BLENDING, ACCUMULATION_MAX, ACCUMULATION_MIX }

    private static final String[] ALGORITHM_NAMES = {
            "Velocity", "Frame blending", "Hybrid", "Accumulation max", "Accumulation mix"
    };

    private static MotionBlur instance;

    private final ChoiceSetting algorithm = add(new ChoiceSetting("algorithm", "Algorithm", ALGORITHM_NAMES[0], ALGORITHM_NAMES));
    private final IntSetting strength = add(new IntSetting("strength", "Strength", 100, 0, 200, 5, "%"));
    private final BoolSetting refreshRateScaling = add(new BoolSetting("refreshRateScaling", "Scale with refresh rate", true));

    public MotionBlur() {
        super("motion_blur", "Motion Blur", Category.RENDER, "Velocity-based motion blur; the HUD is never blurred.");
        instance = this;
    }

    public static MotionBlur instance() { return instance; }

    public Algorithm algorithm() {
        Algorithm[] values = Algorithm.values();
        return values[Math.max(0, Math.min(values.length - 1, algorithm.index()))];
    }

    /** natural-motionblur's strength: 0..2, 1.0 by default. */
    public float strength() { return strength.get() / 100.0f; }

    public boolean refreshRateScaling() { return refreshRateScaling.get(); }

    public boolean usesVelocityBlur() {
        Algorithm a = algorithm();
        return a == Algorithm.VELOCITY_BASED || a == Algorithm.HYBRID_BLENDING;
    }

    public boolean allowsRefreshRateScaling() { return algorithm() == Algorithm.VELOCITY_BASED; }

    public boolean active() { return enabled() && strength() != 0.0f; }

    @Override
    protected void onDisable() { MotionBlurRenderer.release(); }
}
