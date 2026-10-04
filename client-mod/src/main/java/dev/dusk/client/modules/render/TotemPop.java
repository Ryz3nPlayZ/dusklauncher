package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Small totem pop for crystal PvP, the way small-pop resource packs do it but
 * with any pack: shrinks the item that flies at the screen when a Totem of
 * Undying (or a server's custom pop item) goes off, can keep it in the middle
 * instead of drifting to a random side, or hide it. Written from the
 * behaviour of Totem Tweaks / Totem Plus (both ARR, no code used).
 */
public class TotemPop extends Module {
    private static TotemPop instance;

    private final IntSetting size = add(new IntSetting("size", "Pop size", 40, 10, 100, 5, "%"));
    private final BoolSetting centred = add(new BoolSetting("centred", "Keep centred", false));
    private final BoolSetting hide = add(new BoolSetting("hide", "Hide pop", false));

    public TotemPop() {
        super("totempop", "Totem Pop", Category.RENDER,
                "Makes the totem pop animation smaller, centred or hidden, whatever your resource pack does.");
        instance = this;
    }

    private static boolean on() {
        return instance != null && instance.enabled();
    }

    /** The factor the popping item's scale is multiplied by; 1 while the module is off. */
    public static float scale() {
        return on() ? instance.size.get() / 100f : 1f;
    }

    /** True when the pop should not wander off-centre. */
    public static boolean centred() {
        return on() && instance.centred.get();
    }

    /** True when the pop should not be drawn at all. */
    public static boolean hidden() {
        return on() && instance.hide.get();
    }
}
