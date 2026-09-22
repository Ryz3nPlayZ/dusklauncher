package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.module.setting.IntSetting;

/** Label on the left, value on the right, a draggable track underneath. */
public class SliderWidget extends Widget {
    private static final int TRACK_H = 4;

    private final IntSetting setting;
    private final Runnable onChange;
    private boolean dragging;

    public SliderWidget(IntSetting setting, Runnable onChange) {
        this.setting = setting;
        this.onChange = onChange;
    }

    private int trackX() { return x + 4; }
    private int trackW() { return w - 8; }
    private int trackY() { return y + h - TRACK_H - 3; }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        if (contains(mouseX, mouseY) || dragging) c.fill(x, y, x + w, y + h, Theme.ROW_HOVER);
        c.text(setting.name(), x + 4, y + 3, Theme.TEXT, false);
        String v = setting.get() + setting.suffix();
        c.text(v, x + w - 4 - c.textWidth(v), y + 3, Theme.ACCENT, false);
        int tx = trackX(), ty = trackY(), tw = trackW();
        c.fill(tx, ty, tx + tw, ty + TRACK_H, Theme.TRACK);
        int filled = (int) Math.round(tw * setting.fraction());
        c.fill(tx, ty, tx + filled, ty + TRACK_H, Theme.ACCENT);
        int kx = tx + filled;
        c.fill(kx - 2, ty - 2, kx + 2, ty + TRACK_H + 2, Theme.TEXT);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        dragging = true;
        drag(mx, my);
        return true;
    }

    @Override
    public void drag(double mx, double my) {
        if (!dragging) return;
        int before = setting.get();
        setting.setFraction((mx - trackX()) / trackW());
        if (setting.get() != before) onChange.run();
    }

    @Override
    public void release() {
        dragging = false;
    }
}
