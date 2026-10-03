package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;

/**
 * A field of view past vanilla's 30–110 range, and the speed/sprint/bow FOV
 * changes switched off on their own. Applied to the same factor zoom and
 * Behind You scale, so those still work on top; the held item keeps its FOV.
 */
public class FovChanger extends Module {
    private static FovChanger instance;

    private final IntSetting fov = add(new IntSetting("fov", "FOV", 90, 10, 150, 1, "°"));
    private final BoolSetting dynamic = add(new BoolSetting("dynamic", "Sprint, speed and bow FOV changes", true));

    public FovChanger() {
        super("fovchanger", "FOV Changer", Category.RENDER,
                "Any field of view from 10° to 150°, and the sprint and speed FOV changes on or off.");
        instance = this;
    }

    /** {@code modifier} is vanilla's dynamic FOV factor; returns the factor that lands on this module's FOV. */
    public static float apply(float modifier) {
        FovChanger m = instance;
        if (m == null || !m.enabled()) return modifier;
        int vanilla = Minecraft.getInstance().options.fov().get();
        if (vanilla <= 0) return modifier;
        return (m.dynamic.get() ? modifier : 1f) * m.fov.get() / vanilla;
    }
}
