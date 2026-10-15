package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Glass;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Round;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;

import java.util.ArrayList;
import java.util.List;

/**
 * A clipped, scrollable column of widgets. Each widget keeps its own
 * height; widgets inside a folded group are skipped. Call {@link #layout}
 * once per frame before rendering or routing input.
 *
 * <p>With {@link #cards} on in the modern look, each run of setting rows
 * sits in one rounded card with hairlines between the rows, the rows inset
 * from its edge; headings, notes and fields stay outside the cards.
 */
public class ScrollPane {
    private static final int STEP = 20;
    /** A row's inset from its card's edge, and the gap a card leaves after it. */
    private static final int CARD_INSET = 8, CARD_GAP = 6;

    public final List<Widget> widgets = new ArrayList<>();
    private int x, y, w, h;
    private int scroll, contentHeight;
    private Widget dragTarget;
    private boolean draggingBar, cards;

    public void clear() {
        widgets.clear();
        dragTarget = null;
    }

    public void add(Widget wd, int height) {
        wd.h = height;
        widgets.add(wd);
    }

    public void resetScroll() { scroll = 0; }

    /** Rows in rounded cards (the modern look only). */
    public ScrollPane cards(boolean cards) {
        this.cards = cards;
        return this;
    }

    private boolean carding() {
        return cards && Theme.modern();
    }

    public void layout(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        int cy = y - scroll;
        int cw = w - Vanilla.SCROLLBAR_W - 4; // room for the scrollbar
        boolean carding = carding();
        Widget prev = null;
        for (Widget wd : widgets) {
            if (wd.hidden()) continue;
            boolean in = carding && wd.carded();
            // a little air after a card before whatever follows it
            if (carding && prev != null && prev.carded() && !in) cy += CARD_GAP;
            if (in) wd.setBounds(x + CARD_INSET, cy, cw - 2 * CARD_INSET, wd.h);
            else wd.setBounds(x, cy, cw, wd.h);
            cy += wd.h;
            prev = wd;
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
        // a pixel spare on the left for the hover outline rows draw around themselves
        c.scissor(x - 1, y, x + w, y + h);
        if (carding()) drawCards(c, mx, my);
        for (Widget wd : widgets) {
            if (wd.hidden() || wd.y + wd.h < y || wd.y > y + h) continue;
            wd.render(c, mx, my);
        }
        c.unscissor();
        if (contentHeight > h) {
            int barH = Math.max(32, h * h / contentHeight);
            int barY = y + (h - barH) * scroll / Math.max(1, maxScroll());
            Px.scrollbar(c, x + w - Vanilla.SCROLLBAR_W, Vanilla.SCROLLBAR_W, barY, barH);
        }
    }

    /** The cards behind each run of rows, a hover wash on the row under the mouse and the hairlines between rows. */
    private void drawCards(Canvas c, int mx, int my) {
        int cw = w - Vanilla.SCROLLBAR_W - 4;
        Widget first = null, last = null;
        java.util.List<Widget> run = new ArrayList<>();
        for (int i = 0; i <= widgets.size(); i++) {
            Widget wd = i < widgets.size() ? widgets.get(i) : null;
            if (wd != null && wd.hidden()) continue;
            if (wd != null && wd.carded()) {
                if (first == null) first = wd;
                last = wd;
                run.add(wd);
                continue;
            }
            if (first != null) {
                drawCard(c, x, first.y, cw, last.y + last.h - first.y, run, mx, my);
                run.clear();
                first = last = null;
            }
        }
    }

    private void drawCard(Canvas c, int cx, int cy, int cw, int ch, java.util.List<Widget> run, int mx, int my) {
        if (cy > y + h || cy + ch < y) return;
        int r = Px.radius(ch, 18);
        Glass.pane(c, cx, cy, cw, ch, r, false, 0);
        Round.begin(c);
        int line = Px.line(), x0 = Round.w(cx + CARD_INSET), x1 = Round.w(cx + cw - CARD_INSET);
        for (int i = 0; i < run.size(); i++) {
            Widget wd = run.get(i);
            if (wd.contains(mx, my)) {
                // the wash stays inside the card's rounding
                int inset = Math.max(2, Round.w(3));
                Round.fillW(c, Round.w(cx) + inset, Round.w(wd.y) + inset, Round.w(cx + cw) - inset, Round.w(wd.y + wd.h) - inset,
                        Math.max(0, r - inset), Theme.HOVER);
            }
            if (i > 0) {
                int ly = Round.w(wd.y);
                c.fill(x0, ly, x1, ly + line, Theme.LINE);
            }
        }
        Round.end(c);
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
