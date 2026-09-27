package dev.dusk.client.modules.hud;

import dev.dusk.client.DuskClient;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.Keys;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.modules.toggle.ToggleSprint;

/** Flex-HUD's Toggle Sprint readout: "Sprinting (Held)", "(Toggled)" or "(Vanilla)". */
public class SprintStatus extends TextHud {
    public SprintStatus() {
        super("sprintstatus", "Toggle Sprint", "Whether you are sprinting, and whether it is held or toggled.");
        setPosition(150, 104);
        setEnabled(true);
    }

    @Override
    protected String text(HudContext ctx) {
        var mc = ctx.mc();
        var p = mc.player;
        if (p == null) return null;
        ToggleSprint toggle = DuskClient.modules() == null ? null : DuskClient.modules().get(ToggleSprint.class);
        boolean toggled = mc.options.toggleSprint().get() || toggle != null && toggle.sprintToggled();
        var key = mc.options.keySprint;
        if (toggled) {
            if (Keys.physicallyDown(key)) return "Sprinting (Held)";
            if (key.isDown()) return "Sprinting (Toggled)";
            if (p.isSprinting()) return "Sprinting (Vanilla)";
            return null;
        }
        if (key.isDown()) return "Sprinting (Held)";
        if (p.isSprinting()) return "Sprinting (Vanilla)";
        return null;
    }

    @Override
    protected String sample() {
        return "Sprinting (Toggled)";
    }
}
