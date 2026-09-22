package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Raises the lightmap gamma far past the vanilla slider (gamma-utils
 * style). Only the light texture is touched, so it is a pure rendering
 * change.
 */
public class Fullbright extends Module {
    private static Fullbright instance;

    private final IntSetting brightness = add(new IntSetting("brightness", "Brightness", 1000, 100, 1500, 50, "%"));
    private final IntSetting step = add(new IntSetting("step", "Key step", 100, 10, 500, 10, "%"));

    public Fullbright() {
        super("fullbright", "Fullbright", Category.RENDER, "Night vision without the potion: boosts gamma beyond 100%.");
        instance = this;
    }

    public static Fullbright instance() {
        return instance;
    }

    public void adjust(int direction) {
        brightness.set(brightness.get() + direction * step.get());
    }

    public int brightnessPercent() {
        return brightness.get();
    }

    /** Called from the lightmap mixin with the vanilla gamma option value. */
    public static Object applyGamma(Object original) {
        Fullbright f = instance;
        if (f == null || !f.enabled()) return original;
        return (double) f.brightness.get() / 100.0;
    }
}
