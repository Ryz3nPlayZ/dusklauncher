package dev.dusk.client.modules.hud;

import dev.dusk.client.DuskClient;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.modules.toggle.ToggleSprint;

/** "Sprinting (Toggled)" style indicator, like the classic ToggleSprint text. */
public class SprintStatus extends TextHud {
    public SprintStatus() {
        super("sprintstatus", "Sprint Indicator", "", "Shows whether you are sprinting or sneaking, and whether it is toggled.");
        setPosition(150, 104);
        setEnabled(true);
        showLabel.set(false);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        if (p == null) return null;
        ToggleSprint toggle = DuskClient.modules() == null ? null : DuskClient.modules().get(ToggleSprint.class);
        boolean toggled = toggle != null && toggle.enabled();
        if (p.isShiftKeyDown()) return toggled && toggle.sneakToggled() ? "Sneaking (Toggled)" : "Sneaking";
        if (p.isSprinting()) return toggled && toggle.sprintToggled() ? "Sprinting (Toggled)" : "Sprinting";
        return null;
    }

    @Override
    protected String sample() {
        return "Sprinting (Toggled)";
    }
}
