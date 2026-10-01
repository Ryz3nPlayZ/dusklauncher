package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Draws the skin's outer layer (hat, jacket, sleeves, pants) as little
 * blocks instead of a flat shell, for players close to the camera. Past the
 * distance, and on any part armour covers, the vanilla layer stays.
 */
public class SkinLayers3D extends Module {
    public static final int HAT = 1, JACKET = 2, RIGHT_SLEEVE = 4, LEFT_SLEEVE = 8, RIGHT_PANTS = 16, LEFT_PANTS = 32;

    private static SkinLayers3D instance;

    private final IntSetting distance = add(new IntSetting("distance", "Within", 16, 4, 64, 4, " blocks"));
    private final BoolSetting head = add(new BoolSetting("head", "Hat", true), "Parts");
    private final BoolSetting body = add(new BoolSetting("body", "Jacket", true), "Parts");
    private final BoolSetting arms = add(new BoolSetting("arms", "Sleeves", true), "Parts");
    private final BoolSetting legs = add(new BoolSetting("legs", "Pants", true), "Parts");

    public SkinLayers3D() {
        super("skinlayers3d", "3D Skin Layers", Category.RENDER,
                "The outer skin layer as real 3D pixels on nearby players.");
        instance = this;
        setEnabled(true);
    }

    /**
     * Which outer-layer parts to draw as voxels (and hide the flat ones of):
     * those the player shows, no armour covers, within the distance.
     * {@code shown} uses the same bits.
     */
    public static int parts(double distanceSq, boolean invisible, int shown,
                            boolean helmet, boolean chest, boolean leggings, boolean boots) {
        SkinLayers3D m = instance;
        if (m == null || !m.enabled() || invisible || shown == 0) return 0;
        double d = m.distance.get();
        if (distanceSq > d * d) return 0;
        int out = 0;
        if (m.head.get() && !helmet) out |= HAT;
        if (m.body.get() && !chest) out |= JACKET;
        if (m.arms.get() && !chest) out |= RIGHT_SLEEVE | LEFT_SLEEVE;
        if (m.legs.get() && !leggings && !boots) out |= RIGHT_PANTS | LEFT_PANTS;
        return out & shown;
    }
}
