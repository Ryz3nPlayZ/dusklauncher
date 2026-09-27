package dev.dusk.client.modules.hud;

import dev.dusk.client.DuskClient;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.Keys;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.modules.toggle.ToggleSprint;

/** Flex-HUD's Toggle Sneak readout: "Sneaking (Held)" or "Sneaking (Toggled)". */
public class SneakStatus extends TextHud {
    public SneakStatus() {
        super("sneakstatus", "Toggle Sneak", "Whether you are sneaking, and whether it is held or toggled.");
        setPosition(150, 126);
    }

    @Override
    protected String text(HudContext ctx) {
        var mc = ctx.mc();
        if (mc.player == null) return null;
        ToggleSprint toggle = DuskClient.modules() == null ? null : DuskClient.modules().get(ToggleSprint.class);
        boolean toggled = mc.options.toggleCrouch().get() || toggle != null && toggle.sneakToggleMode();
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
