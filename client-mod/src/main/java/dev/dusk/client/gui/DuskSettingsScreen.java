package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.widget.ButtonWidget;
import dev.dusk.client.gui.widget.CycleWidget;
import dev.dusk.client.gui.widget.GroupHeaderWidget;
import dev.dusk.client.gui.widget.LabelWidget;
import dev.dusk.client.gui.widget.ScrollPane;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.gui.widget.ToggleWidget;
import dev.dusk.client.gui.widget.Widget;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.Setting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Dusk menu, laid out after Figma frame 8 (1280x832): a 972x564 panel
 * centred over a 30% dim, a tab bar across the top (ALL, favourites, NEW and
 * the categories on the left; search, preferences, HUD editor and close on
 * the right) and a body of module cards, each with its name, an item icon, a
 * heart, an ENABLED/DISABLED button and a settings gear. A card's gear opens
 * that module's settings in the same body.
 */
public class DuskSettingsScreen extends DuskScreen {
    private static final int TAB_H = 23;
    private static final int CARD_GAP = 6, CARD_MIN_W = 110, MAX_COLS = 4;
    private static final int HEADER_H = 18;
    private static final int SETTINGS_MAX_W = 440;

    /** Modules added since the previous release, listed under the NEW tab. Update this set every release. */
    private static final Set<String> NEW_IDS = Set.of("behindyou", "nametags", "particles", "hitbox");

    private enum Page { MODULES, MODULE, PREFERENCES }

