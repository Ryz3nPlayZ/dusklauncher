package dev.dusk.client.modules.hud;

import dev.dusk.client.DuskClient;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.modules.movement.ToggleSprint;
import dev.dusk.client.util.Keys;
import net.minecraft.client.entity.EntityPlayerSP;

public class SprintStatus extends TextHud {
    public SprintStatus() {
        super("sprintstatus", "Sprint Status", "Whether you are sprinting, and whether it is held or toggled.");
        setPosition(150, 104);
        setEnabled(true);
    }

    @Override
    protected String text(HudContext ctx) {
        EntityPlayerSP p = ctx.player();
        if (p == null) return null;
        ToggleSprint toggle = DuskClient.modules().get(ToggleSprint.class);
        if (Keys.isDown(ctx.mc().gameSettings.keyBindSprint.getKeyCode())) return "Sprinting (Held)";
        if (toggle != null && toggle.sprintToggled()) return "Sprinting (Toggled)";
        if (p.isSprinting()) return "Sprinting (Vanilla)";
        return null;
    }

    @Override
    protected String sample() { return "Sprinting (Toggled)"; }
}
