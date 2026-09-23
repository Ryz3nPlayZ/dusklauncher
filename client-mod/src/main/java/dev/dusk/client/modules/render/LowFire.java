package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/**
 * BactroMod's "Fire Height": shifts the first-person fire overlay so burning
 * does not blind you. The offset is a hundredth of the overlay's own height,
 * negative moving it down, matching BactroMod's -100..100 slider.
 */
public class LowFire extends Module {
    private static LowFire instance;

    private final IntSetting offset = add(new IntSetting("offset", "Fire height", -30, -100, 100, 1, "%"));

    public LowFire() {
        super("lowfire", "Low Fire", Category.RENDER,
                "Moves the first-person fire overlay out of your view.");
        instance = this;
    }

    /** The translation the fire overlay gets, or 0 while the module is off. */
    public static float offsetY() {
        if (instance == null || !instance.enabled()) return 0;
        return instance.offset.get() / 100f; // the setting is already bounded to -100..100
    }
}
