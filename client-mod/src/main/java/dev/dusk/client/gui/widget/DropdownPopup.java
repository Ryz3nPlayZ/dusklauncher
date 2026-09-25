package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The open list of a dropdown, under its button (or above it when there is
 * no room). At most {@link #MAX_VISIBLE} options show; the wheel scrolls
 * the rest. Picking one, clicking outside or Esc closes it.
 */
public class DropdownPopup implements Popup {
    private static final int ROW = 16, MAX_VISIBLE = 8;

    private final int ax, ay, aw, ah;
    private final List<String> options;
    private final int selected;
    private final IntConsumer onPick;
    private int x, y, h, scroll, hovered = -1;
    private boolean closed;

    public DropdownPopup(int anchorX, int anchorY, int anchorW, int anchorH, List<String> options, int selected, IntConsumer onPick) {
        this.ax = anchorX;
        this.ay = anchorY;
        this.aw = anchorW;
        this.ah = anchorH;
        this.options = options;
        this.selected = selected;
        this.onPick = onPick;
        int visible = Math.min(MAX_VISIBLE, options.size());
        this.scroll = Math.max(0, Math.min(options.size() - visible, selected - visible / 2));
    }

    private int visible() { return Math.min(MAX_VISIBLE, options.size()); }

    private void place(int screenH) {
        h = visible() * ROW + 2;
        x = ax;
        y = ay + ah - 1;
        if (y + h > screenH - 2 && ay - h + 1 >= 2) y = ay - h + 1;
    }

    private int rowAt(double mx, double my) {
        if (!Vanilla.inside(mx, my, x, y + 1, aw, h - 2)) return -1;
        int i = scroll + (int) ((my - y - 1) / ROW);
        return i < options.size() ? i : -1;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY, int screenW, int screenH) {
        place(screenH);
        c.fill(x, y, x + aw, y + h, 0xF0000000);
        c.outline(x, y, aw, h, 0xFFA0A0A0);
        hovered = rowAt(mouseX, mouseY);
        boolean bar = options.size() > visible();
        int textW = aw - 10 - (bar ? 4 : 0);
        for (int r = 0; r < visible(); r++) {
            int i = scroll + r, ry = y + 1 + r * ROW;
            if (i == hovered) c.fill(x + 1, ry, x + aw - 1, ry + ROW, 0x40FFFFFF);
            else if (i == selected) c.fill(x + 1, ry, x + aw - 1, ry + ROW, 0x20FFFFFF);
            int color = i == selected ? 0xFFFFFFA0 : Vanilla.TEXT;
            c.text(Theme.ellipsize(c, options.get(i), textW), x + 5, ry + (ROW - 8) / 2 + 1, color, true);
        }
        if (bar) {
            int trackH = h - 2, barH = Math.max(8, trackH * visible() / options.size());
            int barY = y + 1 + (trackH - barH) * scroll / (options.size() - visible());
            c.fill(x + aw - 4, barY, x + aw - 2, barY + barH, 0xFFA0A0A0);
        }
    }

    @Override
    public void click(double mx, double my, int button) {
        int i = rowAt(mx, my);
        if (i >= 0 && button == 0) onPick.accept(i);
        if (i >= 0 || !Vanilla.inside(mx, my, x, y, aw, h)) close();
    }

    @Override
    public void scroll(double mx, double my, double amount) {
        scroll = Math.max(0, Math.min(options.size() - visible(), scroll - (int) Math.signum(amount)));
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (hovered >= 0) onPick.accept(hovered);
            close();
            return true;
        }
        return false;
    }

    @Override
    public void close() { closed = true; }

    @Override
    public boolean closed() { return closed; }
}
