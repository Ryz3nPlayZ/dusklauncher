package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Vanilla;

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
        int ty = y + (h - c.lineHeight()) / 2 + 2;
        drawChevron(c, x + 6, ty + 1, !collapsed(), hover ? Vanilla.TEXT : Vanilla.TEXT_OFF);
        c.text(title, x + 16, ty, hover ? 0xFFFFFFA0 : Vanilla.TEXT, true);
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
