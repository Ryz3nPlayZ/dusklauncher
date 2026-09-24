package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;

import java.util.HashMap;
import java.util.Map;

/** A section heading that folds the settings under it. Fold state lasts for the session. */
public class GroupHeaderWidget extends Widget {
    private static final Map<String, Boolean> COLLAPSED = new HashMap<>();

    private final String key;
    private final String title;
    private final boolean defaultCollapsed;
    private final Runnable onToggle;

    public GroupHeaderWidget(String key, String title, boolean defaultCollapsed, Runnable onToggle) {
        this.key = key;
        this.title = title;
        this.defaultCollapsed = defaultCollapsed;
        this.onToggle = onToggle;
    }

    public boolean collapsed() {
        return COLLAPSED.getOrDefault(key, defaultCollapsed);
    }

    public void setCollapsed(boolean collapsed) {
        COLLAPSED.put(key, collapsed);
    }

    @Override
    public void render(Canvas c, int mouseX, int mouseY) {
        boolean hover = contains(mouseX, mouseY);
        if (hover) c.fill(x, y, x + w, y + h, Theme.ROW_HOVER);
        int ty = y + (h - c.lineHeight()) / 2 + 1;
        drawChevron(c, x + 4, y + h / 2 - 2, !collapsed(), hover ? Theme.ACCENT : Theme.TEXT_MUTED);
        c.text(title.toUpperCase(), x + 14, ty, Theme.ACCENT, false);
        int lx = x + 18 + c.textWidth(title.toUpperCase());
        if (lx < x + w - 4) Theme.divider(c, lx, x + w - 4, y + h / 2);
    }

    /** A 5px pixel chevron: pointing down when open, right when folded. */
    public static void drawChevron(Canvas c, int x, int y, boolean open, int color) {
        if (open) {
            for (int i = 0; i < 3; i++) c.fill(x + i, y + i, x + 5 - i, y + i + 1, color);
        } else {
            for (int i = 0; i < 3; i++) c.fill(x + i, y - 1 + i, x + i + 1, y + 4 - i, color);
        }
    }

    @Override
    public boolean click(double mx, double my, int button) {
        if (!contains(mx, my) || button != 0) return false;
        setCollapsed(!collapsed());
        onToggle.run();
        return true;
    }
}
