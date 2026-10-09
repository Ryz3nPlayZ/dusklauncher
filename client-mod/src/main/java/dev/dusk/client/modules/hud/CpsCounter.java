package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ClickTracker;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

/** Flex-HUD's CPS: "8 | 3 CPS". */
public class CpsCounter extends TextHud {
    private final BoolSetting showLeft = add(new BoolSetting("showLeftClick", "Show left clicks", true));
    private final BoolSetting showRight = add(new BoolSetting("showRightClick", "Show right clicks", true));
    private final BoolSetting showSuffix = add(new BoolSetting("showSuffix", "Show \"CPS\"", true));

    public CpsCounter() {
        super("cps", "CPS", "Clicks per second (left | right).");
        setPosition(5, 16);
        setEnabled(true);
    }

    @Override
    protected String text(HudContext ctx) {
        if (!showLeft.get() && !showRight.get()) return null;
        String text = "";
        if (showLeft.get()) text = String.valueOf(ClickTracker.leftCps());
        if (showLeft.get() && showRight.get()) text += " | ";
        if (showRight.get()) text += ClickTracker.rightCps();
        if (showSuffix.get()) text += " CPS";
        return text;
    }

    @Override
    protected String sample() {
        String text = text(null);
        return text == null ? name() : text; // both sides off: still something to grab in the editor
    }
}
