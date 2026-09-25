package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.ChoiceSetting;

/** A vanilla button showing the current choice; clicking it drops the full list down. */
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
        Vanilla.button(c, x, y, w, h, hover, true);
        c.text(Theme.ellipsize(c, value, w - 22), x + 6, y + (h - 8) / 2 + 1, Vanilla.TEXT, true);
        Icons.DOWN.draw(c, x + w - 11, y + (h - 4) / 2 + 1, 0xFF3F3F3F);
        Icons.DOWN.draw(c, x + w - 12, y + (h - 4) / 2, Vanilla.TEXT);
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
