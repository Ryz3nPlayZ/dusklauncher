package dev.dusk.client.gui;

import dev.dusk.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The module list's chrome for other full pages (wardrobe, pack browser): a
 * centred panel, a tab bar with tools and an optional search box on the
 * right, and one scrollable area the page lays out.
 */
public abstract class PanelScreen extends MenuScreen {
    protected static final int TAB_H = 23, BAR_W = 4, SCROLL_STEP = 24;

    /** A tab-bar tool: an icon square, or a text button when {@code icon} is null. */
    protected record Tool(String id, @Nullable Icons icon, String text, String tip) {}

    private record Placed(Tool tool, int x, int w) {}

    protected int px, py, pw, ph, pad;
    /** The scrollable area: {@link #listX}..{@link #listX}+{@link #listW}, {@link #listY}..{@link #listBottom}. */
    protected int listX, listY, listW, listBottom;
    protected int scroll;
    private boolean draggingBar;
    private final List<Placed> placed = new ArrayList<>();
    private int[] tabX = new int[0], tabW = new int[0];

    protected PanelScreen(Component title, @Nullable Screen parent) {
        super(title, parent);
    }

    protected abstract String[] tabs();

    protected abstract int activeTab();

    protected abstract void selectTab(int i);

    /** Right-hand tools, rightmost first. */
    protected List<Tool> tools() {
        return List.of(new Tool("close", Icons.CLOSE, "", "Close"));
    }

    protected void onTool(String id) {
        if (id.equals("close")) exitMenu();
    }

    @Nullable
    protected TextFieldWidget search() {
        return null;
    }

    /** Height of everything in the scrollable area. */
    protected abstract int contentHeight();

    // ---- layout -----------------------------------------------------------

    protected int bodyY() { return py + TAB_H - 1; }

    protected void layoutPanel() {
        int minW = Math.min(400, this.width - 16), minH = Math.min(220, this.height - 16);
        pw = Math.max(minW, Math.min(this.width - 16, Math.round(this.width * 0.76f)));
        ph = Math.max(minH, Math.min(this.height - 16, Math.round(this.height * 0.72f)));
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        pad = Math.max(6, Math.round(pw * 0.025f));

        placed.clear();
        int rx = px + pw;
        for (Tool t : tools()) {
            int w = t.icon != null ? TAB_H : this.font.width(t.text) + 16;
            rx -= w - 1;
            placed.add(new Placed(t, rx, w));
        }
        String[] tabs = tabs();
        tabX = new int[tabs.length];
        tabW = new int[tabs.length];
        int x = px;
        for (int i = 0; i < tabs.length; i++) {
            int w = this.font.width(tabs[i]) + 22;
            tabX[i] = x;
            tabW[i] = w;
            x += w - 1;
        }
        TextFieldWidget s = search();
        if (s != null) {
            int sw = Math.max(0, Math.min(170, rx - x));
            s.setBounds(rx - sw + 5, py + 4, Math.max(0, sw - 8), TAB_H - 8);
        }
    }

    protected int maxScroll() {
        return Math.max(0, contentHeight() - (listBottom - listY));
    }

    protected void clampScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    protected boolean inList(double mx, double my) {
        return mx >= listX && mx < listX + listW && my >= listY && my < listBottom;
    }

    private int barX() { return listX + listW + 3; }

    // ---- drawing ------------------------------------------------------------

    protected void drawPanel(Canvas c, int mouseX, int mouseY) {
        if (inWorld()) c.fill(0, 0, this.width, this.height, 0x4D000000);
        int by = bodyY();
        c.fill(px + 1, by + 1, px + pw - 1, py + ph - 1, 0xE61E1E1E);
        c.outline(px, by, pw, py + ph - by, 0xFF000000);

        Theme.plate(c, px, py, pw, TAB_H, Theme.SURFACE, Theme.SURFACE, false);
        int ty = py + (TAB_H - 7) / 2;
        String[] tabs = tabs();
        for (int i = 0; i < tabs.length; i++) {
            int x = tabX[i], w = tabW[i];
            boolean active = i == activeTab(), hover = Vanilla.inside(mouseX, mouseY, x, py, w, TAB_H);
            if (active || hover) c.fill(x + 1, py + 2, x + w - 1, py + TAB_H - 2, active ? 0xFF2A2A2A : 0xFF242424);
            if (i > 0) Theme.vDivider(c, x, py + 1, py + TAB_H - 1);
            boolean bright = active || hover;
            Theme.label(c, tabs[i], x + (w - c.textWidth(tabs[i])) / 2, ty,
                    bright ? Theme.ACTIVE_UP : Theme.LABEL_UP, bright ? Theme.ACTIVE_LO : Theme.LABEL_LO, 1f);
            if (active) c.fill(x + 3, py + TAB_H - 4, x + w - 3, py + TAB_H - 3, Theme.ACCENT);
        }
        TextFieldWidget s = search();
        if (s != null && s.w >= 30) {
            s.render(c, mouseX, mouseY);
            if (s.text().isEmpty() && !s.focused()) Icons.SEARCH.draw(c, s.x + s.w - 11, s.y + (s.h - 7) / 2, Theme.TEXT_FAINT);
        }
        for (Placed p : placed) {
            Theme.vDivider(c, p.x, py + 1, py + TAB_H - 1);
            boolean hover = Vanilla.inside(mouseX, mouseY, p.x, py, p.w, TAB_H);
            if (hover) c.fill(p.x + 1, py + 2, p.x + p.w - 1, py + TAB_H - 2, 0xFF242424);
            boolean close = p.tool.id.equals("close");
            int col = close && hover ? Theme.RED_UP : hover ? Theme.ACTIVE_UP : Theme.LABEL_UP;
            if (p.tool.icon != null) {
                p.tool.icon.draw(c, p.x + (p.w - p.tool.icon.width()) / 2, py + (TAB_H - p.tool.icon.height()) / 2, col);
            } else {
                Theme.label(c, p.tool.text, p.x + (p.w - c.textWidth(p.tool.text)) / 2, ty,
                        col, hover ? Theme.ACTIVE_LO : Theme.LABEL_LO, 1f);
            }
            if (hover && !p.tool.tip.isEmpty()) Vanilla.tooltip(c, p.tool.tip, mouseX, mouseY, this.width, this.height);
        }
    }

