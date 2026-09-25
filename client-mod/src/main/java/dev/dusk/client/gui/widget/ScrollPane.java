package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;

import java.util.ArrayList;
import java.util.List;

/**
 * A clipped, scrollable column of widgets. Each widget keeps its own
 * height; widgets inside a folded group are skipped. Call {@link #layout}
 * once per frame before rendering or routing input.
 */
public class ScrollPane {
    private static final int STEP = 20;

    public final List<Widget> widgets = new ArrayList<>();
    private int x, y, w, h;
    private int scroll, contentHeight;
    private Widget dragTarget;
    private boolean draggingBar;

    public void clear() {
        widgets.clear();
        dragTarget = null;
    }

    public void add(Widget wd, int height) {
        wd.h = height;
        widgets.add(wd);
    }

    public void resetScroll() { scroll = 0; }

    public void layout(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        int cy = y - scroll;
        int cw = w - Vanilla.SCROLLBAR_W - 4; // room for the scrollbar
        for (Widget wd : widgets) {
            if (wd.hidden()) continue;
            wd.setBounds(x, cy, cw, wd.h);
            cy += wd.h;
        }
        contentHeight = cy + scroll - y;
        clamp();
    }

    private void clamp() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    private int maxScroll() { return Math.max(0, contentHeight - h); }

    public boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    public void render(Canvas c, int mouseX, int mouseY) {
        boolean inside = contains(mouseX, mouseY);
        int mx = inside ? mouseX : -1, my = inside ? mouseY : -1;
        c.scissor(x, y, x + w, y + h);
        for (Widget wd : widgets) {
            if (wd.hidden() || wd.y + wd.h < y || wd.y > y + h) continue;
            wd.render(c, mx, my);
        }
        c.unscissor();
        if (contentHeight > h) {
            int barH = Math.max(32, h * h / contentHeight);
            int barY = y + (h - barH) * scroll / Math.max(1, maxScroll());
            Vanilla.scrollbar(c, x + w - Vanilla.SCROLLBAR_W, y, y + h, barY, barH);
        }
    }

    public boolean click(double mx, double my, int button) {
        if (!contains(mx, my)) {
            blur();
            return false;
        }
        if (contentHeight > h && mx >= x + w - Vanilla.SCROLLBAR_W) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        for (Widget wd : widgets) {
            if (wd.hidden()) continue;
            if (wd.click(mx, my, button)) {
                dragTarget = wd;
                for (Widget other : widgets) if (other != wd && other.focused()) other.setFocused(false);
                return true;
            }
        }
        blur();
        return true;
    }

    private void dragBar(double my) {
        scroll = (int) ((my - y) / h * contentHeight - h / 2.0);
        clamp();
    }

    public boolean drag(double mx, double my) {
        if (draggingBar) { dragBar(my); return true; }
        if (dragTarget == null) return false;
        dragTarget.drag(mx, my);
        return true;
    }

    public boolean release() {
        boolean had = draggingBar || dragTarget != null;
        draggingBar = false;
        if (dragTarget != null) dragTarget.release();
        dragTarget = null;
        return had;
    }

    public boolean scroll(double mx, double my, double amount) {
        if (!contains(mx, my)) return false;
        scroll -= (int) Math.signum(amount) * STEP;
        clamp();
        return true;
    }

    public boolean keyPressed(int key, int modifiers) {
        for (Widget wd : widgets) if (wd.focused() && wd.keyPressed(key, modifiers)) return true;
        return false;
    }

    public boolean charTyped(char ch) {
        for (Widget wd : widgets) if (wd.focused() && wd.charTyped(ch)) return true;
        return false;
    }

    public boolean anyFocused() {
        for (Widget wd : widgets) if (wd.focused()) return true;
        return false;
    }

    public void blur() {
        for (Widget wd : widgets) if (wd.focused()) wd.setFocused(false);
    }
}
