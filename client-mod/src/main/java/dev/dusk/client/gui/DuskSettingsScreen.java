package dev.dusk.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Input;
import dev.dusk.client.gui.widget.ScrollPane;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.gui.widget.ToggleWidget;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/* Hallmark · pre-emit critique: P4 H4 E4 S4 R5 V4 */
/**
 * The Dusk menu: one window with a sidebar (the module categories with
 * their counts, then Preferences, the HUD layout, Styles, Wardrobe and
 * Friends & Chat) and the page on the right. The modules show as a grid of
 * cards, each with its switch, or a toggle away as one column of rows (under
 * All a card of rows per category, otherwise one card). A module's
 * settings and Preferences open in the same window as cards of rows, Esc
 * going back to the list.
 */
public class DuskSettingsScreen extends MenuScreen {
    private static final int CARD_GAP = 6, CARD_MIN_W = 110, MAX_COLS = 4, BAR_W = 4, SCROLL_STEP = 24;
    private static final int PAD = 10, HEAD_H = 40, ROW_H = 30, ROW_GAP = 4, BTN = 18;
    private static final int BRAND_H = 30, SECTION_H = 14, FOOT_H = 14;
    /** The modern list: a row, the heading over a category's card and the air between cards. */
    private static final int LIST_ROW_H = 36, HEADING_H = 26, GROUP_GAP = 6, SEARCH_H = 20;
    private static final int LEGACY_SWITCH_W = 49, LEGACY_SWITCH_H = 14;
    /** The modern look: the header, the card size, the window's inner margin and the sidebar's inset panel. */
    private static final int MODERN_HEAD_H = 48, MODERN_CARD_H = 80, MODERN_CARD_MIN_W = 124, MODERN_PAD = 14, PANEL_INSET = 7;
    /** The lavender of the italic word in the headings. */
    private static final int LAVENDER = 0xFFB4ACFF;

    /** Modules added since the previous release, listed under the NEW tab. Update this set every release. */
    private static final Set<String> NEW_IDS = Set.of("behindyou", "nametags", "particles", "hitbox",
            "hungerinfo", "lookingat", "shieldtweaks", "freelook", "compactchat", "chattimestamps", "confirmdisconnect",
            "autoreconnect", "serverlag", "containerpreview", "chathistory", "freecam", "chatmacros",
            "stopwatch", "resourcepacks", "scoreboard", "bossbar", "tabping",
            "blockhighlight", "backgroundfps", "namehider", "fovchanger", "totems", "crosshairindicator", "itemcounter", "bedwarsresources",
            "skyblockstats", "hypixel", "slotlock", "totemtweaks", "cooldowns", "glintcolor", "itempickups",
            "scrolltooltips");

    private static final String VERSION = FabricLoader.getInstance().getModContainer("duskclient")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");

    private enum Tab {
        ALL("All", "All modules", null, Icons.GRID), NEW("New", "New modules", null, Icons.SPARKLE),
        HUD("HUD", "HUD", Module.Category.HUD, Icons.HUD), RENDER("Render", "Render", Module.Category.RENDER, Icons.EYE),
        MOVEMENT("Movement", "Movement", Module.Category.MOVEMENT, Icons.RUN),
        UTILITY("Utility", "Utility", Module.Category.MISC, Icons.WRENCH);

        final String label, title;
        @Nullable final Module.Category category;
        final Icons icon;

        Tab(String label, String title, @Nullable Module.Category category, Icons icon) {
            this.label = label;
            this.title = title;
            this.category = category;
            this.icon = icon;
        }

        boolean shows(Module m) {
            return switch (this) {
                case ALL -> true;
                case NEW -> NEW_IDS.contains(m.id());
                default -> m.category() == category;
            };
        }
    }

    /** The sidebar's General links. */
    private enum Link {
        PREFS("Preferences", Icons.GEAR), HUD("HUD layout", Icons.MOVE), STYLES("Styles", Icons.PALETTE),
        WARDROBE("Wardrobe", Icons.SHIRT), SOCIAL("Friends & Chat", Icons.CHAT);

        final String label;
        final Icons icon;

        Link(String label, Icons icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private enum Page { LIST, MODULE, PREFS }

    /** One line of the list, {@code y} from its top: a category heading, or a module row ({@code first} in its card). */
    private record Item(@Nullable Module module, String heading, int count, int y, int h, boolean first) {}

    /** A clickable box laid out this frame. */
    private record Hit(String id, int x, int y, int w, int h) {
        boolean in(double mx, double my) {
            return Vanilla.inside(mx, my, x, y, w, h);
        }
    }

    private static Tab tab = Tab.ALL;
    /** The card grid instead of the rows. */
    private static boolean grid = true;

    private final TextFieldWidget search;
    private final List<Module> shown = new ArrayList<>();
    private final int[] counts = new int[Tab.values().length];
    private final ScrollPane pane = new ScrollPane();
    private final Map<Module, Float> knobs = new HashMap<>();
    private final List<Hit> side = new ArrayList<>(), heads = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    /** The cards behind the rows: {y, h} from the list's top. */
    private final List<int[]> runs = new ArrayList<>();
    private int listH;
    private String query = "";
    private Page page = Page.LIST;
    @Nullable private Module module;
    /** Opened straight onto a module (from the HUD editor): going back leaves the window. */
    private boolean direct;
    private float headKnob = -1;
    private int scroll;
    private boolean draggingBar;
    private int px, py, pw, ph, sw, itemH, modulesY, generalY, titleRight;
    private int cols, cardW, cardH, rowW;
    @Nullable private String tip;

    public DuskSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("Dusk"), parent);
        this.search = new TextFieldWidget(() -> "", this::setQuery, true, 40).bare().placeholder("Search modules");
        pane.cards(true);
        search.setFocused(true); // opening the menu is usually to find a module
        refresh();
    }

    /** The window opened on {@code m}'s settings; going back returns to {@code parent}. */
    public static DuskSettingsScreen module(@Nullable Screen parent, Module m) {
        DuskSettingsScreen s = new DuskSettingsScreen(parent);
        s.showModule(m);
        s.direct = true;
        return s;
    }

    private void setQuery(String q) {
        query = q.toLowerCase(Locale.ROOT).trim();
        scroll = 0;
        refresh();
    }

    private void refresh() {
        shown.clear();
        java.util.Arrays.fill(counts, 0);
        if (DuskClient.modules() == null) return;
        for (Module.Category cat : Module.Category.values()) {
            for (Module m : DuskClient.modules().all()) {
                if (m.category() != cat) continue;
                for (Tab t : Tab.values()) if (t.shows(m)) counts[t.ordinal()]++;
                if (tab.shows(m) && matches(m, query)) shown.add(m);
            }
        }
    }

