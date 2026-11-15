package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;

/** FOV past the options slider (10 to 150), and sprint or speed FOV changes on or off. */
public class FovChanger extends Module {
    private static FovChanger instance;

    private final IntSetting fov = add(new IntSetting("fov", "Field of view", 90, 10, 150, 1, "°"));
    private final BoolSetting dynamic = add(new BoolSetting("dynamic", "Sprint and speed change the FOV", true));

    public FovChanger() {
        super("fovchanger", "FOV Changer", Category.RENDER,
                "Set the field of view past the slider, and turn sprint FOV changes off.");
        instance = this;
    }

    /** The FOV modifier to use (FOVUpdateEvent's newfov), given vanilla's. */
    public static float apply(float modifier) {
        FovChanger m = instance;
        if (m == null || !m.enabled()) return modifier;
        float vanilla = Minecraft.getMinecraft().gameSettings.fovSetting;
        return (m.dynamic.get() ? modifier : 1f) * m.fov.get() / Math.max(1f, vanilla);
    }
}
