package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.Keys;
import dev.dusk.client.hud.TextHud;

/** Flex-HUD's Toggle Sneak readout: "Sneaking (Held)" or "Sneaking (Toggled)". */
public class SneakStatus extends TextHud {
    public SneakStatus() {
        super("sneakstatus", "Sneak Status", "Whether you are sneaking, and whether it is held or toggled.");
        setPosition(150, 137);
    }

    @Override
    protected String text(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        boolean toggled = mc.options.toggleCrouch().get();
        var key = mc.options.keyShift;
        if (toggled) {
            if (Keys.physicallyDown(key)) return "Sneaking (Held)";
            if (key.isDown()) return "Sneaking (Toggled)";
            return null;
        }
        return key.isDown() ? "Sneaking (Held)" : null;
    }

    @Override
    protected String sample() {
        return "Sneaking (Toggled)";
    }
}
