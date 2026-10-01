package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.gui.Px;
import dev.dusk.client.module.setting.ChoiceSetting;

/** A grey PxButton showing the current choice; clicking it drops the full list down. */
public class DropdownWidget extends SettingRow {
    private final ChoiceSetting setting;
    private final Runnable onChange;
    private final PopupHost host;
    private DropdownPopup open;

    public DropdownWidget(ChoiceSetting setting, Runnable onChange, PopupHost host) {
        super(setting.name());
        this.setting = setting;
        this.onChange = onChange;
        this.host = host;
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        boolean shown = open != null && !open.closed();
        drawButton(c, setting.get(), controlX(), top(), controlWidth(), SQUARE, shown || inControl(mouseX, mouseY));
    }

    /** The closed dropdown: value on the left, arrow on the right. Shared with the module list's filter. */
    public static void drawButton(Canvas c, String value, int x, int y, int w, int h, boolean hover) {
        Px.button(c, x, y, w, h, hover, true);
        int arrow = Math.min(h, 14);
        Px.value(c, value, x, y, w - arrow, h, Px.Tone.GREY, hover, true);
        Px.glyph(c, Icons.DOWN, x + w - arrow - Px.pad() / 2, y, arrow, h, Px.Tone.GREY, hover, false, 0xFF);
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (button != 0 || !inControl(mx, my)) return false;
        open = new DropdownPopup(controlX(), top(), controlWidth(), SQUARE, setting.options(), setting.index(), i -> {
            setting.set(setting.options().get(i));
            onChange.run();
        });
        host.open(open);
        return true;
    }
}
