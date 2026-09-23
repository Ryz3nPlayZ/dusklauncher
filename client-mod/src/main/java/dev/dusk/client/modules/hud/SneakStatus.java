package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

/**
 * Flex-HUD's Toggle Sneak readout. Vanilla only exposes the logical sneak
 * state, so "Held" vs "Toggled" follows the toggle-crouch option rather than
 * the physical key (Flex-HUD reads the raw key through an access widener).
 */
public class SneakStatus extends TextHud {
    public SneakStatus() {
        super("sneakstatus", "Sneak Indicator", "", "Whether you are sneaking, and whether it is held or toggled.");
        setPosition(150, 126);
        showLabel.set(false);
    }

    @Override
    protected String value(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        if (!mc.options.keyShift.isDown()) return null;
        return mc.options.toggleCrouch().get() ? "Sneaking (Toggled)" : "Sneaking (Held)";
    }

    @Override
    protected String sample() {
        return "Sneaking (Toggled)";
    }
}
