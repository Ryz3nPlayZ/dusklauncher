package dev.dusk.client.modules.toggle;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;

/**
 * Keeps the sprint (and optionally sneak) key held for you. Purely a
 * client-side key press: the server sees the same input as a finger on the
 * key, so nothing about movement changes.
 */
public class ToggleSprint extends Module {
    private static ToggleSprint instance;

    private final BoolSetting sprint = add(new BoolSetting("toggleSprint", "Toggle sprint", true));
    private final BoolSetting sneak = add(new BoolSetting("toggleSneak", "Toggle sneak", false));
    private final BoolSetting flyBoost = add(new BoolSetting("keepFlying", "Also while flying", true));

    /**
     * PolySprint's two extras. They ride on client internals only 1.21.11 and
     * up have, so on older targets they are left off the module rather than
     * shown as switches that do nothing.
     */
    private final BoolSetting noWTap = new BoolSetting("noWTap", "Disable W-tap sprint", false);
    private final BoolSetting boostFlight = new BoolSetting("flyBoost", "Fly boost (singleplayer)", false);
    private final IntSetting boostAmount = new IntSetting("flyBoostAmount", "Fly boost amount", 4, 1, 10, 1, "x");

    private boolean sneakOn;
    private boolean sneakKeyWasDown;

    public ToggleSprint() {
        super("togglesprint", "Toggle Sprint", Category.MOVEMENT,
                "Holds sprint for you so you never have to double-tap. Optional toggle-sneak.");
        if (Compat.MODERN_CLIENT_HOOKS) {
            add(noWTap);
            add(boostFlight);
            add(boostAmount);
        }
        instance = this;
        setEnabled(true);
    }

    /**
     * PolySprint's "Disable W-Tap Sprint": the double-tap timer is cleared
     * every tick, so letting go of forward and pressing it again never starts
     * a sprint of its own.
     */
    public static boolean disablesWTap() {
        ToggleSprint m = instance;
        return m != null && m.enabled() && m.noWTap.get();
    }

    /**
     * The flight-speed multiplier, or 0 when the boost is off. PolySprint
     * keeps this to singleplayer, where the speed is nobody else's business.
     */
    public static float flyBoost() {
        ToggleSprint m = instance;
        if (m == null || !m.enabled() || !m.boostFlight.get()) return 0f;
        if (!Minecraft.getInstance().hasSingleplayerServer()) return 0f;
        return m.boostAmount.get();
    }

    public boolean sprintToggled() {
        return enabled() && sprint.get();
    }

    public boolean sneakToggled() {
        return enabled() && sneak.get() && sneakOn;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            sneakOn = false;
            return;
        }
        if (sprintToggled()) {
            boolean flying = mc.player.getAbilities().flying;
            if (!flying || flyBoost.get()) mc.options.keySprint.setDown(true);
        }
        if (sneak.get()) {
            boolean down = mc.options.keyShift.isDown();
            if (Compat.currentScreen(mc) == null && down && !sneakKeyWasDown) sneakOn = !sneakOn;
            sneakKeyWasDown = down;
            if (sneakOn) mc.options.keyShift.setDown(true);
        } else {
            sneakOn = false;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) sneakOn = false;
    }
}
