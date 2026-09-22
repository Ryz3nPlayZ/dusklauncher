package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Accumulation motion blur (natural-motionblur style): every frame the
 * world render is blended into a persistent history target, so movement
 * leaves a trail. Runs as a post pass after the level, before the HUD,
 * so 2D UI stays crisp. The GPU work lives in
 * {@link dev.dusk.client.render.MotionBlurRenderer}.
 */
public class MotionBlur extends Module {
    private static MotionBlur instance;

    private final IntSetting strength = add(new IntSetting("strength", "Strength", 50, 1, 100, 1, "%"));

    public MotionBlur() {
        super("motion_blur", "Motion Blur", Category.RENDER, "Frame-accumulation blur; the HUD is never blurred.");
        instance = this;
    }

    public static MotionBlur instance() {
        return instance;
    }

    public int strengthPercent() {
        return strength.get();
    }
}
