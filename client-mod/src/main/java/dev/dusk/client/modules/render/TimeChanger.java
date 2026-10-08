package dev.dusk.client.modules.render;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;

/**
 * PolyTime: holds the sky at a time of day for you alone. The server's clock
 * is untouched, so mobs, crops and everything else still run on world time.
 *
 * <p>PolyTime's "use IRL time" and "use IRL lunar phase" options are not
 * ported: they geolocate the player by IP and pull sunrise/sunset from a web
 * service, which is not something a HUD toggle should do behind your back.
 * The slider steps in whole hours rather than PolyTime's half hours, because
 * the editor's sliders are integer-valued.
 */
public class TimeChanger extends Module {
    private static TimeChanger instance;

    private final IntSetting time = add(new IntSetting("time", "Time of day", 12, 0, 24, 1, "h"));
    // PolyTime's own defaults; they step the slider by an hour
    private final KeySetting forwardKey = add(new KeySetting("time_forward", "Hour forward key", InputConstants.KEY_RBRACKET));
    private final KeySetting backwardKey = add(new KeySetting("time_backward", "Hour back key", InputConstants.KEY_LBRACKET));

    public TimeChanger() {
        super("timechanger", "Time Changer", Category.RENDER,
                "Locks the sky to a time of day for you alone.");
        instance = this;
    }

    /**
     * PolyTime's mapping: drop the day the clock is on, then place the hour
     * inside it. Hour zero is 18000 ticks (midnight) and an hour is 1000.
     */
    public static long dayTime(long original) {
        TimeChanger module = instance;
        if (module == null || !module.enabled()) return original;
        return original - Math.floorMod(original, 24000L) + module.time.get() * 1000L + 18000L;
    }

    /** The two keybinds PolyTime ships; the setting clamps at either end. True when it moved. */
    public boolean tickKeys() {
        boolean changed = false;
        while (forwardKey.mapping().consumeClick()) {
            time.set(time.get() + 1);
            changed = true;
        }
        while (backwardKey.mapping().consumeClick()) {
            time.set(time.get() - 1);
            changed = true;
        }
        return changed;
    }

    public static TimeChanger instance() {
        return instance;
    }
}
