package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Flex-HUD's toggle row: the whole row is the button, the tick box sits at its right end. */
public class ToggleWidget extends SettingRow {
    private final BooleanSupplier get;
    private final Consumer<Boolean> set;

    public ToggleWidget(String label, BooleanSupplier get, Consumer<Boolean> set) {
        super(label);
        this.get = get;
        this.set = set;
    }

    @Override
    protected int controlWidth() { return SQUARE; }

    private boolean onRow(double mx, double my) {
        return Vanilla.inside(mx, my, x, top(), controlX() + SQUARE - x, SQUARE);
    }

    @Override
    protected void renderRow(Canvas c, int mouseX, int mouseY) {
        if (!onRow(mouseX, mouseY)) return;
        int w = controlX() + SQUARE - x;
        c.fill(x, top(), x + w, top() + SQUARE, Vanilla.ROW_HOVER);
        c.outline(x - 1, top() - 1, w + 2, SQUARE + 2, 0xFFFFFFFF);
    }

    @Override
    protected void renderControl(Canvas c, int mouseX, int mouseY) {
        Vanilla.toggleBox(c, controlX(), top(), SQUARE, get.getAsBoolean(), onRow(mouseX, mouseY), true);
    }

    @Override
    protected boolean clickControl(double mx, double my, int button) {
        if (button != 0 || !onRow(mx, my)) return false;
        set.accept(!get.getAsBoolean());
        return true;
    }
}
