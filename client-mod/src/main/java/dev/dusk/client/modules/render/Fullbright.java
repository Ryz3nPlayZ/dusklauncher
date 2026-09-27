package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import org.lwjgl.glfw.GLFW;

/**
 * Raises the lightmap gamma far past the vanilla slider (gamma-utils
 * style). Only the light texture is touched, so it is a pure rendering
 * change.
 */
public class Fullbright extends Module {
    private static Fullbright instance;

    private final KeySetting toggleKey = add(new KeySetting("fullbright", "Toggle key", GLFW.GLFW_KEY_G));
    private final KeySetting upKey = add(new KeySetting("gamma_up", "Brighter key", GLFW.GLFW_KEY_UNKNOWN));
    private final KeySetting downKey = add(new KeySetting("gamma_down", "Darker key", GLFW.GLFW_KEY_UNKNOWN));
    private final IntSetting brightness = add(new IntSetting("brightness", "Brightness", 1000, 100, 1500, 50, "%"));
    private final IntSetting step = add(new IntSetting("step", "Key step", 100, 10, 500, 10, "%"));

    public Fullbright() {
        super("fullbright", "Fullbright", Category.RENDER, "Night vision without the potion: boosts gamma beyond 100%.");
        instance = this;
    }

    public static Fullbright instance() {
        return instance;
    }

    /** Handles the three keys; true when a setting changed. */
    public boolean tickKeys() {
        boolean changed = false;
        while (toggleKey.mapping().consumeClick()) {
            setEnabled(!enabled());
            changed = true;
        }
        while (upKey.mapping().consumeClick()) {
            adjust(1);
            changed = true;
        }
        while (downKey.mapping().consumeClick()) {
            adjust(-1);
            changed = true;
        }
        return changed;
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
