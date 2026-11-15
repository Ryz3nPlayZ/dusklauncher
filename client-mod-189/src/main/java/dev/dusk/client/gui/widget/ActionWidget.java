package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.module.setting.ActionSetting;

/** A settings row whose control is a button. */
public class ActionWidget extends SettingRow {
    private final ActionSetting setting;
    private final Runnable onChange;

    public ActionWidget(ActionSetting setting, Runnable onChange) {
        super(setting.name());
        this.setting = setting;
        this.onChange = onChange;
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        boolean hover = inControl(mouseX, mouseY);
        Px.button(c, controlX(), top(), controlWidth(), SQUARE, hover, true);
        Px.label(c, setting.button(), controlX(), top(), controlWidth(), SQUARE, hover, false, 0xFF);
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (button != 0 || !inControl(mx, my)) return false;
        setting.run();
        onChange.run();
        return true;
    }
}
