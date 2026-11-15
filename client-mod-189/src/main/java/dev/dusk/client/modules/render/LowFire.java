package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/** Moves the first-person fire overlay down so it covers less of the screen. Behaviour after BactroMod. */
public class LowFire extends Module {
    private static LowFire instance;

    private final IntSetting offset = add(new IntSetting("offset", "Height", -30, -100, 100, 1, "%"));

    public LowFire() {
        super("lowfire", "Low Fire", Category.RENDER, "Lowers the fire overlay when you're burning.");
        instance = this;
    }

    /** How far to move the overlay, in its own units; 0 when off. */
    public static float offsetY() {
        LowFire m = instance;
        return m == null || !m.enabled() ? 0f : m.offset.get() / 100f;
    }
}