    private static boolean matches(Module m, String q) {
        if (q.isEmpty()) return true;
        if (m.name().toLowerCase(Locale.ROOT).contains(q) || m.description().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Setting<?> s : m.settings()) if (s.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    // ---- pages --------------------------------------------------------------

    private void showTab(Tab t) {
        leavePage();
        tab = t;
        scroll = 0;
        refresh();
    }

    private void showModule(Module m) {
        leavePage();
        SettingsBuilder.build(pane, m, popups, () -> {}, false);
        module = m;
        page = Page.MODULE;
        headKnob = -1;
        search.setFocused(false);
    }

    private void showPreferences() {
        leavePage();
        ConfigScreen.fillPreferences(pane);
        page = Page.PREFS;
        search.setFocused(false);
    }

    /** Back to the list, saving whatever the page changed. */
    private void leavePage() {
        if (page != Page.LIST) {
            pane.blur();
            saveModules();
        }
        pane.clear();
        pane.resetScroll();
        page = Page.LIST;
        module = null;
        direct = false;
    }

    private void back() {
        if (direct) onClose();
        else leavePage();
    }

    /** Opens another screen over the menu; closing it comes back here. */
    private void leaveTo(Screen screen) {
        commit();
        open(screen);
    }

    private void commit() {
        pane.blur();
        search.setFocused(false);
        saveModules();
    }

    private void resetModule() {
        if (module == null) return;
        pane.blur();
        for (Setting<?> s : module.settings()) s.reset();
        if (module instanceof HudElement) module.setPosition(10, 10);
        saveModules();
    }

    // ---- layout -----------------------------------------------------------

    @Override
    protected void init() {
        layout();
    }

    private static int inset() { return Theme.modern() ? MODERN_PAD : PAD; }
    private static int headH() { return Theme.modern() ? MODERN_HEAD_H : HEAD_H; }
    private int mainX() { return px + sw; }
    private int contentX() { return mainX() + inset(); }
    private int contentW() { return pw - sw - 2 * inset(); }
    private int bodyY() { return py + headH() + (Theme.modern() ? 4 : 8); }
    private int bodyBottom() { return py + ph - inset(); }
    private int gridW() { return contentW() - BAR_W - 4; }
    private int titleX() { return page == Page.MODULE ? contentX() + BTN + 6 : contentX(); }

    private static int switchW() { return Theme.modern() ? ToggleWidget.SWITCH_W : LEGACY_SWITCH_W; }
    private static int switchH() { return Theme.modern() ? ToggleWidget.SWITCH_H : LEGACY_SWITCH_H; }

    private void layout() {
        int minW = Math.min(460, this.width - 16), minH = Math.min(240, this.height - 16);
        pw = Math.max(minW, Math.min(this.width - 16, Math.round(this.width * 0.80f)));
        ph = Math.max(minH, Math.min(this.height - 16, Math.round(this.height * 0.80f)));
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        sw = Math.max(96, Math.min(132, Math.round(pw * 0.24f)));

        // the sidebar: brand, Modules, General, the version at the foot
        int items = Tab.values().length + Link.values().length;
        boolean modern = Theme.modern();
        int foot = modern ? PANEL_INSET + 4 : FOOT_H;
        // the modern items sit inside the sidebar's panel
        int ix = modern ? px + PANEL_INSET + 5 : px + 6, iw = modern ? sw - PANEL_INSET - 10 : sw - 12;
        itemH = Math.max(modern ? 11 : 13, Math.min(modern ? 20 : 18, (ph - brandH() - 2 * SECTION_H - 6 - foot - (modern ? 0 : 8)) / items));
        side.clear();
        int y = modulesY = py + brandH();
        y += SECTION_H;
        for (Tab t : Tab.values()) {
            side.add(new Hit("tab:" + t.name(), ix, y, iw, itemH));
            y += itemH;
        }
        generalY = y += 6;
        y += SECTION_H;
        for (Link l : Link.values()) {
            side.add(new Hit("link:" + l.name(), ix, y, iw, itemH));
            y += itemH;
        }

        // the header, from the right
        heads.clear();
        int hy = py + (headH() - BTN) / 2, right = px + pw - inset();
        heads.add(new Hit("close", right - BTN, hy, BTN, BTN));
        right -= BTN + 6;
        if (page == Page.LIST && modern) {
            // the list | cards switch, then the search field
            int sy = py + (headH() - SEARCH_H) / 2, vw = 2 * SEARCH_H + 2;
            heads.add(new Hit("view", right - vw, sy, vw, SEARCH_H));
            right -= vw + 6;
            int searchW = Math.min(210, right - contentX() - 130);
            if (searchW >= 60) {
                heads.add(new Hit("search", right - searchW, sy, searchW, SEARCH_H));
                search.setBounds(right - searchW + 22, sy, searchW - 30, SEARCH_H);
                right -= searchW + 8;
            } else {
                search.setBounds(0, 0, 0, 0);
            }
        } else if (page == Page.LIST) {
            heads.add(new Hit("view", right - BTN, hy, BTN, BTN));
            right -= BTN + 8;
            int searchW = Math.min(150, right - contentX() - 110);
            if (searchW >= 60) {
                heads.add(new Hit("search", right - searchW, hy, searchW, BTN));
                search.setBounds(right - searchW + 15, hy + (BTN - 12) / 2, searchW - 20, 12);
                right -= searchW + 8;
            } else {
                search.setBounds(0, 0, 0, 0);
            }
        } else if (page == Page.MODULE && module != null) {
            heads.add(new Hit("switch", right - switchW(), py + (headH() - switchH()) / 2, switchW(), switchH()));
            right -= switchW() + 10;
            int rw = textW("Reset all") + 18;
            heads.add(new Hit("reset", right - rw, hy, rw, BTN));
            right -= rw + 6;
            if (module instanceof HudElement && inWorld()) {
                int lw = textW("Edit layout") + 18;
                heads.add(new Hit("layout", right - lw, hy, lw, BTN));
                right -= lw + 6;
            }
            heads.add(new Hit("back", contentX(), hy, BTN, BTN));
        }
        titleRight = right;

        // the list: one column of rows, or cards
        rowW = gridW();
        buildItems();
        int gap = cardGap(), cardMin = modern ? MODERN_CARD_MIN_W : CARD_MIN_W;
        cols = Math.max(1, Math.min(MAX_COLS, (gridW() + gap) / (cardMin + gap)));
        cardW = (gridW() - gap * (cols - 1)) / cols;
        cardH = modern ? MODERN_CARD_H : Math.max(80, Math.min(115, Math.round(cardW * 0.72f)));
        clampScroll();
    }

    private int brandH() { return Theme.modern() ? ph >= 280 ? 54 : 44 : BRAND_H; }

    /**
     * The list's lines: under All with no search, a heading and a card per
     * category; otherwise one card of rows. The legacy look keeps its gaps
     * between plates in place of cards.
     */
    private void buildItems() {
        items.clear();
        runs.clear();
        boolean modern = Theme.modern(), sections = tab == Tab.ALL && query.isEmpty();
        int rowH = modern ? LIST_ROW_H : ROW_H, gap = modern ? 0 : ROW_GAP;
        int y = 0, start = -1;
        Module.Category cat = null;
        for (Module m : shown) {
            if (sections && m.category() != cat) {
                if (start >= 0) {
                    runs.add(new int[] {start, y - gap - start});
                    y += GROUP_GAP;
                }
                cat = m.category();
                int n = 0;
                for (Module o : shown) if (o.category() == cat) n++;
                items.add(new Item(null, cat.label, n, y, HEADING_H, false));
                y += HEADING_H;
                start = -1;
            }
            boolean first = start < 0;
            if (first) start = y;
            items.add(new Item(m, "", 0, y, rowH, first));
            y += rowH + gap;
        }
        if (start >= 0) runs.add(new int[] {start, y - gap - start});
        listH = items.isEmpty() ? 0 : y - gap;
    }

    private int contentHeight() {
        if (!grid) return listH;
        int rows = (shown.size() + cols - 1) / cols;
        return rows == 0 ? 0 : rows * (cardH + cardGap()) - cardGap();
    }

    private int maxScroll() { return Math.max(0, contentHeight() - (bodyBottom() - bodyY())); }

    private void clampScroll() { scroll = Math.max(0, Math.min(maxScroll(), scroll)); }

    private static int cardGap() { return Theme.modern() ? 8 : CARD_GAP; }
    private int cardX(int i) { return contentX() + (i % cols) * (cardW + cardGap()); }
    private int cardY(int i) { return bodyY() + (i / cols) * (cardH + cardGap()) - scroll; }
    private int barX() { return contentX() + contentW() - BAR_W; }

    private boolean inBody(double mx, double my) {
        return mx >= contentX() && mx < contentX() + gridW() && my >= bodyY() && my < bodyBottom();
    }

    /** {x, y} of the switch on a row {@code rh} tall. */
    private int[] switchAt(int rx, int ry, int rh) {
        return new int[] {rx + rowW - (Theme.modern() ? 12 : 8) - switchW(), ry + (rh - switchH()) / 2};
    }

    // ---- card geometry (Figma's 307x223 card, scaled to the card width) ------------------

    private float k() { return cardW / 307f; }
    private int margin() { return Math.max(5, Math.round(14 * k())); }
    private int buttonH() { return Math.max(14, Math.round(40 * k())); }

    /** {x, y, w, h} of the ENABLED button and the gear on the card at {@code (cx, cy)}. */
    private int[] toggleBox(int cx, int cy) {
        if (Theme.modern()) {
            // the switch at the top right, a little past its edges
            int[] s = cardSwitch(cx, cy);
            return new int[] {s[0] - 3, s[1] - 3, ToggleWidget.SWITCH_W + 6, ToggleWidget.SWITCH_H + 6};
        }
        int m = margin(), bh = buttonH(), gap = Math.max(3, Math.round(8 * k()));
        return new int[] {cx + m, cy + cardH - m - bh, cardW - 2 * m - bh - gap, bh};
    }

    private int[] gearBox(int cx, int cy) {
        int m = margin(), bh = buttonH();
        return new int[] {cx + cardW - m - bh, cy + cardH - m - bh, bh, bh};
    }

    /** {x, y} of the switch on the modern card at {@code (cx, cy)}. */
    private int[] cardSwitch(int cx, int cy) {
        return new int[] {cx + cardW - CARD_MARGIN - ToggleWidget.SWITCH_W, cy + CARD_MARGIN + 1};
    }

    private static final int CARD_MARGIN = 11;

    private static boolean in(int[] b, double mx, double my) {
        return Vanilla.inside(mx, my, b[0], b[1], b[2], b[3]);
    }

    // ---- text ----------------------------------------------------------------

    /** Small flat text: the sans in the modern look, the vanilla font in the legacy one. */
    private static void small(Canvas c, String text, int x, int y, int color) {
        if (Theme.modern()) Theme.sansPush();
        c.text(text, x, y, color, false);
        if (Theme.modern()) Theme.sansPop();
    }

    private int textW(String text) {
        if (!Theme.modern()) return this.font.width(text);
        Theme.sansPush();
        int w = this.font.width(text);
        Theme.sansPop();
        return w;
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        tip = null;
        if (inWorld()) c.fill(0, 0, this.width, this.height, Theme.pick(0x660A0B1C, 0x4D000000));
        drawWindow(c);
        drawSidebar(c, mouseX, mouseY);
        drawHeader(c, mouseX, mouseY);
        if (page == Page.LIST) {
            drawList(c, mouseX, mouseY);
        } else {
            pane.layout(contentX(), bodyY(), contentW(), bodyBottom() - bodyY());
            pane.render(c, mouseX, mouseY);
        }
        if (tip != null) Vanilla.tooltip(c, tip, mouseX, mouseY, this.width, this.height);
    }

    /**
     * The window: in the modern look a rounded night-blue sheet on a soft
     * shadow, the sidebar an inset panel of its own; in the legacy one a
     * black outline round the 90% body, the sidebar darker behind a black rule.
     */
    private void drawWindow(Canvas c) {
        if (Theme.modern()) {
            int r = Px.radius(ph, 18);
            Round.begin(c);
            int x0 = Round.w(px), y0 = Round.w(py), x1 = Round.w(px + pw), y1 = Round.w(py + ph);
            Round.softW(c, x0, y0, x1, y1, r, Round.w(12), Round.w(4), 0x80000000);
            Round.fillW(c, x0, y0, x1, y1, r, 0xF70D0E22);
            Round.end(c);
            Round.ring(c, px, py, pw, ph, r, Theme.LINE_2);
            int in = PANEL_INSET;
            Round.fill(c, px + in, py + in, sw - in, ph - 2 * in, Px.radius(ph, 13), 0x66050611, Theme.LINE);
            return;
        }
        c.outline(px, py, pw, ph, 0xFF000000);
        c.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, 0xE61E1E1E);
        c.fill(px + 1, py + 1, px + sw, py + ph - 1, 0x59000000);
        c.fill(px + sw, py + 1, px + sw + 1, py + ph - 1, 0xFF000000);
        c.fill(mainX() + 1, py + HEAD_H, px + pw - 1, py + HEAD_H + 1, 0xFF000000);
    }

    private void drawSidebar(Canvas c, int mouseX, int mouseY) {
        boolean modern = Theme.modern();
        int ms = Theme.markSize(modern ? 16 : 14), bx = px + (modern ? PANEL_INSET + 9 : 10);
        // the modern brand block sits in the sidebar panel, below its top edge
        int brandTop = modern ? py + PANEL_INSET : py, brandRoom = modern ? brandH() - PANEL_INSET : brandH();
        Theme.mark(c, bx, brandTop + (brandRoom - ms) / 2, ms, 0xFF);
        int nx = bx + ms + (modern ? 7 : 6) - Px.pad(), nw = sw - (nx - px) - 6;
        if (modern && !VERSION.isEmpty()) {
            // the name over the version, as one block centred on the mark
            int by = brandTop + (brandRoom - 24) / 2;
            Component name = Component.empty().append(Component.literal("Dusk ").withStyle(ChatFormatting.BOLD))
                    .append(Component.literal("Client").withStyle(st -> st.withItalic(true).withColor(LAVENDER)));
            Px.sans(c, name, nx, by, nw, 14, Px.Size.S20, Theme.TEXT, true);
            Px.sans(c, "v" + VERSION, nx, by + 14, nw, 10, Px.Size.S11, Theme.TEXT_FAINT, false, true);
        } else if (modern) {
            Px.sans(c, "Dusk", nx, py, nw, brandH(), Px.Size.S20, Theme.TEXT, true, true);
        } else {
            Px.label(c, "Dusk", nx, py, nw, BRAND_H, Px.Size.S20, Px.Tone.ACCENT, false, false, true, 0xFF);
        }

        section(c, "Modules", modulesY);
        section(c, "General", generalY);
        for (Hit h : side) {
            boolean hover = h.in(mouseX, mouseY);
            if (h.id().startsWith("tab:")) {
                Tab t = Tab.valueOf(h.id().substring(4));
                sideItem(c, h, t.icon, t.label, String.valueOf(counts[t.ordinal()]), page == Page.LIST && tab == t, hover, false);
            } else {
                Link l = Link.valueOf(h.id().substring(5));
                boolean off = l == Link.HUD && !inWorld();
                sideItem(c, h, l.icon, l.label, "", l == Link.PREFS && page == Page.PREFS, hover, off);
                if (hover && off) tip = "Join a world to edit the HUD layout";
            }
        }
        if (!modern && !VERSION.isEmpty() && generalY + SECTION_H + Link.values().length * itemH + FOOT_H <= py + ph - 4) {
            small(c, "v" + VERSION, bx, py + ph - FOOT_H, modern ? Theme.TEXT_FAINT : Theme.DIM);
        }
    }

    private void section(Canvas c, String label, int y) {
        int x = px + 12 - Px.pad();
        if (Theme.modern()) {
            x = px + PANEL_INSET + 11 - Px.pad();
            Px.sans(c, label.toUpperCase(Locale.ROOT), x, y, sw - 24, SECTION_H, Px.Size.S11, Theme.TEXT_FAINT, true, true);
        }
        else Px.label(c, label, x, y, sw - 20, SECTION_H, Px.Size.S11, Px.Tone.DIM, false, false, true, 0xFF);
    }

    private void sideItem(Canvas c, Hit h, Icons icon, String label, String count, boolean selected, boolean hover, boolean off) {
        int x = h.x(), y = h.y(), w = h.w(), ih = h.h();
        int countW = count.isEmpty() ? 0 : textW(count) + 6;
        int iy = y + (ih - icon.height()) / 2, ix = x + 6 + (7 - icon.width()) / 2;
        int tx = x + 18 - Px.pad(), tw = w - 18 - countW + Px.pad();
        if (Theme.modern()) {
            // the accent under the page shown, a faint wash under the pointer
            int r = Px.radius(ih - 2, 8);
            if (selected) Round.fill(c, x, y + 1, w, ih - 2, r, Theme.ACCENT);
            else if (hover && !off) Round.fill(c, x, y + 1, w, ih - 2, r, Theme.HOVER);
            int col = off ? Theme.TEXT_FAINT : selected || hover ? Theme.TEXT : Theme.TEXT_MUTED;
            ix = x + 8 + (7 - icon.width()) / 2;
            icon.draw(c, ix, iy, off ? Theme.TEXT_FAINT : selected ? Theme.INK : hover ? Theme.TEXT : Theme.GLYPH);
            int cw = count.isEmpty() ? 0 : Math.round(Px.sansWidth(c, count, Px.Size.S11, false)) + 4;
            tx = x + 21 - Px.pad();
            Px.sans(c, label, tx, y, w - 21 - cw - 8 + 2 * Px.pad(), ih, Px.Size.S16, selected ? Theme.INK : col, selected, true);
            if (!count.isEmpty()) {
                int cx = x + w - 9 - cw + 4;
                Px.sans(c, count, cx - Px.pad(), y, cw - 4 + 2 * Px.pad(), ih, Px.Size.S11,
                        selected ? 0xD9FFFFFF : Theme.TEXT_FAINT, false, true);
            }
            return;
        }
        if (selected || hover && !off) Theme.cell(c, x, y, w, ih, hover && !off);
        icon.draw(c, ix, iy, Theme.filter(selected ? Theme.ACTIVE_UP : Theme.GLYPH, hover, off));
        Px.label(c, label, tx, y, tw, ih, Px.Size.S14, selected ? Px.Tone.ACTIVE : Px.Tone.GREY, hover, off, true, 0xFF);
        if (!count.isEmpty()) c.text(count, x + w - 6 - textW(count), y + (ih - 7) / 2, Theme.DIM, false);
    }

    private void drawHeader(Canvas c, int mouseX, int mouseY) {
        int tx = titleX(), tw = Math.max(0, titleRight - tx);
        String title, sub;
        if (page == Page.MODULE && module != null) {
            title = module.name();
            sub = module.description();
        } else if (page == Page.PREFS) {
            title = "Preferences";
            sub = "";
        } else {
            title = tab.title;
            int on = 0;
            for (Module m : shown) if (m.enabled()) on++;
            sub = !query.isEmpty()
                    ? shown.size() + (shown.size() == 1 ? " match" : " matches") + " for \"" + search.text().trim() + "\""
                    : shown.size() + (shown.size() == 1 ? " module" : " modules") + " · " + on + " enabled";
        }
        if (Theme.modern()) {
            // the list's title leads with its category in lavender italics: "Render modules"
            Component heading = Component.literal(title).withStyle(ChatFormatting.BOLD);
            if (page == Page.LIST) {
                String word = query.isEmpty() ? tab.label : "Search", rest = query.isEmpty() ? " modules" : " results";
                heading = Component.empty()
                        .append(Component.literal(word).withStyle(st -> st.withBold(true).withItalic(true).withColor(LAVENDER)))
                        .append(Component.literal(rest).withStyle(ChatFormatting.BOLD));
            }
            int hx = tx - Px.pad(), hw = tw + 2 * Px.pad();
            if (sub.isEmpty()) {
                Px.sans(c, heading, hx, py, hw, headH(), Px.Size.S20, Theme.TEXT, true);
            } else {
                Px.sans(c, heading, hx, py + 9, hw, 16, Px.Size.S20, Theme.TEXT, true);
                Px.sans(c, sub, hx, py + 27, hw, 11, Px.Size.S11, Theme.TEXT_MUTED, false, true);
            }
        } else if (sub.isEmpty()) {
            Px.label(c, title, tx - Px.pad(), py, tw + Px.pad(), HEAD_H, Px.Size.S20, Px.Tone.ACTIVE, false, false, true, 0xFF);
        } else {
            Px.label(c, title, tx - Px.pad(), py + 6, tw + Px.pad(), 16, Px.Size.S20, Px.Tone.ACTIVE, false, false, true, 0xFF);
            Px.sans(c, sub, tx - Px.pad(), py + 23, tw + Px.pad(), 11, Px.Size.S11, Theme.modern() ? Theme.TEXT_MUTED : Theme.DIM, false, true);
        }

        for (Hit h : heads) {
            boolean hover = h.in(mouseX, mouseY);
            switch (h.id()) {
                case "close" -> {
                    iconButton(c, h, Icons.CLOSE, hover, true);
                    if (hover) tip = "Close";
                }
                case "view" -> {
                    if (Theme.modern()) {
                        drawViewSwitch(c, h, mouseX, mouseY);
                        if (hover) tip = mouseX < h.x() + h.w() / 2 ? "Show as a list" : "Show as cards";
                        break;
                    }
                    iconButton(c, h, grid ? Icons.LIST : Icons.GRID, hover, false);
                    if (hover) tip = grid ? "Show as a list" : "Show as cards";
                }
                case "back" -> {
                    iconButton(c, h, Icons.BACK, hover, false);
                    if (hover) tip = direct ? "Back" : "Back to the modules";
                }
                case "search" -> drawSearch(c, h, mouseX, mouseY);
                case "reset" -> textButton(c, h, "Reset all", hover);
                case "layout" -> textButton(c, h, "Edit layout", hover);
                case "switch" -> {
                    if (module == null) break;
                    if (Theme.modern()) headKnob = ToggleWidget.drawSwitch(c, h.x(), h.y(), module.enabled(), hover, headKnob);
                    else legacySwitch(c, h.x(), h.y(), module.enabled(), mouseX, mouseY);
                }
                default -> {}
            }
        }
    }

    private void drawSearch(Canvas c, Hit h, int mouseX, int mouseY) {
        if (Theme.modern()) {
            boolean hot = h.in(mouseX, mouseY);
            int ring = search.focused() ? Theme.ACCENT & 0xFFFFFF | 0xB3 << 24 : hot ? Theme.LINE_3 : Theme.LINE_2;
            Round.fill(c, h.x(), h.y(), h.w(), h.h(), Px.radius(h.h(), 9), 0x0AFFFFFF, ring);
            Icons.SEARCH.draw(c, h.x() + 9, h.y() + (h.h() - 7) / 2, search.focused() ? Theme.TEXT : Theme.TEXT_FAINT);
        } else {
            Theme.cell(c, h.x(), h.y(), h.w(), h.h(), false);
            Icons.SEARCH.draw(c, h.x() + 5, h.y() + (h.h() - 7) / 2, 0xFF7B7B7B);
        }
        search.render(c, mouseX, mouseY);
    }

    /** The modern list | cards switch: two icons in an outlined box, the view shown raised. */
    private static void drawViewSwitch(Canvas c, Hit h, int mouseX, int mouseY) {
        int r = Px.radius(h.h(), 9), half = h.w() / 2;
        Round.fill(c, h.x(), h.y(), h.w(), h.h(), r, 0x0AFFFFFF, Theme.LINE_2);
        for (boolean cards : new boolean[] {false, true}) {
            int sx = h.x() + (cards ? half : 0) + 2, sy = h.y() + 2, sw = half - 3, sh = h.h() - 4;
            boolean sel = cards == grid, hover = Vanilla.inside(mouseX, mouseY, sx, sy, sw, sh);
            if (sel) Round.fill(c, sx, sy, sw, sh, Px.radius(sh, 7), Theme.RAISED_2);
            Icons icon = cards ? Icons.GRID : Icons.LIST;
            icon.draw(c, sx + (sw - icon.width()) / 2, sy + (sh - icon.height()) / 2,
                    sel ? Theme.TEXT : hover ? Theme.TEXT_MUTED : Theme.TEXT_FAINT);
        }
    }

    /** A round icon button (a square cell in the legacy look); {@code danger} reddens it on hover. */
    private static void iconButton(Canvas c, Hit h, Icons icon, boolean hover, boolean danger) {
        int ix = h.x() + (h.w() - icon.width()) / 2, iy = h.y() + (h.h() - icon.height()) / 2;
        if (Theme.modern()) {
            if (hover) Round.dot(c, h.x(), h.y(), h.w(), danger ? 0x33FF6B5E : 0x1FFFFFFF);
            icon.draw(c, ix, iy, danger && hover ? Theme.BAD : hover ? Theme.TEXT : Theme.GLYPH);
            return;
        }
        if (danger) {
            Theme.closeCell(c, h.x(), h.y(), h.w(), h.h(), hover);
            icon.draw(c, ix, iy, Theme.RED_CORNER);
        } else {
            Theme.cell(c, h.x(), h.y(), h.w(), h.h(), hover);
            icon.draw(c, ix, iy, Theme.filter(Theme.GLYPH, hover, false));
        }
    }

    private static void textButton(Canvas c, Hit h, String label, boolean hover) {
        if (Theme.modern()) {
            Round.fill(c, h.x(), h.y(), h.w(), h.h(), 64, hover ? Theme.RAISED_2 : Theme.RAISED, hover ? Theme.LINE_3 : Theme.LINE_2);
            Px.sans(c, label, h.x(), h.y(), h.w(), h.h(), Px.Size.S14, hover ? Theme.TEXT : Theme.TEXT_MUTED, true, false);
            return;
        }
        Px.button(c, h.x(), h.y(), h.w(), h.h(), hover, true);
        Px.label(c, label, h.x(), h.y(), h.w(), h.h(), Px.Size.S14, Px.Tone.GREY, hover, false, false, 0xFF);
    }

    /** The legacy look's switch: Off and On segments, the current one in the accent family. */
    private static void legacySwitch(Canvas c, int x, int y, boolean on, int mouseX, int mouseY) {
        int seg = (LEGACY_SWITCH_W - 1) / 2;
        for (boolean v : new boolean[] {false, true}) {
            int sx = x + (v ? seg + 1 : 0);
            boolean sel = v == on, hover = Vanilla.inside(mouseX, mouseY, sx, y, seg, LEGACY_SWITCH_H);
            Px.box(c, sx, y, seg, LEGACY_SWITCH_H, sel ? Theme.Family.ACCENT : Theme.Family.GREY, hover, false, 0xFF);
            Px.label(c, v ? "On" : "Off", sx, y, seg, LEGACY_SWITCH_H, Px.Size.S11, sel ? Px.Tone.ACCENT : Px.Tone.GREY,
                    hover, false, false, 0xFF);
        }
    }

    private void drawList(Canvas c, int mouseX, int mouseY) {
        int top = bodyY(), bottom = bodyBottom();
        boolean inBody = inBody(mouseX, mouseY);
        int mx = inBody ? mouseX : -1, my = inBody ? mouseY : -1;
        c.scissor(contentX(), top, contentX() + contentW(), bottom);
        if (grid) {
            for (int i = 0; i < shown.size(); i++) {
                int x = cardX(i), y = cardY(i);
                if (y + cardH < top || y > bottom) continue;
                drawCard(c, shown.get(i), x, y, mx, my);
            }
        } else {
            int x = contentX(), y0 = top - scroll;
            if (Theme.modern()) {
                for (int[] run : runs) {
                    if (y0 + run[0] + run[1] < top || y0 + run[0] > bottom) continue;
                    Round.fill(c, x, y0 + run[0], rowW, run[1], Px.radius(LIST_ROW_H, 18), Theme.SURFACE, Theme.LINE);
                }
            }
            for (Item it : items) {
                int y = y0 + it.y();
                if (y + it.h() < top || y > bottom) continue;
                if (it.module() == null) drawHeading(c, it, x, y);
                else drawRow(c, it, x, y, mx, my);
            }
        }
        c.unscissor();
        if (shown.isEmpty()) {
            String empty = !query.isEmpty() ? "No modules match \"" + search.text().trim() + "\"" : "No modules here.";
            if (Theme.modern()) Px.sans(c, empty, contentX() - Px.pad(), top + 2, gridW() + 2 * Px.pad(), 14, Px.Size.S14, Theme.TEXT_MUTED, false, true);
            else small(c, empty, contentX(), top + 4, Theme.TEXT_MUTED);
        }
        if (maxScroll() > 0) {
            int h = bottom - top, barH = Math.max(16, h * h / contentHeight());
            int barY = top + (h - barH) * scroll / maxScroll();
            if (Theme.modern()) {
                Round.pill(c, barX(), barY, BAR_W, barH, draggingBar ? 0xFF6A6C9E : 0xFF35375F);
            } else {
                c.fill(barX(), top, barX() + BAR_W, bottom, 0xFF141414);
                c.fill(barX(), barY, barX() + BAR_W, barY + barH, draggingBar ? Theme.LABEL_UP : Theme.LABEL_LO);
            }
        }
    }

    /** A category's heading over its card: the name, then the module count, faint. */
    private void drawHeading(Canvas c, Item it, int x, int y) {
        int ty = y + it.h() - 16;
        if (Theme.modern()) {
            Px.sans(c, it.heading(), x + 4 - Px.pad(), ty, rowW, 12, Px.Size.S14, Theme.TEXT_MUTED, true, true);
            int nx = x + 4 + Math.round(Px.sansWidth(c, it.heading(), Px.Size.S14, true)) + 5;
            Px.sans(c, String.valueOf(it.count()), nx - Px.pad(), ty, 40, 12, Px.Size.S11, Theme.TEXT_FAINT, false, true);
        } else {
            Px.label(c, it.heading(), x + 2 - Px.pad(), ty, rowW, 12, Px.Size.S11, Px.Tone.DIM, false, false, true, 0xFF);
        }
    }

    /** The New tag: lavender on a faint accent pill, its width returned. */
    private int newTag(Canvas c, int x, int y, boolean right) {
        int tw = Math.round(Px.sansWidth(c, "New", Px.Size.S11, true)) + 10, th = 11;
        int tx = right ? x - tw : x;
        Round.fill(c, tx, y, tw, th, 64, Theme.ACCENT & 0xFFFFFF | 0x38 << 24, Theme.ACCENT & 0xFFFFFF | 0x66 << 24);
        Px.sans(c, "New", tx, y, tw, th, Px.Size.S11, LAVENDER, true, false);
        return tw;
    }

    /**
     * A module row: its icon, the name over the description, a New tag, and
     * the switch at the right. In the modern look the rows share their
     * category's card, a hairline between them and a soft wash under the
     * pointer; the chevron to the settings shows only on hover.
     */
    private void drawRow(Canvas c, Item it, int rx, int ry, int mouseX, int mouseY) {
        Module m = it.module();
        boolean modern = Theme.modern();
        int rh = it.h();
        int[] s = switchAt(rx, ry, rh);
        boolean hot = Vanilla.inside(mouseX, mouseY, rx, ry, rowW, rh);
        boolean onSwitch = hot && Vanilla.inside(mouseX, mouseY, s[0], s[1], switchW(), switchH());
        if (modern) {
            Round.begin(c);
            if (hot && !onSwitch) {
                // the wash stays inside the card's rounding
                int inset = Math.max(2, Round.w(3)), r = Px.radius(LIST_ROW_H, 18);
                Round.fillW(c, Round.w(rx) + inset, Round.w(ry) + inset, Round.w(rx + rowW) - inset, Round.w(ry + rh) - inset,
                        Math.max(0, r - inset), Theme.HOVER);
            }
            if (!it.first()) {
                int ly = Round.w(ry);
                c.fill(Round.w(rx + 12), ly, Round.w(rx + rowW - 12), ly + Px.line(), Theme.LINE);
            }
            Round.end(c);
        } else {
            Theme.plate(c, rx, ry, rowW, rh, Theme.SURFACE, Theme.SURFACE, hot && !onSwitch);
        }
        if (!modern) drawIcon(c, m, rx + 7, ry + (rh - 16) / 2, Theme.LABEL_LO);

        int tx = rx + (modern ? 14 : 30), right = s[0] - (modern ? 10 : 18);
        if (modern) {
            if (hot && !onSwitch) {
                Icons.NEXT.draw(c, right - Icons.NEXT.width(), ry + (rh - Icons.NEXT.height()) / 2, Theme.TEXT_MUTED);
            }
            right -= Icons.NEXT.width() + 8;
        } else {
            Icons.NEXT.draw(c, s[0] - 12, ry + (rh - 5) / 2, Theme.filter(Theme.GLYPH, hot && !onSwitch, false));
        }
        if (NEW_IDS.contains(m.id())) {
            if (modern) {
                right -= newTag(c, right, ry + (rh - 11) / 2, true);
            } else {
                right -= c.textWidth("NEW");
                c.text("NEW", right, ry + (rh - 7) / 2, Theme.ACCENT, false);
            }
            right -= 6;
        }

        int w = right - tx + Px.pad();
        String desc = m.description();
        if (modern) {
            w += Px.pad();
            if (desc.isEmpty()) {
                Px.sans(c, m.name(), tx - Px.pad(), ry, w, rh, Px.Size.S16, Theme.TEXT, true, true);
            } else {
                int by = ry + (rh - 25) / 2;
                Px.sans(c, m.name(), tx - Px.pad(), by, w, 13, Px.Size.S16, Theme.TEXT, true, true);
                Px.sans(c, desc, tx - Px.pad(), by + 14, w, 11, Px.Size.S11, Theme.TEXT_MUTED, false, true);
            }
            knobs.put(m, ToggleWidget.drawSwitch(c, s[0], s[1], m.enabled(), onSwitch, knobs.getOrDefault(m, -1f)));
        } else {
            if (desc.isEmpty()) {
                Px.label(c, m.name(), tx - Px.pad(), ry, w, rh, Px.Size.S14, Px.Tone.GREY, hot && !onSwitch, false, true, 0xFF);
            } else {
                Px.label(c, m.name(), tx - Px.pad(), ry + 3, w, 13, Px.Size.S14, Px.Tone.GREY, hot && !onSwitch, false, true, 0xFF);
                c.text(Theme.ellipsize(c, desc, right - tx), tx, ry + 18, Theme.TEXT_MUTED, false);
            }
            legacySwitch(c, s[0], s[1], m.enabled(), mouseX, mouseY);
        }
    }

    /** The module's item, or its category's glyph when it has none, in a 16px square. */
    private static void drawIcon(Canvas c, Module m, int x, int y, int glyphColor) {
        ItemStack stack = ModuleIcons.of(m.id());
        if (!stack.isEmpty()) {
            c.item(stack, x, y);
            return;
        }
        Icons glyph = categoryGlyph(m);
        glyph.draw(c, x + (16 - glyph.width() * 2) / 2, y + (16 - glyph.height() * 2) / 2, glyphColor, 2);
    }

    private static Icons categoryGlyph(Module m) {
        return switch (m.category()) {
            case HUD -> Icons.HUD;
            case RENDER -> Icons.EYE;
            case MOVEMENT -> Icons.ARROW;
            case MISC -> Icons.DOTS;
        };
    }

    private void drawCard(Canvas c, Module m, int cx, int cy, int mouseX, int mouseY) {
        int cw = cardW, mg = margin();
        int[] tb = toggleBox(cx, cy), gb = gearBox(cx, cy);
        boolean hot = Vanilla.inside(mouseX, mouseY, cx, cy, cw, cardH);
        boolean onToggle = hot && in(tb, mouseX, mouseY), onGear = hot && in(gb, mouseX, mouseY);
        if (Theme.modern()) {
            drawCardModern(c, m, cx, cy, tb, gb, hot, onToggle, onGear);
            return;
        }
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

        drawCardIcon(c, m, cx, nameBottom + 2, tb[1] - 2, Theme.LABEL_LO);

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

    /** The card's icon, centred between {@code areaTop} and {@code areaBot}: doubled when there is room. */
    private void drawCardIcon(Canvas c, Module m, int cx, int areaTop, int areaBot, int glyphColor) {
        int areaH = areaBot - areaTop, cw = cardW;
        int is = areaH >= 38 && cw >= 130 ? 2 : 1;
        if (areaH < 16) return;
        ItemStack stack = ModuleIcons.of(m.id());
        int size = 16 * is, ix = cx + (cw - size) / 2, iy = areaTop + (areaH - size) / 2;
        if (!stack.isEmpty()) {
            c.push();
            c.translate(ix, iy);
            c.scale(is, is);
            c.item(stack, 0, 0);
            c.pop();
        } else {
            Icons glyph = categoryGlyph(m);
            int gs = 2 * is;
            glyph.draw(c, cx + (cw - glyph.width() * gs) / 2, areaTop + (areaH - glyph.height() * gs) / 2, glyphColor, gs);
        }
    }

    /**
     * The modern card: a rounded tile with the name and its switch along the
     * top, two lines of the description under it, and the category (or a
     * New tag) and a chevron along the foot. Enabled cards take a faint
     * accent edge; anywhere off the switch opens the settings.
     */
    private void drawCardModern(Canvas c, Module m, int cx, int cy, int[] tb, int[] gb, boolean hot, boolean onToggle, boolean onGear) {
        int cw = cardW, mg = CARD_MARGIN;
        boolean on = m.enabled(), lit = hot && !onToggle;
        int ring = on ? Theme.ACCENT & 0xFFFFFF | (lit ? 0x8C : 0x59) << 24 : lit ? Theme.LINE_3 : Theme.LINE_2;
        Round.fill(c, cx, cy, cw, cardH, Px.radius(cardH, 12), lit ? 0xFF1B1D3D : Theme.SURFACE, ring);

        int[] s = cardSwitch(cx, cy);
        int tx = cx + mg, nameW = s[0] - 8 - tx;
        Px.sans(c, m.name(), tx - Px.pad(), cy + mg - 1, nameW + 2 * Px.pad(), 16, Px.Size.S16, Theme.TEXT, true, true);
        knobs.put(m, ToggleWidget.drawSwitch(c, s[0], s[1], on, onToggle, knobs.getOrDefault(m, -1f)));

        int textW = cw - 2 * mg, dy = cy + mg + 19;
        for (String line : lines(c, m.description(), textW, 2)) {
            Px.sans(c, line, tx - Px.pad(), dy, textW + 2 * Px.pad(), 11, Px.Size.S11, Theme.TEXT_MUTED, false, true);
            dy += 11;
        }

        int fy = cy + cardH - mg - 11;
        if (NEW_IDS.contains(m.id())) newTag(c, tx, fy, false);
        else Px.sans(c, m.category().label, tx - Px.pad(), fy, textW, 11, Px.Size.S11, Theme.TEXT_FAINT, false, true);
        Icons.NEXT.draw(c, cx + cw - mg - Icons.NEXT.width(), fy + (11 - Icons.NEXT.height()) / 2, lit ? Theme.TEXT : Theme.TEXT_FAINT);
    }

    /** {@code text} broken at spaces into at most {@code max} lines {@code w} wide in the small sans, the last cut short. */
    private static List<String> lines(Canvas c, String text, int w, int max) {
        List<String> out = new ArrayList<>();
        int room = w - 1;
        String rest = text.trim();
        while (!rest.isEmpty() && out.size() < max) {
            if (out.size() == max - 1 || Px.sansWidth(c, rest, Px.Size.S11, false) <= room) {
                String line = rest;
                if (Px.sansWidth(c, line, Px.Size.S11, false) > room) {
                    while (!line.isEmpty() && Px.sansWidth(c, line + "...", Px.Size.S11, false) > room) line = line.substring(0, line.length() - 1);
                    line = line.stripTrailing() + "...";
                }
                out.add(line);
                break;
            }
            int cut = rest.length();
            while (cut > 0) {
                int sp = rest.lastIndexOf(' ', cut - 1);
                if (sp <= 0) { cut = -1; break; }
                cut = sp;
                if (Px.sansWidth(c, rest.substring(0, cut), Px.Size.S11, false) <= room) break;
            }
            if (cut <= 0) {
                // one word too long for the line: leave it to the last-line cut
                out.add(rest);
                break;
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut + 1).trim();
        }
        return out;
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (page == Page.LIST && search.w >= 22 && search.contains(mx, my)) return search.click(mx, my, button);
        search.setFocused(false);
        for (Hit h : side) {
            if (!h.in(mx, my)) continue;
            if (button == 0) clickSide(h.id());
            return true;
        }
        for (Hit h : heads) {
            if (!h.in(mx, my)) continue;
            if (button == 0) clickHead(h, mx);
            return true;
        }
        if (page != Page.LIST) return pane.click(mx, my, button) || Vanilla.inside(mx, my, px, py, pw, ph);
        if (maxScroll() > 0 && Vanilla.inside(mx, my, barX() - 2, bodyY(), BAR_W + 4, bodyBottom() - bodyY())) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        if (inBody(mx, my) && grid) {
            for (int i = 0; i < shown.size(); i++) {
                int x = cardX(i), y = cardY(i);
                if (!Vanilla.inside(mx, my, x, y, cardW, cardH)) continue;
                Module m = shown.get(i);
                if (button == 0 && in(toggleBox(x, y), mx, my)) {
                    m.setEnabled(!m.enabled());
                    saveModules();
                } else if (button == 0 || button == 1) {
                    showModule(m);
                }
                return true;
            }
        } else if (inBody(mx, my)) {
            for (Item it : items) {
                int x = contentX(), y = bodyY() - scroll + it.y();
                if (it.module() == null || !Vanilla.inside(mx, my, x, y, rowW, it.h())) continue;
                Module m = it.module();
                int[] sw = switchAt(x, y, it.h());
                if (button == 0 && onSwitch(sw, mx, my)) {
                    m.setEnabled(switchValue(sw[0], mx, m.enabled()));
                    saveModules();
                } else if (button == 0 || button == 1) {
                    showModule(m);
                }
                return true;
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    private static boolean onSwitch(int[] s, double mx, double my) {
        return Vanilla.inside(mx, my, s[0], s[1], switchW(), switchH());
    }

    /** What a click at {@code mx} on a switch at {@code x} sets: a flip, or the legacy segment clicked. */
    private static boolean switchValue(int x, double mx, boolean now) {
        if (Theme.modern()) return !now;
        return mx >= x + (LEGACY_SWITCH_W - 1) / 2 + 1;
    }

    private void clickSide(String id) {
        if (id.startsWith("tab:")) {
            showTab(Tab.valueOf(id.substring(4)));
            return;
        }
        switch (Link.valueOf(id.substring(5))) {
            case PREFS -> showPreferences();
            case HUD -> { if (inWorld()) leaveTo(new HudEditorScreen(this)); }
            case STYLES -> leaveTo(new StylesScreen(this));
            case WARDROBE -> leaveTo(new WardrobeScreen(this));
            case SOCIAL -> {
                commit();
                SocialScreen.show(this);
            }
        }
    }

    private void clickHead(Hit h, double mx) {
        switch (h.id()) {
            case "close" -> exitMenu();
            case "view" -> {
                boolean cards = !Theme.modern() ? !grid : mx >= h.x() + h.w() / 2.0;
                if (cards != grid) scroll = 0;
                grid = cards;
            }
            case "search" -> search.setFocused(true);
            case "back" -> back();
            case "reset" -> resetModule();
            case "layout" -> leaveTo(new HudEditorScreen(this));
            case "switch" -> {
                if (module == null) return;
                boolean v = switchValue(h.x(), mx, module.enabled());
                if (v != module.enabled()) {
                    module.setEnabled(v);
                    saveModules();
                }
            }
            default -> {}
        }
    }

    private void dragBar(double my) {
        int h = bodyBottom() - bodyY();
        scroll = (int) ((my - bodyY()) / h * contentHeight() - h / 2.0);
        clampScroll();
    }

    @Override
    protected boolean menuDrag(double mx, double my, int button) {
        if (page != Page.LIST) return pane.drag(mx, my);
        if (!draggingBar) return false;
        dragBar(my);
        return true;
    }

    @Override
    protected boolean menuRelease(double mx, double my, int button) {
        if (page != Page.LIST) {
            boolean had = pane.release();
            if (had) saveModules();
            return had;
        }
        boolean had = draggingBar;
        draggingBar = false;
        return had;
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        if (page != Page.LIST) return pane.scroll(mx, my, amount);
        scroll -= (int) Math.signum(amount) * SCROLL_STEP;
        clampScroll();
        return true;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        boolean cmd = (modifiers & Input.MOD_SHORTCUT) != 0;
        if (key == InputConstants.KEY_F && cmd) {
            if (page != Page.LIST) leavePage();
            search.setFocused(true);
            return true;
        }
        if (page != Page.LIST) {
            if (pane.keyPressed(key, modifiers)) return true;
            if (key == InputConstants.KEY_ESCAPE) {
                back();
                return true;
            }
            return false;
        }
        if (search.focused()) {
            // the menu key still closes the menu unless it would type into the search
            if (isSettingsKey(key, scancode) && (!Input.printable(key) || search.text().isEmpty())) return false;
            if (key == InputConstants.KEY_ESCAPE && !search.text().isEmpty()) {
                search.setText("");
                search.setFocused(false);
                return true;
            }
            return key != InputConstants.KEY_ESCAPE && search.keyPressed(key, modifiers);
        }
        return false;
    }

    @Override
    protected boolean menuChar(char ch) {
        if (page != Page.LIST) return pane.charTyped(ch);
        if (!search.focused()) {
            if (ch <= ' ') return false;
            search.setFocused(true); // type anywhere to search
        }
        return search.charTyped(ch);
    }

    @Override
    protected void beforeClose() {
        search.setFocused(false);
        pane.blur();
        super.beforeClose();
    }
}
