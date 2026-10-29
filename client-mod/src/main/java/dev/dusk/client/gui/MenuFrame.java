package dev.dusk.client.gui;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.media.MediaScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The Dusk menu's one window, shared by every page: the frosted sheet, the
 * sidebar in a pane of the same glass (the module categories, then
 * everything else the client has: Styles, Packs, Wardrobe, the HUD layout,
 * Friends & Chat, Quests, Media, Waypoints, Stats, Keybinds, Preferences)
 * and the page to its right. Every page draws the same frame, so moving
 * through the sidebar only changes what is right of it.
 */
public final class MenuFrame {
    private static final int BRAND_H = 30, SECTION_H = 14, SECTION_GAP = 4, GUTTER = 6, FOOT = 4;

    static final String VERSION = FabricLoader.getInstance().getModContainer("duskclient")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");

    public enum Section {
        MODULES("Modules"), CUSTOMISE("Customise"), SOCIAL("Social"), GAME("Game");

        final String label;

        Section(String label) {
            this.label = label;
        }
    }

    /** A sidebar entry, in sidebar order. */
    public enum Entry {
        ALL(Section.MODULES, "All", Icons.GRID), NEW(Section.MODULES, "New", Icons.SPARKLE),
        HUD(Section.MODULES, "HUD", Icons.HUD), RENDER(Section.MODULES, "Render", Icons.EYE),
        MOVEMENT(Section.MODULES, "Movement", Icons.RUN), UTILITY(Section.MODULES, "Utility", Icons.WRENCH),
        STYLES(Section.CUSTOMISE, "Styles", Icons.PALETTE), PACKS(Section.CUSTOMISE, "Packs", Icons.PACK),
        WARDROBE(Section.CUSTOMISE, "Wardrobe", Icons.SHIRT), LAYOUT(Section.CUSTOMISE, "HUD layout", Icons.MOVE, true),
        FRIENDS(Section.SOCIAL, "Friends & Chat", Icons.CHAT), QUESTS(Section.SOCIAL, "Quests", Icons.FLAG),
        MEDIA(Section.SOCIAL, "Media", Icons.MEDIA),
        WAYPOINTS(Section.GAME, "Waypoints", Icons.PIN), STATS(Section.GAME, "Stats", Icons.CHART, true),
        PERFORMANCE(Section.GAME, "Performance", Icons.MONITOR),
        KEYBINDS(Section.GAME, "Keybinds", Icons.KEYBOARD), PREFERENCES(Section.GAME, "Preferences", Icons.GEAR);

        final Section section;
        final String label;
        final Icons icon;
        /** Only in a world: hidden from the sidebar on the title screen. */
        final boolean world;

        Entry(Section section, String label, Icons icon) {
            this(section, label, icon, false);
        }

        Entry(Section section, String label, Icons icon, boolean world) {
            this.section = section;
            this.label = label;
            this.icon = icon;
            this.world = world;
        }

        public boolean module() { return section == Section.MODULES; }

        /** Shown by the module window itself rather than a screen of its own. */
        public boolean onHub() { return module() || this == KEYBINDS || this == PREFERENCES; }
    }

    private record Item(@Nullable Entry entry, @Nullable Section section, int y, int h) {}

    /** The sidebar's scroll, kept across pages so moving between them doesn't jump it. */
    private static int sideScroll;

    public int x, y, w, h, sw;
    private final List<Item> items = new ArrayList<>();
    private int itemsTop, itemsBottom, itemsH;
    private boolean world;

    // ---- layout -----------------------------------------------------------