    protected void drawScrollbar(Canvas c) {
        if (maxScroll() <= 0) return;
        int h = listBottom - listY, barH = Math.max(16, h * h / contentHeight());
        int barY = listY + (h - barH) * scroll / maxScroll();
        c.fill(barX(), listY, barX() + BAR_W, listBottom, 0xFF141414);
        c.fill(barX(), barY, barX() + BAR_W, barY + barH, draggingBar ? Theme.LABEL_UP : Theme.LABEL_LO);
    }

    // ---- input --------------------------------------------------------------

    /** Tab bar and scrollbar clicks; true when handled. */
    protected boolean clickChrome(double mx, double my, int button) {
        TextFieldWidget s = search();
        if (s != null && s.w >= 22 && s.contains(mx, my)) return s.click(mx, my, button);
        if (s != null) s.setFocused(false);
        if (Vanilla.inside(mx, my, px, py, pw, TAB_H)) {
            if (button != 0) return true;
            for (int i = 0; i < tabX.length; i++) {
                if (mx >= tabX[i] && mx < tabX[i] + tabW[i]) {
                    scroll = 0;
                    selectTab(i);
                    return true;
                }
            }
            for (Placed p : placed) {
                if (mx >= p.x && mx < p.x + p.w) {
                    onTool(p.tool.id);
                    return true;
                }
            }
            return true;
        }
        if (maxScroll() > 0 && Vanilla.inside(mx, my, barX() - 2, listY, BAR_W + 4, listBottom - listY)) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        return false;
    }

    private void dragBar(double my) {
        int h = listBottom - listY;
        scroll = (int) ((my - listY) / h * contentHeight() - h / 2.0);
        clampScroll();
    }

    @Override
    protected boolean menuDrag(double mx, double my, int button) {
        if (!draggingBar) return false;
        dragBar(my);
        return true;
    }

    @Override
    protected boolean menuRelease(double mx, double my, int button) {
        boolean had = draggingBar;
        draggingBar = false;
        return had;
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        if (!inList(mx, my)) return false;
        scroll -= (int) Math.signum(amount) * SCROLL_STEP;
        clampScroll();
        return true;
    }

    @Override
    protected boolean menuChar(char ch) {
        TextFieldWidget s = search();
        if (s == null) return false;
        if (!s.focused()) {
            if (ch <= ' ') return false;
            s.setFocused(true);
        }
        return s.charTyped(ch);
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        TextFieldWidget s = search();
        if (s == null || !s.focused()) return false;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            s.setFocused(false);
            return true;
        }
        return s.keyPressed(key, modifiers);
    }

    // ---- helpers ------------------------------------------------------------

    /** {@code text} broken into lines no wider than {@code max}. */
    protected static List<String> wrap(Canvas c, String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (c.textWidth(next) <= max || line.isEmpty()) {
                line.setLength(0);
                line.append(next);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    /** A flat button with a centred label; returns whether the mouse is on it. */
    protected static boolean flatButton(Canvas c, String label, int x, int y, int w, int h, int mouseX, int mouseY,
                                        boolean on, boolean enabled) {
        boolean hover = enabled && Vanilla.inside(mouseX, mouseY, x, y, w, h);
        Theme.plate(c, x, y, w, h, Theme.SURFACE, on ? Theme.MOSS_BOT : Theme.SURFACE_BOT, hover);
        int lx = x + (w - c.textWidth(label)) / 2, ly = y + (h - 7) / 2;
        if (!enabled) c.text(label, lx, ly, Theme.TEXT_FAINT, false);
        else if (on) Theme.label(c, label, lx, ly, Theme.MOSS_UP, Theme.MOSS_LO, 1f);
        else Theme.label(c, label, lx, ly, hover ? Theme.ACTIVE_UP : Theme.LABEL_UP, hover ? Theme.ACTIVE_LO : Theme.LABEL_LO, 1f);
        return hover;
    }
}
