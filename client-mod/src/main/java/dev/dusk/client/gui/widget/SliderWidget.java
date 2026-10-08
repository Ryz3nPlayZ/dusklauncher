package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.module.setting.IntSetting;

/** The launcher's range as a Px slider with the value written on it. Arrow keys step it while hovered. */
public class SliderWidget extends SettingRow {
    private final IntSetting setting;
    private final Runnable onChange;
    private boolean dragging;

    public SliderWidget(IntSetting setting, Runnable onChange) {
        super(setting.name());
        this.setting = setting;
        this.onChange = onChange;
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        Px.slider(c, controlX(), top(), controlWidth(), SQUARE, setting.fraction(), setting.display(),
                dragging || inControl(mouseX, mouseY));
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (button != 0 || !inControl(mx, my)) return false;
        dragging = true;
        setFocused(true);
        drag(mx, my);
        return true;
    }

    @Override
    public void drag(double mx, double my) {
        if (!dragging) return;
        int before = setting.get();
        // Px.slider's handle is 8 wide on a standard row; its centre follows the mouse
        setting.setFraction((mx - controlX() - 4) / (controlWidth() - 8));
        if (setting.get() != before) onChange.run();
    }

    @Override
    public void release() {
        dragging = false;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        int dir = key == InputConstants.KEY_LEFT ? -1 : key == InputConstants.KEY_RIGHT ? 1 : 0;
        if (dir == 0) return false;
        int before = setting.get();
        setting.set(before + dir * setting.step());
        if (setting.get() != before) onChange.run();
        return true;
    }
}
