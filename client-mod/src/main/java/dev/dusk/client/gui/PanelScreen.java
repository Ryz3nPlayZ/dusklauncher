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
    protected static final int TAB_H = NavBar.H, BAR_W = 4, SCROLL_STEP = 24;

    /** A tab-bar tool: an icon square, or a text button when {@code icon} is null. */
    protected record Tool(String id, @Nullable Icons icon, String text, String tip) {}

    protected int px, py, pw, ph, pad;
    /** The scrollable area: {@link #listX}..{@link #listX}+{@link #listW}, {@link #listY}..{@link #listBottom}. */
    protected int listX, listY, listW, listBottom;
    protected int scroll;
    private boolean draggingBar;
    private final NavBar bar = new NavBar();

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
        List<NavBar.Tool> tools = new ArrayList<>();
        for (Tool t : tools()) tools.add(new NavBar.Tool(t.id, t.icon, t.text, t.tip));
        bar.layout(px, py, pw, tabs(), tools, search(), 170, this.font::width);
    }

    /** Lays the whole page out; pages with more than the panel override it. */
    protected void relayout() {
        layoutPanel();
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

    /**
     * The world dim and the window body go under the page's vanilla widgets
     * (the wardrobe's model), so they draw with the background.
     */
    @Override
    protected void drawBackgroundOverlay(Canvas c) {
        super.drawBackgroundOverlay(c);
        relayout();
        if (inWorld()) c.fill(0, 0, this.width, this.height, 0x4D000000);
        NavBar.window(c, px, py, pw, ph);
        drawUnderWidgets(c);
    }

    /** Anything else that must sit under the page's vanilla widgets. */
    protected void drawUnderWidgets(Canvas c) {}

    /** The tab bar; the body is already down (see {@link #drawBackgroundOverlay}). */
    protected void drawPanel(Canvas c, int mouseX, int mouseY) {
        bar.draw(c, activeTab(), mouseX, mouseY, this.width, this.height);
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
        if (bar.contains(mx, my)) {
            if (button != 0) return true;
            int t = bar.tabAt(mx, my);
            if (t >= 0) {
                scroll = 0;
                selectTab(t);
                return true;
            }
            NavBar.Tool tool = bar.toolAt(mx, my);
            if (tool != null) onTool(tool.id());
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
        // the menu key still closes the page unless it would type into the box
        if (isSettingsKey(key, scancode) && (!NavBar.printable(key) || s.text().isEmpty())) return false;
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

    /** A launcher button: {@code on} is the installed/applied state (green label). Returns whether the mouse is on it. */
    protected static boolean flatButton(Canvas c, String label, int x, int y, int w, int h, int mouseX, int mouseY,
                                        boolean on, boolean enabled) {
        return boxButton(c, label, x, y, w, h, mouseX, mouseY, Theme.Family.INSTALL, on, enabled);
    }

    /** A {@link Theme#box} with a centred two-tone label, filtered like the box. */
    protected static boolean boxButton(Canvas c, String label, int x, int y, int w, int h, int mouseX, int mouseY,
                                       Theme.Family f, boolean on, boolean enabled) {
        boolean hover = enabled && Vanilla.inside(mouseX, mouseY, x, y, w, h);
        Theme.box(c, x, y, w, h, f, hover, !enabled && !on);
        int up = on ? Theme.GREEN_UP : Theme.LABEL_UP, lo = on ? Theme.GREEN_LO : Theme.LABEL_LO;
        boolean dis = !enabled && !on;
        Theme.label(c, label, x + (w - c.textWidth(label)) / 2, y + (h - 7) / 2,
                Theme.filter(up, hover, dis), Theme.filter(lo, hover, dis), 1f);
        return hover;
    }
}
