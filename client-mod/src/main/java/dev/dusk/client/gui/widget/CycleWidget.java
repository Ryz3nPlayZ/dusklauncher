package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.module.setting.ChoiceSetting;

/** Label on the left, the current option as a button on the right; click cycles. */
public class CycleWidget extends Widget {
    private static final int BTN_W = 90;

    private final ChoiceSetting setting;
    private final Runnable onChange;

    public CycleWidget(ChoiceSetting setting, Runnable onChange) {
        this.setting = setting;
        this.onChange = onChange;
    }

    private int btnX() { return x + w - BTN_W - 4; }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        boolean hover = contains(mouseX, mouseY);
        if (hover) c.fill(x, y, x + w, y + h, Theme.ROW_HOVER);
        c.text(setting.name(), x + 4, y + (h - c.lineHeight()) / 2 + 1, Theme.TEXT, false);
        int bx = btnX(), by = y + 3, bh = h - 6;
        Theme.button(c, bx, by, BTN_W, bh, hover, Theme.Kind.NORMAL);
        c.centeredText("< " + setting.get() + " >", bx + BTN_W / 2, by + (bh - c.lineHeight()) / 2 + 1, Theme.TEXT, false);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (button == 0) setting.next();
        else if (button == 1) {
            int i = (setting.index() - 1 + setting.options().size()) % setting.options().size();
            setting.set(setting.options().get(i));
        } else return false;
        onChange.run();
        return true;
    }
}
