package dev.dusk.client.modules.toggle;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.client.Minecraft;

/**
 * Keeps the sprint (and optionally sneak) key held for you. Purely a
 * client-side key press: the server sees the same input as a finger on the
 * key, so nothing about movement changes.
 */
public class ToggleSprint extends Module {
    private final BoolSetting sprint = add(new BoolSetting("toggleSprint", "Toggle sprint", true));
    private final BoolSetting sneak = add(new BoolSetting("toggleSneak", "Toggle sneak", false));
    private final BoolSetting flyBoost = add(new BoolSetting("keepFlying", "Also while flying", true));

    private boolean sneakOn;
    private boolean sneakKeyWasDown;

    public ToggleSprint() {
        super("togglesprint", "Toggle Sprint", Category.MOVEMENT,
                "Holds sprint for you so you never have to double-tap. Optional toggle-sneak.");
        setEnabled(true);
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
