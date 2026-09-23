package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;

/**
 * BactroMod's fog switches, one per fog environment. BactroMod phrases them
 * as "show this fog"; here each setting hides one instead, so a freshly
 * enabled module still looks like vanilla until you tick something.
 */
public class FogControl extends Module {
    private static FogControl instance;

    private final BoolSetting lava = add(new BoolSetting("lava", "Hide lava fog", true));
    private final BoolSetting powderSnow = add(new BoolSetting("powderSnow", "Hide powder snow fog", true));
    private final BoolSetting blindness = add(new BoolSetting("blindness", "Hide blindness fog", false));
    private final BoolSetting darkness = add(new BoolSetting("darkness", "Hide darkness fog", false));
    private final BoolSetting water = add(new BoolSetting("water", "Hide water fog", false));
    private final BoolSetting atmospheric = add(new BoolSetting("atmospheric", "Hide distance fog", false));

    /** The fog environments this module knows how to switch off. */
    public enum Fog { LAVA, POWDER_SNOW, BLINDNESS, DARKNESS, WATER, ATMOSPHERIC }

    public FogControl() {
        super("fogcontrol", "Fog Control", Category.RENDER,
                "Turns off the fog lava, powder snow, blindness, darkness, water or distance adds.");
        instance = this;
    }

    public static boolean hides(Fog fog) {
        FogControl m = instance;
        if (m == null || !m.enabled()) return false;
        return switch (fog) {
            case LAVA -> m.lava.get();
            case POWDER_SNOW -> m.powderSnow.get();
            case BLINDNESS -> m.blindness.get();
            case DARKNESS -> m.darkness.get();
            case WATER -> m.water.get();
            case ATMOSPHERIC -> m.atmospheric.get();
        };
    }
}