    /** Lays the window out centred on a {@code screenW} x {@code screenH} screen. */
    public void layout(int screenW, int screenH, boolean inWorld) {
        world = inWorld;
        int minW = Math.min(460, screenW - 16), minH = Math.min(240, screenH - 16);
        w = screenW < 600 ? screenW - 16 : Math.max(minW, Math.min(screenW - 16, Math.round(screenW * 0.86f)));
        h = Math.max(minH, Math.min(screenH - 16, Math.round(screenH * 0.84f)));
        x = (screenW - w) / 2;
        y = (screenH - h) / 2;
        sw = Math.max(108, Math.min(144, Math.round(w * 0.22f)));

        List<Entry> shown = new ArrayList<>();
        for (Entry e : Entry.values()) if (!e.world || inWorld) shown.add(e);
        int sections = Section.values().length;
        itemsTop = paneY() + brandH();
        itemsBottom = paneY() + paneH() - FOOT;
        int avail = itemsBottom - itemsTop, n = shown.size();
        // the section labels go first when it is tight, leaving a gap between groups
        int itemH = (avail - sections * SECTION_H - (sections - 1) * SECTION_GAP) / n;
        boolean labels = itemH >= 12;
        if (!labels) itemH = (avail - (sections - 1) * (SECTION_GAP + 2)) / n;
        itemH = Math.max(11, Math.min(18, itemH));

        items.clear();
        int cy = 0;
        Section cur = null;
        for (Entry e : shown) {
            if (e.section != cur) {
                if (cur != null) cy += labels ? SECTION_GAP : SECTION_GAP + 2;
                cur = e.section;
                if (labels) {
                    items.add(new Item(null, cur, cy, SECTION_H));
                    cy += SECTION_H;
                }
            }
            items.add(new Item(e, null, cy, itemH));
            cy += itemH;
        }
        itemsH = cy;
        sideScroll = Math.max(0, Math.min(maxSideScroll(), sideScroll));
    }

    public int contentX() { return x + sw; }
    public int contentW() { return w - sw; }

    private boolean modern() { return Theme.modern(); }
    private int paneX() { return modern() ? x + GUTTER : x; }
    private int paneY() { return modern() ? y + GUTTER : y; }
    private int paneW() { return modern() ? sw - GUTTER : sw; }
    private int paneH() { return modern() ? h - 2 * GUTTER : h; }
    private int brandH() { return modern() ? h >= 280 ? 46 : 38 : BRAND_H; }
    private int maxSideScroll() { return Math.max(0, itemsH - (itemsBottom - itemsTop)); }
    private int itemX() { return paneX() + (modern() ? 5 : 6); }
    private int itemW() { return paneW() - (modern() ? 10 : 12); }

    public boolean inSidebar(double mx, double my) {
        return Vanilla.inside(mx, my, x, y, sw, h);
    }

    @Nullable
    public Entry entryAt(double mx, double my) {
        if (!Vanilla.inside(mx, my, itemX(), itemsTop, itemW(), itemsBottom - itemsTop)) return null;
        for (Item it : items) {
            if (it.entry() == null) continue;
            int iy = itemsTop + it.y() - sideScroll;
            if (my >= iy && my < iy + it.h()) return it.entry();
        }
        return null;
    }

    /** Wheel over the sidebar, when it is taller than the window. */
    public boolean scroll(double mx, double my, double amount) {
        if (!inSidebar(mx, my)) return false;
        sideScroll = Math.max(0, Math.min(maxSideScroll(), sideScroll - (int) Math.signum(amount) * 16));
        return true;
    }

    // ---- drawing ------------------------------------------------------------

