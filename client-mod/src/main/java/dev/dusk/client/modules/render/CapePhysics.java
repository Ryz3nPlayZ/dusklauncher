package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.render.cape.CapeSim;
import net.minecraft.client.Minecraft;

/**
 * Capes that move: they trail behind when you run, lift when you fall,
 * sway when you turn and ripple while moving, instead of swinging as one
 * stiff board. Only players within the distance are simulated.
 */
public class CapePhysics extends Module {
    private static CapePhysics instance;

    private final IntSetting distance = add(new IntSetting("distance", "Within", 32, 8, 64, 8, " blocks"));
    private final IntSetting flutter = add(new IntSetting("flutter", "Ripple", 5, 0, 10, 1, ""));
    /** Global: how far every cape swings from its resting drape. */
    private final IntSetting sway = add(new IntSetting("sway", "Sway", 60, 0, 100, 10, "%"));

    public CapePhysics() {
        super("capephysics", "Cape Physics", Category.RENDER,
                "Capes trail, sway and ripple as players move instead of swinging stiffly.");
        instance = this;
        setEnabled(true);
    }

    public static boolean active() {
        CapePhysics m = instance;
        return m != null && m.enabled();
    }

    @Override
    public void tick() {
        CapeSim.tick(Minecraft.getInstance(), distance.get(), flutter.get() / 10F, sway.get() / 100F);
    }

    @Override
    protected void onDisable() {
        CapeSim.clear();
    }
}
