package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/**
 * ColorSaturation: a colour grade over the finished world render — saturation,
 * contrast, brightness and a hue rotation, in that order, exactly as
 * Polyfrost's shader does them.
 *
 * <p>The sliders are whole percents (and whole degrees for the hue) because
 * the editor has no fractional slider; Polyfrost steps saturation in 0.05.
 */
public class ColorSaturation extends Module {
    private static ColorSaturation instance;

    private final IntSetting saturation = add(new IntSetting("saturation", "Saturation", 100, -100, 500, 5, "%"));
    private final IntSetting contrast = add(new IntSetting("contrast", "Contrast", 100, 0, 200, 5, "%"));
    private final IntSetting brightness = add(new IntSetting("brightness", "Brightness", 100, 0, 200, 5, "%"));
    private final IntSetting hue = add(new IntSetting("hue", "Hue shift", 0, -180, 180, 1, "°"));

    public ColorSaturation() {
        super("colorsaturation", "Color Saturation", Category.RENDER,
                "Grades the world's colours: saturation, contrast, brightness and hue.");
        instance = this;
    }

    /** True only when the grade would actually change a pixel. */
    public static boolean active() {
        ColorSaturation m = instance;
        if (m == null || !m.enabled()) return false;
        return m.saturation.get() != 100 || m.contrast.get() != 100
                || m.brightness.get() != 100 || m.hue.get() != 0;
    }

    public static float saturation() {
        ColorSaturation m = instance;
        return m == null ? 1f : m.saturation.get() / 100f;
    }

    public static float contrast() {
        ColorSaturation m = instance;
        return m == null ? 1f : m.contrast.get() / 100f;
    }

    public static float brightness() {
        ColorSaturation m = instance;
        return m == null ? 1f : m.brightness.get() / 100f;
    }

    /** Degrees around the grey axis. */
    public static float hue() {
        ColorSaturation m = instance;
        return m == null ? 0f : m.hue.get();
    }
}
