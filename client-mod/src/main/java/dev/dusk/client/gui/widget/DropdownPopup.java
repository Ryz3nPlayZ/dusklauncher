package dev.dusk.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Vanilla;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * The open list of a dropdown, under its button (or above it when there is
 * no room). At most {@link #MAX_VISIBLE} options show; the wheel scrolls
 * the rest. Picking one, clicking outside or Esc closes it.
 */
public class DropdownPopup implements Popup {
    private static final int ROW = 16, MAX_VISIBLE = 8;
    /** The panel's frame (2px black + 3px band at the launcher's scale), rounded up to GUI pixels. */
    private static final int INSET = 3;

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
        h = visible() * ROW + 2 * INSET;
        x = ax;
        y = ay + ah - 1;
        if (y + h > screenH - 2 && ay - h + 1 >= 2) y = ay - h + 1;
    }

    private int rowAt(double mx, double my) {
        if (!Vanilla.inside(mx, my, x, y + INSET, aw, h - 2 * INSET)) return -1;
        int i = scroll + (int) ((my - y - INSET) / ROW);
        return i < options.size() ? i : -1;
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY, int screenW, int screenH) {
        place(screenH);
        Px.panel(c, x, y, aw, h);
        hovered = rowAt(mouseX, mouseY);
        boolean bar = options.size() > visible();
        int rowW = aw - 2 * INSET - (bar ? Vanilla.SCROLLBAR_W : 0);
        for (int r = 0; r < visible(); r++) {
            int i = scroll + r, ry = y + INSET + r * ROW;
            if (i == hovered) c.fill(x + INSET, ry, x + INSET + rowW, ry + ROW, 0xFF323232);
            Px.value(c, options.get(i), x + INSET, ry, rowW, ROW, i == selected ? Px.Tone.ACCENT : Px.Tone.GREY,
                    i == hovered, true);
        }
        if (bar) {
            int trackH = h - 2 * INSET, barH = Math.max(8, trackH * visible() / options.size());
            int barY = y + INSET + (trackH - barH) * scroll / (options.size() - visible());
            Px.scrollbar(c, x + aw - INSET - Vanilla.SCROLLBAR_W, Vanilla.SCROLLBAR_W, barY, barH);
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
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
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
