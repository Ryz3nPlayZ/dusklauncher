package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.IntSetting;
import org.lwjgl.glfw.GLFW;

/** A vanilla slider with the value written on it. Arrow keys step it while hovered. */
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
        Vanilla.slider(c, controlX(), top(), controlWidth(), SQUARE, setting.fraction(), setting.display(),
                dragging || inControl(mouseX, mouseY), true);
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
        setting.setFraction((mx - controlX() - 4) / (controlWidth() - 8));
        if (setting.get() != before) onChange.run();
    }

    @Override
    public void release() {
        dragging = false;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        int dir = key == GLFW.GLFW_KEY_LEFT ? -1 : key == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
        if (dir == 0) return false;
        int before = setting.get();
        setting.set(before + dir * setting.step());
        if (setting.get() != before) onChange.run();
        return true;
    }
}
