package dev.dusk.client.modules.hud;

import dev.dusk.client.DuskClient;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.modules.movement.ToggleSprint;
import dev.dusk.client.util.Keys;

public class SneakStatus extends TextHud {
    public SneakStatus() {
        super("sneakstatus", "Sneak Status", "Whether you are sneaking, and whether it is held or toggled.");
        setPosition(150, 137);
    }

    @Override
    protected String text(HudContext ctx) {
        if (ctx.player() == null) return null;
        if (Keys.isDown(ctx.mc().gameSettings.keyBindSneak.getKeyCode())) return "Sneaking (Held)";
        ToggleSprint toggle = DuskClient.modules().get(ToggleSprint.class);
        return toggle != null && toggle.sneakToggled() ? "Sneaking (Toggled)" : null;
    }

    @Override
    protected String sample() { return "Sneaking (Toggled)"; }
}