    /**
     * The window: in the modern look the frosted sheet with the sidebar's
     * pane inset on it, the same glass as the page's own panes; in the
     * legacy one the black outline, the 90% body and the darker sidebar
     * behind a black rule. Draw it under everything on the page.
     */
    public void drawWindow(Canvas c) {
        if (modern()) {
            Glass.sheet(c, x, y, w, h, Px.radius(h, 22));
            Glass.pane(c, paneX(), paneY(), paneW(), paneH(), Px.radius(paneH(), 16), false, 0);
            return;
        }
        c.outline(x, y, w, h, 0xFF000000);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xE61E1E1E);
        c.fill(x + 1, y + 1, x + sw, y + h - 1, 0x59000000);
        c.fill(x + sw, y + 1, x + sw + 1, y + h - 1, 0xFF000000);
    }

    /**
     * The brand and the entries, {@code selected} raised; {@code count} gives
     * the module entries their counts (null for none).
     */
    public void drawSidebar(Canvas c, int mouseX, int mouseY, @Nullable Entry selected, @Nullable ToIntFunction<Entry> count) {
        drawBrand(c);
        c.scissor(paneX(), itemsTop, paneX() + paneW(), itemsBottom);
        boolean in = Vanilla.inside(mouseX, mouseY, itemX(), itemsTop, itemW(), itemsBottom - itemsTop);
        for (Item it : items) {
            int iy = itemsTop + it.y() - sideScroll;
            if (iy + it.h() < itemsTop || iy > itemsBottom) continue;
            if (it.entry() == null) {
                section(c, it.section().label, iy);
                continue;
            }
            Entry e = it.entry();
            boolean hover = in && mouseY >= iy && mouseY < iy + it.h();
            String n = count != null && e.module() ? String.valueOf(count.applyAsInt(e)) : "";
            item(c, itemX(), iy, itemW(), it.h(), e.icon, e.label, n, e == selected, hover);
        }
        c.unscissor();
        if (maxSideScroll() > 0 && modern()) {
            int vh = itemsBottom - itemsTop, bh = Math.max(12, vh * vh / itemsH);
            int by = itemsTop + (vh - bh) * sideScroll / maxSideScroll();
            Round.pill(c, paneX() + paneW() - 4, by, 2, bh, 0xFF45454C);
        }
    }

    private void drawBrand(Canvas c) {
        boolean modern = modern();
        int ms = Theme.markSize(modern ? 16 : 14), bx = paneX() + 10, by0 = paneY();
        Theme.mark(c, bx, by0 + (brandH() - ms) / 2, ms, 0xFF);
        int nx = bx + ms + (modern ? 7 : 6) - Px.pad(), nw = paneW() - (nx - paneX()) - 6;
        if (modern && !VERSION.isEmpty()) {
            // the name over the version, as one block centred on the mark
            int by = by0 + (brandH() - 24) / 2;
            Px.sans(c, "Dusk", nx, by, nw, 14, Px.Size.S20, Theme.TEXT, true, true);
            Px.sans(c, "v" + VERSION, nx, by + 14, nw, 10, Px.Size.S11, Theme.TEXT_FAINT, false, true);
        } else if (modern) {
            Px.sans(c, "Dusk", nx, by0, nw, brandH(), Px.Size.S20, Theme.TEXT, true, true);
        } else {
            Px.label(c, "Dusk", nx, by0, nw, BRAND_H, Px.Size.S20, Px.Tone.ACCENT, false, false, true, 0xFF);
        }
    }

    private void section(Canvas c, String label, int y) {
        int sx = itemX() + 4 - Px.pad();
        if (modern()) Px.sans(c, label, sx, y, itemW() - 8, SECTION_H, Px.Size.S11, Theme.TEXT_FAINT, true, true);
        else Px.label(c, label, sx, y, itemW() - 8, SECTION_H, Px.Size.S11, Px.Tone.DIM, false, false, true, 0xFF);
    }

    private static void item(Canvas c, int x, int y, int w, int ih, Icons icon, String label, String count,
                             boolean selected, boolean hover) {
        int iy = y + (ih - icon.height()) / 2;
        if (Theme.modern()) {
            // the page shown on a brighter wash of the pane's glass, a faint one under the pointer
            if (selected) Round.fill(c, x, y + 1, w, ih - 2, 64, Glass.PANE_SEL);
            else if (hover) Round.fill(c, x, y + 1, w, ih - 2, 64, Theme.HOVER);
            int col = selected || hover ? Theme.TEXT : Theme.TEXT_MUTED;
            icon.draw(c, x + 8 + (7 - icon.width()) / 2, iy, selected || hover ? Theme.TEXT : Theme.GLYPH);
            int cw = count.isEmpty() ? 0 : Math.round(Px.sansWidth(c, count, Px.Size.S11, false)) + 4;
            int tx = x + 21 - Px.pad();
            Px.sans(c, label, tx, y, w - 21 - cw - 8 + 2 * Px.pad(), ih, Px.Size.S16, col, selected, true);
            if (!count.isEmpty()) {
                int cx = x + w - 9 - cw + 4;
                Px.sans(c, count, cx - Px.pad(), y, cw - 4 + 2 * Px.pad(), ih, Px.Size.S11,
                        selected ? Theme.TEXT_MUTED : Theme.TEXT_FAINT, false, true);
            }
            return;
        }
        int countW = count.isEmpty() ? 0 : c.textWidth(count) + 6;
        if (selected || hover) Theme.cell(c, x, y, w, ih, hover);
        icon.draw(c, x + 6 + (7 - icon.width()) / 2, iy, Theme.filter(selected ? Theme.ACTIVE_UP : Theme.GLYPH, hover, false));
        Px.label(c, label, x + 18 - Px.pad(), y, w - 18 - countW + Px.pad(), ih, Px.Size.S14,
                selected ? Px.Tone.ACTIVE : Px.Tone.GREY, hover, false, true, 0xFF);
        if (!count.isEmpty()) c.text(count, x + w - 6 - c.textWidth(count), y + (ih - 7) / 2, Theme.DIM, false);
    }

    // ---- navigation ----------------------------------------------------------

    /**
     * Goes from the page {@code from} (showing {@code current}) to {@code to}.
     * Every page opens over the module window, so Esc anywhere comes back to
     * it and once more leaves the menu; it is made when the menu was entered
     * some other way (Friends from the title screen).
     */
    public static void go(MenuScreen from, @Nullable Entry current, Entry to) {
        Minecraft mc = Minecraft.getInstance();
        if (to.world && mc.level == null) return;
        DuskSettingsScreen hub = null;
        Screen root = from;
        while (root instanceof MenuScreen m) {
            if (hub == null && m instanceof DuskSettingsScreen d) hub = d;
            root = m.parent;
        }
        // Keybinds opens on the drawn keyboard; its LIST tool comes back here from it
        if (to == Entry.KEYBINDS && !(from instanceof KeyboardScreen)) {
            if (to == current && from != hub) return;
            from.beforeClose();
            Compat.setScreen(mc, new KeyboardScreen(hub != null ? hub : new DuskSettingsScreen(root)));
            return;
        }
        // the module window takes its own entries again (a category back from a module's settings)
        if (from == hub && to.onHub()) {
            hub.show(to);
            return;
        }
        if (to == current) return;
        from.beforeClose();
        if (hub == null) hub = new DuskSettingsScreen(root);
        if (to.onHub()) {
            hub.show(to);
            Compat.setScreen(mc, hub);
            return;
        }
        Compat.setScreen(mc, switch (to) {
            case STYLES -> new StylesScreen(hub);
            case PACKS -> new PackBrowserScreen(hub, PackBrowserScreen.RESOURCE_PACKS);
            case WARDROBE -> new WardrobeScreen(hub);
            case LAYOUT -> new HudEditorScreen(hub);
            case FRIENDS -> new SocialScreen(hub);
            case QUESTS -> new QuestsScreen(hub);
            case MEDIA -> new MediaScreen(hub, MediaScreen.SCREENSHOTS);
            case WAYPOINTS -> new WaypointsScreen(hub);
            case STATS -> new DuskStatsScreen(hub);
            case PERFORMANCE -> new PerformanceScreen(hub);
            default -> hub;
        });
    }
}
