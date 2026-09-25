package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.Setting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The module list, laid out after Figma frame 8: a panel centred over the
 * game, a tab bar across the top (ALL, NEW and the categories on the left;
 * search, preferences, HUD editor and close on the right) and a grid of
 * module cards, each with its name, an item icon, an ENABLED/DISABLED button
 * and a settings gear. A card opens that module's settings page.
 */
public class DuskSettingsScreen extends MenuScreen {
    private static final int TAB_H = 23;
    private static final int CARD_GAP = 6, CARD_MIN_W = 110, MAX_COLS = 4, BAR_W = 4, SCROLL_STEP = 24;

    /** Modules added since the previous release, listed under the NEW tab. Update this set every release. */
    private static final Set<String> NEW_IDS = Set.of("behindyou", "nametags", "particles", "hitbox");

    private enum Tab {
        ALL("ALL", null), NEW("NEW", null),
        HUD("HUD", Module.Category.HUD), RENDER("RENDER", Module.Category.RENDER),
        MOVEMENT("MOVEMENT", Module.Category.MOVEMENT), UTILITY("UTILITY", Module.Category.MISC);

        final String label;
        @Nullable final Module.Category category;

        Tab(String label, @Nullable Module.Category category) {
            this.label = label;
            this.category = category;
        }

        boolean shows(Module m) {
            return switch (this) {
                case ALL -> true;
                case NEW -> NEW_IDS.contains(m.id());
                default -> m.category() == category;
            };
        }
    }

    private enum Tool { SEARCH, PREFS, HUD_EDITOR, CLOSE }

    private static Tab tab = Tab.ALL;

    private final TextFieldWidget search;
    private final List<Module> shown = new ArrayList<>();
    private String query = "";
    private int scroll;
    private boolean draggingBar;
    private int px, py, pw, ph, pad, cols, cardW, cardH;
    private final int[] tabX = new int[Tab.values().length], tabW = new int[Tab.values().length];
    private final int[] toolX = new int[Tool.values().length], toolW = new int[Tool.values().length];

