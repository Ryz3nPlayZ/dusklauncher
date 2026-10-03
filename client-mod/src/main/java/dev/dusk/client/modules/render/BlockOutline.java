package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;

/** The outline around the block you're looking at: its colour and thickness. */
public class BlockOutline extends Module {
    private static BlockOutline instance;

    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0x66000000));
    private final IntSetting width = add(new IntSetting("width", "Thickness", 25, 10, 100, 5, "").decimals(1));

    public BlockOutline() {
        super("blockoutline", "Block Outline", Category.RENDER,
                "Change the colour and thickness of the block selection outline.");
        instance = this;
    }

    public static int color(int vanilla) {
        return instance != null && instance.enabled() ? instance.color.argb() : vanilla;
    }

    /** Vanilla's line width scaled by the setting (2.5 = vanilla). */
    public static float width(float vanilla) {
        return instance != null && instance.enabled() ? vanilla * instance.width.get() / 25f : vanilla;
    }
}
