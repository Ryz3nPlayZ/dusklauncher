package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/**
 * BactroMod's "Shield Height": lowers a raised shield so it covers less of
 * the screen. Same -100..100 scale as BactroMod, negative moving it down.
 */
public class LowShield extends Module {
    private static LowShield instance;

    private final IntSetting offset = add(new IntSetting("offset", "Shield height", -20, -100, 100, 1, "%"));

    public LowShield() {
        super("lowshield", "Low Shield", Category.RENDER,
                "Moves the first-person shield down out of your view.");
        instance = this;
    }

    public static float offsetY() {
        if (instance == null || !instance.enabled()) return 0;
        return instance.offset.get() / 100f; // the setting is already bounded to -100..100
    }
}
