package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.gui.Vanilla;
import dev.dusk.client.module.setting.Setting;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One row of a config list, laid out like Flex-HUD's entries: the label on
 * the left, the control on the right and, for a setting, a 20x20 reset
 * button at the far right that lights up once the value differs from its
 * default.
 */
public abstract class SettingRow extends Widget {
    public static final int CONTROL_W = 120, SQUARE = 20, GAP = 4;

    protected final String label;
    @Nullable private Setting<?> resettable;
    @Nullable private Runnable onReset;

    protected SettingRow(String label) {
        this.label = label;
    }

    /** Adds the reset button for {@code setting}; {@code onReset} runs after it resets. */
    public SettingRow resets(Setting<?> setting, Runnable onReset) {
        this.resettable = setting;
        this.onReset = onReset;
        return this;
    }

    protected int top() { return y + (h - SQUARE) / 2; }

    private int resetX() { return x + w - SQUARE; }

    protected int controlWidth() { return CONTROL_W; }

    protected int controlX() {
        return (resettable == null ? x + w : resetX() - GAP) - controlWidth();
    }

    protected boolean inControl(double mx, double my) {
        return Vanilla.inside(mx, my, controlX(), top(), controlWidth(), SQUARE);
    }

    private boolean changed() {
        return resettable != null && !Objects.deepEquals(resettable.get(), resettable.defaultValue());
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        renderRow(c, mouseX, mouseY);
        Px.label(c, label, x, y, controlX() - x, h, Px.Size.S20, Px.Tone.PLAIN, false, false, true, 0xFF);
        renderControl(c, mouseX, mouseY);
        if (resettable != null) {
            int rx = resetX(), ry = top();
            boolean on = changed();
            boolean hover = Vanilla.inside(mouseX, mouseY, rx, ry, SQUARE, SQUARE);
            Px.button(c, rx, ry, SQUARE, SQUARE, hover, on);
            Px.glyph(c, Icons.RESET, rx, ry, SQUARE, SQUARE, Px.Tone.GREY, hover && on, !on, 0xFF);
        }
    }

    /** Behind the label: nothing, unless the whole row is one control. */
    protected void renderRow(Canvas c, int mouseX, int mouseY) {}

    protected abstract void renderControl(Canvas c, int mouseX, int mouseY);

    @Override
    public boolean click(double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        if (resettable != null && Vanilla.inside(mx, my, resetX(), top(), SQUARE, SQUARE)) {
            if (button == 0 && changed()) {
                resettable.reset();
                if (onReset != null) onReset.run();
            }
            return true;
        }
        return clickControl(mx, my, button);
    }

    protected abstract boolean clickControl(double mx, double my, int button);
}
