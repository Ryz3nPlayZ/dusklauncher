package dev.dusk.client.gui;

import dev.dusk.client.gui.widget.TextFieldWidget;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The launcher's window bar (launcher/src/design/nav.css .win__bar): black
 * behind everything, so cells sit in one-pixel black gaps; tabs from the
 * left, pinned cells from the right, an optional search cell before them and
 * the plain filler panel between. The open tab only brightens its label.
 */
public final class NavBar {
    /** Bar height: the window's outline row, a 20px cell and the black line under it. */
    public static final int H = 22, CELL_H = H - 2;
    private static final int BLACK = 0xFF000000;

    /**
     * A pinned right-hand cell: an icon square, or a text cell when {@code icon}
     * is null. An id of {@code "label"} is the launcher's NavLabel: dim text, not a button.
     */
    public record Tool(String id, @Nullable Icons icon, String text, String tip) {}

    private int x, y, w;
    private String[] tabs = {};
    private List<Tool> tools = List.of();
    @Nullable private TextFieldWidget search;
    private int[] tabX = {}, tabW = {}, toolX = {}, toolW = {};
    private int searchX, searchW, fillX, fillW;

    /** Lays the bar out across the top of a window at {@code (x, y)}, {@code w} wide. Tools are rightmost first. */
    public void layout(int x, int y, int w, String[] tabs, List<Tool> tools, @Nullable TextFieldWidget search,
                       int searchMax, ToIntFunction<String> width) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.tabs = tabs;
        this.tools = tools;
        this.search = search;
        int right = x + w - 1;
        toolX = new int[tools.size()];
        toolW = new int[tools.size()];
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            int tw = t.icon != null ? CELL_H + 4 : width.applyAsInt(t.text) + 20;
            right -= tw;
            toolX[i] = right;
            toolW[i] = tw;
            right -= 1;
        }
        tabX = new int[tabs.length];
        tabW = new int[tabs.length];
        int cursor = x + 1;
        for (int pad : new int[] {12, 8, 5}) {
            cursor = x + 1;
            for (int i = 0; i < tabs.length; i++) {
                tabX[i] = cursor;
                tabW[i] = width.applyAsInt(tabs[i]) + 2 * pad;
                cursor += tabW[i] + 1;
            }
            if (right - cursor >= (search != null ? 90 : 10)) break;
        }
        searchW = 0;
        if (search != null) {
            int sw = Math.min(searchMax, right - cursor - 8);
            if (sw >= 50) {
                searchW = sw;
                searchX = right - sw;
                right = searchX - 1;
                search.setBounds(searchX + 15, y + 1 + (CELL_H - 12) / 2, sw - 18, 12);
            } else {
                search.setBounds(0, 0, 0, 0);
            }
        }
        fillX = cursor;
        fillW = Math.max(0, right - cursor);
    }

    public int tabAt(double mx, double my) {
        if (my < y || my >= y + H) return -1;
        for (int i = 0; i < tabX.length; i++) if (mx >= tabX[i] && mx < tabX[i] + tabW[i]) return i;
        return -1;
    }

    @Nullable
    public Tool toolAt(double mx, double my) {
        if (my < y || my >= y + H) return null;
        for (int i = 0; i < toolX.length; i++) if (mx >= toolX[i] && mx < toolX[i] + toolW[i]) return tools.get(i);
        return null;
    }

    public boolean contains(double mx, double my) {
        return Vanilla.inside(mx, my, x, y, w, H);
    }

    /** Draws the bar; {@code active} is the open tab's index, or -1 for none. */
    public void draw(Canvas c, int active, int mouseX, int mouseY, int screenW, int screenH) {
        int cy = y + 1;
        c.fill(x, y, x + w, y + H, BLACK);
        for (int i = 0; i < tabs.length; i++) {
            boolean hover = Vanilla.inside(mouseX, mouseY, tabX[i], cy, tabW[i], CELL_H);
            cellLabel(c, tabs[i], tabX[i], cy, tabW[i], i == active, hover);
        }
        if (fillW > 0) Theme.barFill(c, fillX, cy, fillW, CELL_H);
        if (search != null && searchW > 0) {
            Theme.cell(c, searchX, cy, searchW, CELL_H, false);
            Icons.SEARCH.draw(c, searchX + 5, cy + (CELL_H - 7) / 2, 0xFF7B7B7B);
            search.render(c, mouseX, mouseY);
        }
        Tool tip = null;
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            int tx = toolX[i], tw = toolW[i];
            boolean hover = Vanilla.inside(mouseX, mouseY, tx, cy, tw, CELL_H);
            if (t.id.equals("close")) {
                Theme.closeCell(c, tx, cy, tw, CELL_H, hover);
                Icons.CLOSE.draw(c, tx + (tw - 7) / 2, cy + (CELL_H - 7) / 2, Theme.RED_CORNER);
            } else if (t.id.equals("label")) {
                cellText(c, t.text, tx, cy, tw);
                continue;
            } else if (t.icon != null) {
                Theme.cell(c, tx, cy, tw, CELL_H, hover);
                t.icon.draw(c, tx + (tw - t.icon.width()) / 2, cy + (CELL_H - t.icon.height()) / 2, Theme.filter(Theme.GLYPH, hover, false));
            } else {
                cellLabel(c, t.text, tx, cy, tw, false, hover);
            }
            if (hover && !t.tip.isEmpty()) tip = t;
        }
        if (tip != null) Vanilla.tooltip(c, tip.tip, mouseX, mouseY, screenW, screenH);
    }

    /** A cell with a centred two-tone label (idle, or the brighter pair when {@code active}). */
    public static void cellLabel(Canvas c, String label, int x, int y, int w, boolean active, boolean hover) {
        Theme.cell(c, x, y, w, CELL_H, hover);
        int up = active ? Theme.ACTIVE_UP : Theme.LABEL_UP, lo = active ? Theme.ACTIVE_LO : Theme.LABEL_LO;
        Theme.label(c, label, x + (w - c.textWidth(label)) / 2, y + (CELL_H - 7) / 2,
                Theme.filter(up, hover, false), Theme.filter(lo, hover, false), 1f);
    }

    /** A cell that only reports something (the launcher's NavLabel): dim flat text. */
    public static void cellText(Canvas c, String label, int x, int y, int w) {
        Theme.cell(c, x, y, w, CELL_H, false);
        c.text(label, x + (w - c.textWidth(label)) / 2, y + (CELL_H - 7) / 2, Theme.DIM, false);
    }

    /** Whether a GLFW key types a character (so a search box should get it before any keybind). */
    public static boolean printable(int key) {
        return key >= 32 && key <= 96 || key == 161 || key == 162 || key >= 320 && key <= 336;
    }

    /**
     * The window under the bar (nav.css .win/.win__body): a black outline and
     * the body at 90%. Draw it before anything that sits in the body.
     */
    public static void window(Canvas c, int x, int y, int w, int h) {
        c.outline(x, y, w, h, BLACK);
        c.fill(x + 1, y + H, x + w - 1, y + h - 1, 0xE61E1E1E);
    }
}
