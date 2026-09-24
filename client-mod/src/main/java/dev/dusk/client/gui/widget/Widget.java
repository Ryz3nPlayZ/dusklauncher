package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;

/**
 * Minimal custom widget: the window lays it out every frame (absolute
 * coordinates, already scrolled) and forwards input. Vanilla widgets are
 * avoided so the same code draws on every game version.
 */
public abstract class Widget {
    public int x, y, w, h;
    protected boolean focused;
    /** The collapsible section this widget sits in, if any; hidden while it is folded. */
    public GroupHeaderWidget group;

    public boolean hidden() {
        return group != null && group.collapsed();
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    public abstract void render(Canvas c, int mouseX, int mouseY);

    /** Returns true when the click was consumed. */
    public boolean click(double mx, double my, int button) { return false; }

    public void drag(double mx, double my) {}

    public void release() {}

    public boolean keyPressed(int key, int modifiers) { return false; }

    public boolean charTyped(char ch) { return false; }

    public boolean focused() { return focused; }

    public void setFocused(boolean focused) { this.focused = focused; }
}
