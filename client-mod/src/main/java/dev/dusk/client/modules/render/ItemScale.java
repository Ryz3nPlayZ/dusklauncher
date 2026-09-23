package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

/**
 * BactroMod's "Item Scaling": shrinks the item model held in first person so
 * it covers less of the screen.
 *
 * <p>BactroMod keeps a per-item scale map driven by its own options screen;
 * this module applies one scale to whatever you are holding instead, because
 * the editor has no per-item list widget.
 */
public class ItemScale extends Module {
    private static ItemScale instance;

    private final IntSetting scale = add(new IntSetting("scale", "Item scale", 80, 1, 100, 1, "%"));

    public ItemScale() {
        super("itemscale", "Item Scale", Category.RENDER,
                "Shrinks the item in your hand so it hides less of the screen.");
        instance = this;
    }

    /** The factor first-person items are scaled by, 1 while the module is off. */
    public static float scale() {
        if (instance == null || !instance.enabled()) return 1f;
        return instance.scale.get() / 100f; // the setting is already bounded to 1..100
    }
}
