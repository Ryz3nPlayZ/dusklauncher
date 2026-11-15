package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Theme;
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
        if (Theme.modern()) {
            // a section title over its card, the fold chevron trailing it; it sits low so it reads with the card below
            String t = Theme.sentence(title);
            int color = hover ? Theme.TEXT : Theme.TEXT_MUTED;
            int ly = y + h - 16, tw = Math.round(Px.sansWidth(c, t, Px.Size.S14, true));
            Px.sans(c, t, x + 8 - Px.pad(), ly, w - 24 + Px.pad(), 12, Px.Size.S14, color, true, true);
            drawChevron(c, x + 8 + Math.min(tw, w - 32) + 5, ly + 4, !collapsed(), hover ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
            return;
        }
        drawChevron(c, x + 6, ty + 1, !collapsed(), hover ? Vanilla.TEXT : Vanilla.TEXT_OFF);
        c.text(title, x + 16, ty, hover ? 0xFFFFFFA0 : Vanilla.TEXT, true);
    }

    @Override
    public boolean carded() { return false; }

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
