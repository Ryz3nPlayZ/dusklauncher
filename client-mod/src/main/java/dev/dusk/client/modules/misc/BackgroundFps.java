package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;

/** Caps the frame rate while the game window isn't focused, to save power and keep other apps smooth. */
public class BackgroundFps extends Module {
    private static BackgroundFps instance;

    private final IntSetting fps = add(new IntSetting("fps", "FPS in the background", 30, 5, 120, 5, " fps"));

    public BackgroundFps() {
        super("backgroundfps", "Background FPS", Category.MISC,
                "Lowers the frame rate while you're tabbed out of the game.");
        instance = this;
    }

    /** The frame limit to use, given the one vanilla picked. */
    public static int limit(int vanilla) {
        if (instance == null || !instance.enabled() || Minecraft.getInstance().isWindowActive()) return vanilla;
        return Math.min(vanilla, instance.fps.get());
    }
}
