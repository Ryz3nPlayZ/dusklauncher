package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

public class CpsCounter extends TextHud {
    private final BoolSetting showRight = add(new BoolSetting("showRight", "Show right clicks", true));

    public CpsCounter() {
        super("cps", "CPS", "CPS", "Clicks per second (left | right).");
        setPosition(5, 16);
        setEnabled(true);
    }

    @Override
    protected String value(HudContext ctx) {
        return showRight.get() ? ClickTracker.leftCps() + " | " + ClickTracker.rightCps() : Integer.toString(ClickTracker.leftCps());
    }
}
