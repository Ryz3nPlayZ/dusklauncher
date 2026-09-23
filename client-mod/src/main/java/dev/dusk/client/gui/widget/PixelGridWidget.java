package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.PixelGridSetting;

/**
 * Paints a {@link PixelGridSetting}: left-drag sets a cell to the paint
 * colour, right-drag clears it. The checkerboard behind the grid shows
 * which cells are transparent.
 */
public class PixelGridWidget extends Widget {
    private static final int CELL = 7;
    private static final int LABEL_H = 11;

    private final PixelGridSetting setting;
    private final ColorSetting paintColor;
    private final Runnable onChange;
    private final Runnable onPaint;
    private boolean painting;
    private int paintValue;

    public PixelGridWidget(PixelGridSetting setting, ColorSetting paintColor, Runnable onChange, Runnable onPaint) {
        this.setting = setting;
        this.paintColor = paintColor;
        this.onChange = onChange;
        this.onPaint = onPaint;
    }

    /** Row height the window should give this widget. */
    public int preferredHeight() {
        return LABEL_H + setting.height() * CELL + 6;
    }

    private int gridX() { return x + 4; }

    private int gridY() { return y + LABEL_H; }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        c.text(setting.name(), x + 4, y + 1, Theme.TEXT, false);
        int gx = gridX(), gy = gridY();
        int w = setting.width() * CELL, h = setting.height() * CELL;
        c.outline(gx - 1, gy - 1, w + 2, h + 2, Theme.BORDER);
        for (int py = 0; py < setting.height(); py++) {
            for (int px = 0; px < setting.width(); px++) {
                int cx = gx + px * CELL, cy = gy + py * CELL;
                int checker = ((px + py) % 2 == 0) ? 0xFF2A2A2E : 0xFF232327;
                c.fill(cx, cy, cx + CELL, cy + CELL, checker);
                int argb = setting.pixel(px, py);
                if ((argb >>> 24) != 0) c.fill(cx, cy, cx + CELL, cy + CELL, argb);
            }
        }
        // centre guides, so a crosshair can be lined up on the middle cell
        int midX = gx + (setting.width() / 2) * CELL;
        int midY = gy + (setting.height() / 2) * CELL;
        c.outline(midX, midY, CELL, CELL, Theme.ACCENT);
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (!inGrid(mx, my)) return false;
        painting = true;
        paintValue = button == 1 ? 0 : paintColor.argb();
        paint(mx, my);
        return true;
    }

    @Override
    public void drag(double mx, double my) {
        if (!painting || !inGrid(mx, my)) return;
        paint(mx, my);
    }

    @Override
    public void release() {
        painting = false;
    }

    private boolean inGrid(double mx, double my) {
        return mx >= gridX() && my >= gridY()
                && mx < gridX() + setting.width() * CELL
                && my < gridY() + setting.height() * CELL;
    }

    private void paint(double mx, double my) {
        int px = (int) ((mx - gridX()) / CELL);
        int py = (int) ((my - gridY()) / CELL);
        if (setting.pixel(px, py) == paintValue) return;
        setting.setPixel(px, py, paintValue);
        onPaint.run();
        onChange.run();
    }
}
