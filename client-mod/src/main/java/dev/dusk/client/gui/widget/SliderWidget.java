package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.IntSetting;

/**
 * The launcher's range as a Px slider with the value written on it; in the
 * modern look a thin track with a round knob and the value in a pill beside
 * it. Arrow keys step it while focused.
 */
public class SliderWidget extends SettingRow {
    private final IntSetting setting;
    private final Runnable onChange;
    private boolean dragging;

    public SliderWidget(IntSetting setting, Runnable onChange) {
        super(setting.name());
        this.setting = setting;
        this.onChange = onChange;
    }

    private static final int VALUE_W = 34, KNOB = 10;

    /** The modern track's ends: the knob's centre runs between them. */
    private int trackX0() { return controlX() + KNOB / 2 + 1; }
    private int trackX1() { return controlX() + controlWidth() - VALUE_W - 6 - KNOB / 2; }

    private boolean onTrack(double mx, double my) {
        return Vanilla.inside(mx, my, controlX(), top(), trackX1() + KNOB / 2 + 2 - controlX(), SQUARE);
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        if (Theme.modern()) {
            renderModern(c, dragging || onTrack(mouseX, mouseY));
            return;
        }
        Px.slider(c, controlX(), top(), controlWidth(), SQUARE, setting.fraction(), setting.display(),
                dragging || inControl(mouseX, mouseY));
    }

    private void renderModern(Canvas c, boolean hot) {
        double f = Math.max(0, Math.min(1, setting.fraction()));
        int mid = top() + SQUARE / 2;
        Round.begin(c);
        int x0 = Round.w(trackX0()), x1 = Round.w(trackX1()), th = Math.max(2, Round.w(2));
        int ty = Round.w(mid) - th / 2, kx = (int) Math.round(x0 + f * (x1 - x0));
        Round.fillW(c, x0, ty, x1, ty + th, 64, Theme.TRACK);
        Round.fillW(c, x0, ty, Math.max(x0 + th, kx), ty + th, 64, Theme.ACCENT);
        int d = Round.w(KNOB), ky = Round.w(mid) - d / 2;
        if (hot) Round.fillW(c, kx - d, ky - d / 2, kx + d, ky + d + d / 2, 64, Theme.HOVER);
        Round.fillW(c, kx - d / 2, ky, kx - d / 2 + d, ky + d, 64, hot ? 0xFFFFFFFF : Theme.TEXT);
        Round.end(c);
        // the value, in a pill at the end of the slot
        int vx = controlX() + controlWidth() - VALUE_W;
        Round.fill(c, vx, top() + 2, VALUE_W, SQUARE - 4, 64, Theme.RAISED, Theme.LINE_2);
        Px.sans(c, setting.display(), vx, top() + 2, VALUE_W, SQUARE - 4, Px.Size.S14, Theme.TEXT, false, false);
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (Theme.modern() && inControl(mx, my) && !onTrack(mx, my)) return button == 0;
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
        // the knob's centre follows the mouse (Px.slider's handle is 8 wide on a standard row)
        if (Theme.modern()) setting.setFraction((mx - trackX0()) / (double) Math.max(1, trackX1() - trackX0()));
        else setting.setFraction((mx - controlX() - 4) / (controlWidth() - 8));
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