    private enum Tab {
        ALL("ALL", null), FAVORITES("", null), NEW("NEW", null),
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
                case FAVORITES -> DuskConfig.get().favorites.contains(m.id());
                case NEW -> NEW_IDS.contains(m.id());
                default -> m.category() == category;
            };
        }
    }

    private enum Tool { SEARCH, PREFS, HUD_EDITOR, CLOSE }

    private final Screen parent;
    private final ScrollPane pane = new ScrollPane();
    private final TextFieldWidget search;
    private Page page = Page.MODULES;
    private Tab tab = Tab.ALL;
    @Nullable private Module module;
    private String query = "";
    private boolean dirty = true;
    private int builtCols = -1, builtCardW = -1;
    private int px, py, pw, ph, pad;
    private final int[] tabX = new int[Tab.values().length], tabW = new int[Tab.values().length];
    private final int[] toolX = new int[Tool.values().length], toolW = new int[Tool.values().length];

    public DuskSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("Dusk Menu"));
        this.parent = parent;
        this.search = new TextFieldWidget(() -> "", q -> { query = q; dirty = true; pane.resetScroll(); }, true, 40)
                .placeholder("Search...");
    }

    /** Opens straight onto a module's settings page. */
    public DuskSettingsScreen open(Module m) {
        showModule(m);
        return this;
    }

    private boolean inWorld() {
        return this.minecraft != null && this.minecraft.level != null;
    }

    private void save() {
        if (DuskClient.modules() != null) DuskClient.modules().saveConfig();
    }

    // ---- navigation -----------------------------------------------------

    private void showModules(Tab t) {
        page = Page.MODULES;
        tab = t;
        module = null;
        resetPage();
    }

    private void showModule(Module m) {
        page = Page.MODULE;
        module = m;
        resetPage();
    }

    private void showPreferences() {
        page = Page.PREFERENCES;
        module = null;
        resetPage();
    }

    private void resetPage() {
        search.setFocused(false);
        search.setText("");
        query = "";
        pane.resetScroll();
        dirty = true;
    }

    private void openHudEditor(@Nullable Module focus) {
        if (this.minecraft == null || !inWorld()) return;
        HudEditorScreen editor = new HudEditorScreen(this);
        if (focus != null) editor.focus(focus);
        Compat.setScreen(this.minecraft, editor);
    }

    private static boolean favorite(Module m) {
        return DuskConfig.get().favorites.contains(m.id());
    }

    private void toggleFavorite(Module m) {
        List<String> favs = DuskConfig.get().favorites;
        if (!favs.remove(m.id())) favs.add(m.id());
        DuskConfig.save();
        if (tab == Tab.FAVORITES) dirty = true;
    }

    // ---- layout -----------------------------------------------------------

    private int bodyY() { return py + TAB_H - 1; }
    private int contentX() { return px + pad; }
    private int contentW() { return pw - 2 * pad; }
    private int contentY() { return bodyY() + pad + (page == Page.MODULES ? 0 : HEADER_H); }
    private int contentH() { return py + ph - pad - contentY(); }

    private void layout() {
        int minW = Math.min(400, this.width - 16), minH = Math.min(220, this.height - 16);
        pw = Math.max(minW, Math.min(this.width - 16, Math.round(this.width * 0.76f)));
        ph = Math.max(minH, Math.min(this.height - 16, Math.round(this.height * 0.68f)));
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        pad = Math.max(6, Math.round(pw * 0.03f));
        layoutTabBar();

        int cols = Math.max(1, Math.min(MAX_COLS, (contentW() - 6 + CARD_GAP) / (CARD_MIN_W + CARD_GAP)));
        int cardW = (contentW() - 6 - CARD_GAP * (cols - 1)) / cols;
        if (page == Page.MODULES && (cols != builtCols || cardW != builtCardW)) dirty = true;
        if (dirty) rebuild(cols, cardW);
        if (page == Page.MODULES) {
            pane.layout(contentX(), contentY(), contentW(), contentH());
        } else {
            int sw = Math.min(contentW(), SETTINGS_MAX_W);
            pane.layout(contentX() + (contentW() - sw) / 2, contentY(), sw, contentH());
        }
    }

    /** Tabs from the left at their label width; square tools from the right; search takes what is left. */
    private void layoutTabBar() {
        Tool[] right = inWorld() ? new Tool[] {Tool.CLOSE, Tool.HUD_EDITOR, Tool.PREFS} : new Tool[] {Tool.CLOSE, Tool.PREFS};
        java.util.Arrays.fill(toolW, 0);
        int rx = px + pw;
        for (Tool t : right) {
            rx -= TAB_H - 1;
            toolX[t.ordinal()] = rx;
            toolW[t.ordinal()] = TAB_H;
        }
        for (int tabPad : new int[] {10, 6, 3}) {
            int x = px;
            for (Tab t : Tab.values()) {
                int w = t == Tab.FAVORITES ? TAB_H : this.font.width(t.label) + 2 * tabPad + 2;
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

    private void rebuild(int cols, int cardW) {
        dirty = false;
        builtCols = cols;
        builtCardW = cardW;
        pane.clear();
        switch (page) {
            case MODULES -> buildCards(cols, cardW);
            case MODULE -> buildModule();
            case PREFERENCES -> buildPreferences();
        }
    }

    private void buildCards(int cols, int cardW) {
        if (DuskClient.modules() == null) return;
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Module> mods = new ArrayList<>();
        for (Module.Category cat : Module.Category.values()) {
            for (Module m : DuskClient.modules().all()) {
                if (m.category() == cat && tab.shows(m) && matches(m, q)) mods.add(m);
            }
        }
        int cardH = Math.max(80, Math.min(115, Math.round(cardW * 0.72f)));
        for (int i = 0; i < mods.size(); i += cols) {
            pane.add(new CardRow(mods.subList(i, Math.min(mods.size(), i + cols)), cols, cardH), cardH + CARD_GAP);
        }
        if (mods.isEmpty()) {
            String empty = !q.isEmpty() ? "No modules match \"" + query.trim() + "\""
                    : tab == Tab.FAVORITES ? "No favourites yet: click the heart on a card." : "No modules here.";
            pane.add(new LabelWidget(empty), 20);
        }
    }

    private static boolean matches(Module m, String q) {
        if (q.isEmpty()) return true;
        if (m.name().toLowerCase(Locale.ROOT).contains(q) || m.description().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Setting<?> s : m.settings()) if (s.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private void buildModule() {
        Module m = module;
        if (m == null) return;
        int width = Math.min(contentW(), SETTINGS_MAX_W);
        if (m instanceof HudElement && query.isBlank()) {
            ButtonWidget move = new ButtonWidget(inWorld() ? "Move on screen (HUD editor)" : "Join a world to move this element",
                    () -> openHudEditor(m));
            pane.add(move, 18);
            pane.add(new LabelWidget(""), 4);
        }
        SettingsBuilder.build(pane, m, this.font, width - 6, query, true, this::save, () -> dirty = true);
    }

    private void buildPreferences() {
        GroupHeaderWidget look = new GroupHeaderWidget("prefs/look", "Appearance", false, () -> {});
        pane.add(look, 16);
        ChoiceSetting styleChoice = new ChoiceSetting("ui_style", "Menu style", Theme.style().label,
                Theme.Style.DUSK.label, Theme.Style.DAWN.label);
        add(look, new CycleWidget(styleChoice, () -> {
            Theme.setStyle(styleChoice.is(Theme.Style.DAWN.label) ? Theme.Style.DAWN : Theme.Style.DUSK);
            dirty = true;
        }), SettingsBuilder.ROW_H);
        add(look, new ToggleWidget("Animated title scene", () -> DuskConfig.get().titleScene, v -> {
            DuskConfig.get().titleScene = v;
            DuskConfig.save();
        }), SettingsBuilder.ROW_H);
        add(look, new ToggleWidget("Show skin on title screen", () -> DuskConfig.get().showAccountTile, v -> {
            DuskConfig.get().showAccountTile = v;
            DuskConfig.save();
        }), SettingsBuilder.ROW_H);

        GroupHeaderWidget launcher = new GroupHeaderWidget("prefs/launcher", "Launcher", false, () -> {});
        pane.add(launcher, 16);
        add(launcher, new LabelWidget("Background image (PNG/JPG path, empty = default)"), 14);
        TextFieldWidget bg = new TextFieldWidget(() -> DuskConfig.get().backgroundPath, v -> {
            DuskConfig.get().backgroundPath = v.trim();
            DuskConfig.save();
        }, false, 512).placeholder("C:/path/to/background.png");
        add(launcher, bg, 16);
        add(launcher, new LabelWidget(""), 4);

        GroupHeaderWidget hud = new GroupHeaderWidget("prefs/hud", "HUD", false, () -> {});
        pane.add(hud, 16);
        add(hud, new ButtonWidget(inWorld() ? "Open HUD editor" : "Join a world to edit the HUD layout",
                () -> openHudEditor(null)), 18);
        add(hud, new LabelWidget("Right Shift opens this menu (rebind under Controls)."), 16);
    }

    private void add(GroupHeaderWidget group, Widget w, int height) {
        w.group = group;
        pane.add(w, height);
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected boolean vanillaBackground() {
        return inWorld() || !DuskConfig.get().titleScene;
    }

    @Override
    protected void drawBackgroundOverlay(Canvas c) {
        if (!vanillaBackground()) TitleScene.draw(c, this.width, this.height);
        c.fill(0, 0, this.width, this.height, 0x4D000000); // Figma: black at 30%
    }

    @Override
    protected void drawOverlay(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        boolean dusk = Theme.style() == Theme.Style.DUSK;

        // body: #1e1e1e at 90% inside a black stroke
        int by = bodyY();
        if (dusk) {
            c.fill(px + 1, by + 1, px + pw - 1, py + ph - 1, 0xE61E1E1E);
            c.outline(px, by, pw, py + ph - by, 0xFF000000);
        } else {
            Theme.panel(c, px, by, pw, py + ph - by);
        }

        drawTabBar(c, mouseX, mouseY);

        if (page != Page.MODULES) {
            String title = page == Page.PREFERENCES ? "PREFERENCES" : module != null ? module.name().toUpperCase(Locale.ROOT) : "";
            boolean hb = inBack(mouseX, mouseY);
            int hx = contentX(), hy = bodyY() + pad + (HEADER_H - 7) / 2 - 2;
            Icons.BACK.draw(c, hx, hy + 1, hb ? Theme.ACTIVE_UP : Theme.LABEL_UP);
            Theme.label(c, Theme.ellipsize(c, title, contentW() - 14), hx + 8, hy, hb ? Theme.ACTIVE_UP : Theme.LABEL_UP,
                    hb ? Theme.ACTIVE_LO : Theme.LABEL_LO, 1f);
            Theme.divider(c, contentX(), contentX() + contentW(), bodyY() + pad + HEADER_H - 5);
        }

        pane.render(c, mouseX, mouseY);
    }

    private void drawTabBar(Canvas c, int mouseX, int mouseY) {
        Theme.plate(c, px, py, pw, TAB_H, Theme.SURFACE, Theme.SURFACE, false);
        int ty = py + (TAB_H - 7) / 2;
        for (Tab t : Tab.values()) {
            int x = tabX[t.ordinal()], w = tabW[t.ordinal()];
            boolean active = t == tab && page != Page.PREFERENCES;
            boolean hover = hit(x, py, w, TAB_H, mouseX, mouseY);
            if (active || hover) c.fill(x + 1, py + 2, x + w - 1, py + TAB_H - 2, active ? 0xFF2A2A2A : 0xFF242424);
            if (t.ordinal() > 0) Theme.vDivider(c, x, py + 1, py + TAB_H - 1);
            if (t == Tab.FAVORITES) {
                Icons icon = active ? Icons.HEART : Icons.HEART_OUTLINE;
                int ix = x + (w - icon.width()) / 2, iy = py + (TAB_H - icon.height()) / 2;
                drawHeart(c, icon, ix, iy, 1, active ? Theme.RED_UP : hover ? Theme.ACTIVE_UP : Theme.LABEL_UP,
                        active ? Theme.RED_LO : hover ? Theme.ACTIVE_LO : Theme.LABEL_LO);
                continue;
            }
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
                if (page == Page.PREFERENCES || w < 30) continue;
                search.render(c, mouseX, mouseY);
                if (search.text().isEmpty() && !search.focused()) {
                    Icons.SEARCH.draw(c, search.x + search.w - 11, search.y + (search.h - 7) / 2, Theme.TEXT_FAINT);
                }
                continue;
            }
            boolean hover = hit(x, py, w, TAB_H, mouseX, mouseY);
            boolean active = t == Tool.PREFS && page == Page.PREFERENCES;
            if (hover || active) c.fill(x + 1, py + 2, x + w - 1, py + TAB_H - 2, active ? 0xFF2A2A2A : 0xFF242424);
            Icons icon = t == Tool.CLOSE ? Icons.CLOSE : t == Tool.PREFS ? Icons.GEAR : Icons.HUD;
            int col = t == Tool.CLOSE && hover ? Theme.RED_UP : hover || active ? Theme.ACTIVE_UP : Theme.LABEL_UP;
            icon.draw(c, x + (w - icon.width()) / 2, py + (TAB_H - icon.height()) / 2, col);
        }
    }

    /** A heart glyph in two tones: {@code up} over the top three rows, {@code lo} below. */
    private static void drawHeart(Canvas c, Icons icon, int x, int y, int scale, int up, int lo) {
        if (Theme.style() == Theme.Style.DAWN) {
            icon.draw(c, x, y, up, scale);
            return;
        }
        c.scissor(x, y, x + icon.width() * scale, y + 3 * scale);
        icon.draw(c, x, y, up, scale);
        c.unscissor();
        c.scissor(x, y + 3 * scale, x + icon.width() * scale, y + icon.height() * scale);
        icon.draw(c, x, y, lo, scale);
        c.unscissor();
    }

    private static boolean hit(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private boolean inBack(double mx, double my) {
        if (page == Page.MODULES) return false;
        String title = page == Page.PREFERENCES ? "PREFERENCES" : module != null ? module.name().toUpperCase(Locale.ROOT) : "";
        int top = bodyY() + pad;
        return mx >= contentX() - 2 && mx < contentX() + 12 + this.font.width(title) && my >= top - 2 && my < top + HEADER_H - 4;
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean onClick(double mx, double my, int button) {
        layout();
        if (page != Page.PREFERENCES && search.w >= 22 && search.contains(mx, my)) {
            pane.blur();
            return search.click(mx, my, button);
        }
        search.setFocused(false);
        if (hit(px, py, pw, TAB_H, mx, my)) {
            pane.blur();
            if (button == 0) clickTabBar(mx);
            return true;
        }
        if (inBack(mx, my) && button == 0) {
            showModules(tab);
            return true;
        }
        if (pane.click(mx, my, button)) return true;
        if (button == 1 && page != Page.MODULES) {
            showModules(tab);
            return true;
        }
        return hit(px, py, pw, ph, mx, my);
    }

    private void clickTabBar(double mx) {
        for (Tab t : Tab.values()) {
            if (mx >= tabX[t.ordinal()] && mx < tabX[t.ordinal()] + tabW[t.ordinal()]) {
                showModules(t);
                return;
            }
        }
        for (Tool t : Tool.values()) {
            int x = toolX[t.ordinal()], w = toolW[t.ordinal()];
            if (w <= 0 || mx < x || mx >= x + w) continue;
            switch (t) {
                case PREFS -> { if (page == Page.PREFERENCES) showModules(tab); else showPreferences(); }
                case HUD_EDITOR -> openHudEditor(null);
                case CLOSE -> onClose();
                case SEARCH -> {}
            }
            return;
        }
    }

    @Override
    protected boolean onDrag(double mx, double my, int button) {
        return pane.drag(mx, my);
    }

    @Override
    protected boolean onRelease(double mx, double my, int button) {
        return pane.release();
    }

    @Override
    protected boolean onScroll(double mx, double my, double amount) {
        return pane.scroll(mx, my, amount);
    }

    @Override
    protected boolean onKey(int key, int scancode, int modifiers) {
        if (search.focused() && search.keyPressed(key, modifiers)) return true;
        if (pane.keyPressed(key, modifiers)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE && page != Page.MODULES) {
            showModules(tab);
            return true;
        }
        if (key == GLFW.GLFW_KEY_F && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 && page != Page.PREFERENCES) {
            search.setFocused(true);
            return true;
        }
        if (isSettingsKey(key, scancode)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onChar(char ch) {
        if (search.focused()) return search.charTyped(ch);
        if (pane.charTyped(ch)) return true;
        // start typing anywhere to search
        if (page != Page.PREFERENCES && ch > ' ' && !pane.anyFocused()) {
            search.setFocused(true);
            return search.charTyped(ch);
        }
        return false;
    }

    @Override
    public void onClose() {
        pane.blur();
        save();
        if (this.minecraft != null) Compat.setScreen(this.minecraft, this.parent);
    }

    /**
     * A row of module cards. Each card follows Figma's 307x223 card: name at
     * the top left, heart top right, the icon in the middle and a 233x40
     * ENABLED button beside a 40x40 gear along the bottom; everything scales
     * with the card's width.
     */
    private class CardRow extends Widget {
        private final List<Module> mods;
        private final int cols, cardH;

        CardRow(List<Module> mods, int cols, int cardH) {
            this.mods = mods;
            this.cols = cols;
            this.cardH = cardH;
        }

        private int cardW() { return (w - CARD_GAP * (cols - 1)) / cols; }

        private int cardX(int i) { return x + i * (cardW() + CARD_GAP); }

        private float k() { return cardW() / 307f; }

        private int margin() { return Math.max(5, Math.round(14 * k())); }

        private int buttonH() { return Math.max(14, Math.round(40 * k())); }

        private int heartScale() { return cardW() >= 170 ? 2 : 1; }

        private int indexAt(double mx, double my) {
            if (my < y || my >= y + cardH) return -1;
            for (int i = 0; i < mods.size(); i++) if (mx >= cardX(i) && mx < cardX(i) + cardW()) return i;
            return -1;
        }

        /** {x, y, w, h} of the ENABLED button, the gear and the heart on card {@code i}. */
        private int[] toggleBox(int i) {
            int m = margin(), bh = buttonH(), gap = Math.max(3, Math.round(8 * k()));
            return new int[] {cardX(i) + m, y + cardH - m - bh, cardW() - 2 * m - bh - gap, bh};
        }

        private int[] gearBox(int i) {
            int m = margin(), bh = buttonH();
            return new int[] {cardX(i) + cardW() - m - bh, y + cardH - m - bh, bh, bh};
        }

        private int[] heartBox(int i) {
            int s = heartScale(), m = margin();
            return new int[] {cardX(i) + cardW() - m - 7 * s - 2, y + m - 2, 7 * s + 4, 6 * s + 4};
        }

        private boolean in(int[] b, double mx, double my) {
            return hit(b[0], b[1], b[2], b[3], mx, my);
        }

        @Override
        public void render(Canvas c, int mouseX, int mouseY) {
            int hover = indexAt(mouseX, mouseY);
            for (int i = 0; i < mods.size(); i++) drawCard(c, i, mods.get(i), i == hover, mouseX, mouseY);
        }

        private void drawCard(Canvas c, int i, Module m, boolean hot, int mouseX, int mouseY) {
            int cx = cardX(i), cw = cardW(), mg = margin();
            int[] tb = toggleBox(i), gb = gearBox(i), hb = heartBox(i);
            boolean onToggle = hot && in(tb, mouseX, mouseY), onGear = hot && in(gb, mouseX, mouseY), onHeart = hot && in(hb, mouseX, mouseY);
            Theme.plate(c, cx, y, cw, cardH, Theme.SURFACE, Theme.SURFACE, hot && !onToggle && !onGear && !onHeart);

            // heart
            boolean fav = favorite(m);
            int hs = heartScale();
            drawHeart(c, fav ? Icons.HEART : Icons.HEART_OUTLINE, hb[0] + 2, hb[1] + 2, hs,
                    fav ? Theme.RED_UP : onHeart ? Theme.ACTIVE_UP : Theme.LABEL_UP,
                    fav ? Theme.RED_LO : onHeart ? Theme.ACTIVE_LO : Theme.LABEL_LO);

            // name: as large as fits, else two lines, else cut
            String name = m.name().toUpperCase(Locale.ROOT);
            int nameMax = cw - 2 * mg - 7 * hs - 6, nx = cx + mg, ny = y + mg;
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
            ItemStack stack = ModuleIcons.of(m.id());
            int is = areaH >= 38 && cw >= 130 ? 2 : 1;
            if (areaH >= 16) {
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

        @Override
        public boolean click(double mx, double my, int button) {
            int i = indexAt(mx, my);
            if (i < 0) return false;
            Module m = mods.get(i);
            if (button == 0 && in(heartBox(i), mx, my)) {
                toggleFavorite(m);
            } else if (button == 0 && in(toggleBox(i), mx, my)) {
                m.setEnabled(!m.enabled());
                save();
            } else if (button == 0 || button == 1) {
                showModule(m);
            }
            return true;
        }
    }
}