    public DuskSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("Modules"), parent);
        this.search = new TextFieldWidget(() -> "", this::setQuery, true, 40).themed().placeholder("Search...");
        refresh();
    }

    private void setQuery(String q) {
        query = q.toLowerCase(Locale.ROOT).trim();
        scroll = 0;
        refresh();
    }

    private void showTab(Tab t) {
        tab = t;
        scroll = 0;
        refresh();
    }

    private void refresh() {
        shown.clear();
        if (DuskClient.modules() == null) return;
        for (Module.Category cat : Module.Category.values()) {
            for (Module m : DuskClient.modules().all()) {
                if (m.category() == cat && tab.shows(m) && matches(m, query)) shown.add(m);
            }
        }
    }

    private static boolean matches(Module m, String q) {
        if (q.isEmpty()) return true;
        if (m.name().toLowerCase(Locale.ROOT).contains(q) || m.description().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Setting<?> s : m.settings()) if (s.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    // ---- layout -----------------------------------------------------------

    @Override
    protected void init() {
        layout();
    }

    private int bodyY() { return py + TAB_H - 1; }
    private int contentX() { return px + pad; }
    private int contentW() { return pw - 2 * pad; }
    private int contentY() { return bodyY() + pad; }
    private int contentBottom() { return py + ph - pad; }
    private int gridW() { return contentW() - BAR_W - 4; }

    private void layout() {
        int minW = Math.min(400, this.width - 16), minH = Math.min(220, this.height - 16);
        pw = Math.max(minW, Math.min(this.width - 16, Math.round(this.width * 0.76f)));
        ph = Math.max(minH, Math.min(this.height - 16, Math.round(this.height * 0.68f)));
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        pad = Math.max(6, Math.round(pw * 0.03f));
        cols = Math.max(1, Math.min(MAX_COLS, (gridW() + CARD_GAP) / (CARD_MIN_W + CARD_GAP)));
        cardW = (gridW() - CARD_GAP * (cols - 1)) / cols;
        cardH = Math.max(80, Math.min(115, Math.round(cardW * 0.72f)));
        layoutTabBar();
        clampScroll();
    }

    /** Tabs from the left at their label width; square tools from the right; search takes what is left. */
    private void layoutTabBar() {
        Tool[] right = inWorld() ? new Tool[] {Tool.CLOSE, Tool.HUD_EDITOR, Tool.PREFS} : new Tool[] {Tool.CLOSE, Tool.PREFS};
        Arrays.fill(toolW, 0);
        int rx = px + pw;
        for (Tool t : right) {
            rx -= TAB_H - 1;
            toolX[t.ordinal()] = rx;
            toolW[t.ordinal()] = TAB_H;
        }
        for (int tabPad : new int[] {10, 6, 3}) {
            int x = px;
            for (Tab t : Tab.values()) {
                int w = this.font.width(t.label) + 2 * tabPad + 2;
                tabX[t.ordinal()] = x;
                tabW[t.ordinal()] = w;
                x += w - 1;
            }
            int left = rx - x;
            if (left >= 70 || tabPad == 3) {
                int sw = Math.max(0, Math.min(150, left));
                toolX[Tool.SEARCH.ordinal()] = rx - sw + 1;
                toolW[Tool.SEARCH.ordinal()] = sw;
                break;
            }
        }
        int sx = toolX[Tool.SEARCH.ordinal()], sw = toolW[Tool.SEARCH.ordinal()];
        search.setBounds(sx + 4, py + 4, Math.max(0, sw - 8), TAB_H - 8);
    }

    private int rows() { return (shown.size() + cols - 1) / cols; }

    private int contentHeight() {
        int rows = rows();
        return rows == 0 ? 0 : rows * (cardH + CARD_GAP) - CARD_GAP;
    }

    private int maxScroll() { return Math.max(0, contentHeight() - (contentBottom() - contentY())); }

    private void clampScroll() { scroll = Math.max(0, Math.min(maxScroll(), scroll)); }

    private int cardX(int i) { return contentX() + (i % cols) * (cardW + CARD_GAP); }
    private int cardY(int i) { return contentY() + (i / cols) * (cardH + CARD_GAP) - scroll; }
    private int barX() { return contentX() + contentW() - BAR_W; }

    private boolean inGrid(double mx, double my) {
        return mx >= contentX() && mx < contentX() + gridW() && my >= contentY() && my < contentBottom();
    }

    // ---- card geometry (Figma's 307x223 card, scaled to the card width) ------------------

    private float k() { return cardW / 307f; }
    private int margin() { return Math.max(5, Math.round(14 * k())); }
    private int buttonH() { return Math.max(14, Math.round(40 * k())); }

    /** {x, y, w, h} of the ENABLED button and the gear on the card at {@code (cx, cy)}. */
    private int[] toggleBox(int cx, int cy) {
        int m = margin(), bh = buttonH(), gap = Math.max(3, Math.round(8 * k()));
        return new int[] {cx + m, cy + cardH - m - bh, cardW - 2 * m - bh - gap, bh};
    }

    private int[] gearBox(int cx, int cy) {
        int m = margin(), bh = buttonH();
        return new int[] {cx + cardW - m - bh, cy + cardH - m - bh, bh, bh};
    }

    private static boolean in(int[] b, double mx, double my) {
        return Vanilla.inside(mx, my, b[0], b[1], b[2], b[3]);
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        if (inWorld()) c.fill(0, 0, this.width, this.height, 0x4D000000);

        // body: #1e1e1e at 90% inside a black stroke
        int by = bodyY();
        c.fill(px + 1, by + 1, px + pw - 1, py + ph - 1, 0xE61E1E1E);
        c.outline(px, by, pw, py + ph - by, 0xFF000000);

        drawTabBar(c, mouseX, mouseY);

        int top = contentY(), bottom = contentBottom();
        boolean inGrid = inGrid(mouseX, mouseY);
        c.scissor(contentX(), top, contentX() + contentW(), bottom);
        for (int i = 0; i < shown.size(); i++) {
            int x = cardX(i), y = cardY(i);
            if (y + cardH < top || y > bottom) continue;
            drawCard(c, shown.get(i), x, y, inGrid ? mouseX : -1, inGrid ? mouseY : -1);
        }
        c.unscissor();
        if (shown.isEmpty()) {
            String empty = !query.isEmpty() ? "No modules match \"" + search.text().trim() + "\"" : "No modules here.";
            c.text(empty, contentX(), top + 4, Theme.TEXT_MUTED, false);
        }
        if (maxScroll() > 0) {
            int h = bottom - top, barH = Math.max(16, h * h / contentHeight());
            int barY = top + (h - barH) * scroll / maxScroll();
            c.fill(barX(), top, barX() + BAR_W, bottom, 0xFF141414);
            c.fill(barX(), barY, barX() + BAR_W, barY + barH, draggingBar ? Theme.LABEL_UP : Theme.LABEL_LO);
        }
    }

    private void drawTabBar(Canvas c, int mouseX, int mouseY) {
        Theme.plate(c, px, py, pw, TAB_H, Theme.SURFACE, Theme.SURFACE, false);
        int ty = py + (TAB_H - 7) / 2;
        for (Tab t : Tab.values()) {
            int x = tabX[t.ordinal()], w = tabW[t.ordinal()];
            boolean active = t == tab;
            boolean hover = Vanilla.inside(mouseX, mouseY, x, py, w, TAB_H);
            if (active || hover) c.fill(x + 1, py + 2, x + w - 1, py + TAB_H - 2, active ? 0xFF2A2A2A : 0xFF242424);
            if (t.ordinal() > 0) Theme.vDivider(c, x, py + 1, py + TAB_H - 1);
            boolean bright = active || hover;
            Theme.label(c, t.label, x + (w - c.textWidth(t.label)) / 2, ty,
                    bright ? Theme.ACTIVE_UP : Theme.LABEL_UP, bright ? Theme.ACTIVE_LO : Theme.LABEL_LO, 1f);
            if (active) c.fill(x + 3, py + TAB_H - 4, x + w - 3, py + TAB_H - 3, Theme.ACCENT);
        }
        for (Tool t : Tool.values()) {
            int x = toolX[t.ordinal()], w = toolW[t.ordinal()];
            if (w <= 0) continue;
            Theme.vDivider(c, x, py + 1, py + TAB_H - 1);
            if (t == Tool.SEARCH) {
                if (w < 30) continue;
                search.render(c, mouseX, mouseY);
                if (search.text().isEmpty() && !search.focused()) {
                    Icons.SEARCH.draw(c, search.x + search.w - 11, search.y + (search.h - 7) / 2, Theme.TEXT_FAINT);
                }
                continue;
            }
            boolean hover = Vanilla.inside(mouseX, mouseY, x, py, w, TAB_H);
            if (hover) c.fill(x + 1, py + 2, x + w - 1, py + TAB_H - 2, 0xFF242424);
            Icons icon = t == Tool.CLOSE ? Icons.CLOSE : t == Tool.PREFS ? Icons.GEAR : Icons.HUD;
            int col = t == Tool.CLOSE && hover ? Theme.RED_UP : hover ? Theme.ACTIVE_UP : Theme.LABEL_UP;
            icon.draw(c, x + (w - icon.width()) / 2, py + (TAB_H - icon.height()) / 2, col);
            if (hover) {
                String tip = t == Tool.CLOSE ? "Close" : t == Tool.PREFS ? "Preferences" : "Edit HUD layout";
                Vanilla.tooltip(c, tip, mouseX, mouseY, this.width, this.height);
            }
        }
    }

    private void drawCard(Canvas c, Module m, int cx, int cy, int mouseX, int mouseY) {
        int cw = cardW, mg = margin();
        int[] tb = toggleBox(cx, cy), gb = gearBox(cx, cy);
        boolean hot = Vanilla.inside(mouseX, mouseY, cx, cy, cw, cardH);
        boolean onToggle = hot && in(tb, mouseX, mouseY), onGear = hot && in(gb, mouseX, mouseY);
        Theme.plate(c, cx, cy, cw, cardH, Theme.SURFACE, Theme.SURFACE, hot && !onToggle && !onGear);

        // name: as large as fits, else two lines, else cut
        String name = m.name().toUpperCase(Locale.ROOT);
        int nameMax = cw - 2 * mg, nx = cx + mg, ny = cy + mg;
        if (NEW_IDS.contains(m.id())) {
            nameMax -= c.textWidth("NEW") + 4;
            c.text("NEW", cx + cw - mg - c.textWidth("NEW"), ny, Theme.ACCENT, false);
        }
        float maxScale = cw >= 220 ? 2f : cw >= 150 ? 1.5f : 1f;
        float ns = 1f;
        for (float s : new float[] {2f, 1.5f}) {
            if (s <= maxScale && Theme.labelWidth(c, name, s) <= nameMax) { ns = s; break; }
        }
        int nameBottom;
        if (c.textWidth(name) <= nameMax || ns > 1f) {
            Theme.scaledText(c, name, nx, ny, 0xFFFFFFFF, ns);
            nameBottom = ny + Math.round(8 * ns);
        } else {
            int cut = name.lastIndexOf(' ');
            while (cut > 0 && c.textWidth(name.substring(0, cut)) > nameMax) cut = name.lastIndexOf(' ', cut - 1);
            String first = cut > 0 ? name.substring(0, cut) : Theme.ellipsize(c, name, nameMax);
            String rest = cut > 0 ? Theme.ellipsize(c, name.substring(cut + 1), nameMax) : "";
            c.text(first, nx, ny, 0xFFFFFFFF, false);
            if (!rest.isEmpty()) c.text(rest, nx, ny + 10, 0xFFFFFFFF, false);
            nameBottom = ny + (rest.isEmpty() ? 8 : 18);
        }

        // icon, centred between the name and the buttons
        int areaTop = nameBottom + 2, areaBot = tb[1] - 2, areaH = areaBot - areaTop;
        int is = areaH >= 38 && cw >= 130 ? 2 : 1;
        if (areaH >= 16) {
            ItemStack stack = ModuleIcons.of(m.id());
            int size = 16 * is, ix = cx + (cw - size) / 2, iy = areaTop + (areaH - size) / 2;
            if (!stack.isEmpty()) {
                c.push();
                c.translate(ix, iy);
                c.scale(is, is);
                c.item(stack, 0, 0);
                c.pop();
            } else {
                Icons glyph = switch (m.category()) {
                    case HUD -> Icons.HUD;
                    case RENDER -> Icons.EYE;
                    case MOVEMENT -> Icons.ARROW;
                    case MISC -> Icons.DOTS;
                };
                int gs = 2 * is;
                glyph.draw(c, cx + (cw - glyph.width() * gs) / 2, areaTop + (areaH - glyph.height() * gs) / 2, Theme.LABEL_LO, gs);
            }
        }

        // ENABLED / DISABLED
        boolean on = m.enabled();
        Theme.plate(c, tb[0], tb[1], tb[2], tb[3], Theme.SURFACE, on ? Theme.MOSS_BOT : Theme.SURFACE_BOT, onToggle);
        String state = on ? "ENABLED" : "DISABLED";
        float ts = tb[3] >= 28 ? 2f : tb[3] >= 21 ? 1.5f : 1f;
        while (ts > 1f && Theme.labelWidth(c, state, ts) > tb[2] - 8) ts -= 0.5f;
        int lx = tb[0] + (tb[2] - Theme.labelWidth(c, state, ts)) / 2, ly = tb[1] + Math.round((tb[3] - 7 * ts) / 2);
        if (on) {
            Theme.label(c, state, lx, ly, Theme.MOSS_UP, Theme.MOSS_LO, ts);
        } else {
            Theme.label(c, state, lx, ly, onToggle ? Theme.ACTIVE_UP : Theme.LABEL_UP, onToggle ? Theme.ACTIVE_LO : Theme.LABEL_LO, ts);
        }

        // gear
        Theme.plate(c, gb[0], gb[1], gb[2], gb[3], Theme.SURFACE, Theme.SURFACE, onGear);
        int gs = gb[2] >= 24 ? 2 : 1;
        Icons.GEAR.draw(c, gb[0] + (gb[2] - 7 * gs) / 2, gb[1] + (gb[3] - 7 * gs) / 2, onGear ? 0xFFFFFFFF : Theme.ACTIVE_UP, gs);
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (search.w >= 22 && search.contains(mx, my)) return search.click(mx, my, button);
        search.setFocused(false);
        if (Vanilla.inside(mx, my, px, py, pw, TAB_H)) {
            if (button == 0) clickTabBar(mx);
            return true;
        }
        if (maxScroll() > 0 && Vanilla.inside(mx, my, barX() - 2, contentY(), BAR_W + 4, contentBottom() - contentY())) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        if (inGrid(mx, my)) {
            for (int i = 0; i < shown.size(); i++) {
                int x = cardX(i), y = cardY(i);
                if (!Vanilla.inside(mx, my, x, y, cardW, cardH)) continue;
                Module m = shown.get(i);
                if (button == 0 && in(toggleBox(x, y), mx, my)) {
                    m.setEnabled(!m.enabled());
                    saveModules();
                } else if (button == 0 || button == 1) {
                    open(ConfigScreen.module(this, m));
                }
                return true;
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    private void clickTabBar(double mx) {
        for (Tab t : Tab.values()) {
            if (mx >= tabX[t.ordinal()] && mx < tabX[t.ordinal()] + tabW[t.ordinal()]) {
                showTab(t);
                return;
            }
        }
        for (Tool t : Tool.values()) {
            int x = toolX[t.ordinal()], w = toolW[t.ordinal()];
            if (w <= 0 || mx < x || mx >= x + w) continue;
            switch (t) {
                case PREFS -> open(ConfigScreen.preferences(this));
                case HUD_EDITOR -> { if (inWorld()) open(new HudEditorScreen(this)); }
                case CLOSE -> exitMenu();
                case SEARCH -> {}
            }
            return;
        }
    }

    private void dragBar(double my) {
        int h = contentBottom() - contentY();
        scroll = (int) ((my - contentY()) / h * contentHeight() - h / 2.0);
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
        scroll -= (int) Math.signum(amount) * SCROLL_STEP;
        clampScroll();
        return true;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        boolean cmd = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (key == GLFW.GLFW_KEY_F && cmd) {
            search.setFocused(true);
            return true;
        }
        if (search.focused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE && !search.text().isEmpty()) {
                search.setText("");
                search.setFocused(false);
                return true;
            }
            return key != GLFW.GLFW_KEY_ESCAPE && search.keyPressed(key, modifiers);
        }
        return false;
    }

    @Override
    protected boolean menuChar(char ch) {
        if (!search.focused()) {
            if (ch <= ' ') return false;
            search.setFocused(true); // type anywhere to search
        }
        return search.charTyped(ch);
    }

    @Override
    protected void beforeClose() {
        search.setFocused(false);
        super.beforeClose();
    }
}
