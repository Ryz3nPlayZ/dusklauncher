package dev.dusk.client.gui;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.MobFactory;
import dev.dusk.client.gui.widget.TextFieldWidget;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Statistics screen, in the spirit of BetterStats (TheCSDev; all rights
 * reserved, so written from how it behaves, not its code): the general
 * counters as a list, and items and mobs as grids, with a search box, a sort,
 * one stat at a time or all of them, grouping by mod or kind, and the
 * never-touched entries hidden. Mobs are drawn as their models; hovering any
 * entry lists every number it has.
 */
public class DuskStatsScreen extends PanelScreen {
    private static final String[] TABS = {"GENERAL", "ITEMS", "MOBS"};
    private static final int GENERAL = 0, ITEMS = 1, MOBS = 2;
    private static final int BAR_H = 16, HEAD_H = 16, ROW_H = 12, GAP = 2;
    private static final int SLOT_W = 24, SLOT_H = 22, SLOT_H_COUNT = 31, MOB_W = 62, MOB_H = 76, MODEL_H = 50;
    private static final int[][] STAT_LABELS_BY_TAB = {{}, {0, 1, 2, 3, 4, 5}, {0, 1}};
    private static final String[] ITEM_STATS = {"Mined", "Crafted", "Used", "Broken", "Picked up", "Dropped"};
    private static final String[] MOB_STATS = {"Killed", "Killed you"};
    private static final String[] MOB_KINDS = {"Hostile", "Animals", "Water", "Ambient", "Other"};
    private static final String[] GROUPS = {"OFF", "MOD", "TYPE"};
    private static final Set<String> BOSSES = Set.of("minecraft:ender_dragon", "minecraft:wither");
    private static final long REFRESH_MS = 1000, REQUEST_MS = 10_000;

    /** One thing with numbers; {@code values} and {@code shown} refresh in place. */
    private static final class Entry {
        final String name, id, mod, kind, match;
        @Nullable final ItemStack icon;
        @Nullable final EntityType<?> type;
        @Nullable final Block block;
        @Nullable final Stat<?> stat;
        final int[] values;
        String shown = "";

        Entry(String name, String id, String kind, @Nullable ItemStack icon, @Nullable EntityType<?> type,
              @Nullable Block block, @Nullable Stat<?> stat, int n) {
            this.name = name;
            this.id = id;
            this.mod = modName(id);
            this.kind = kind;
            this.match = (name + " " + id).toLowerCase(Locale.ROOT);
            this.icon = icon;
            this.type = type;
            this.block = block;
            this.stat = stat;
            this.values = new int[n];
        }

        long total() {
            long t = 0;
            for (int v : values) t += v;
            return t;
        }
    }

    /** A laid-out piece of the list: a group header ({@code head}) or an entry. */
    private record Cell(int x, int y, int w, int h, @Nullable Entry entry, @Nullable String head) {}

    private record Button(String id, String label, boolean changed, int x, int w) {}

    // the choices stay for the session, like the tab
    private static int tab = ITEMS, group;
    private static boolean az, showEmpty;
    private static final int[] stat = {-1, -1, -1};
    private static final Map<String, String> MOD_NAMES = new HashMap<>();
    @Nullable private static Map<String, Item> eggs;

    private final TextFieldWidget search;
    private String query = "";
    @SuppressWarnings("unchecked")
    private final List<Entry>[] entries = new List[3];
    private final Map<EntityType<?>, Entity> models = new HashMap<>();
    private final Set<EntityType<?>> unmodelled = new HashSet<>();
    private List<Cell> cells = List.of();
    private List<Button> buttons = List.of();
    private int height, shownCount, barY, cellsW = -1;
    private long refreshedAt, requestedAt;
    private boolean dirty = true;

    public DuskStatsScreen(@Nullable Screen parent) {
        super(Component.literal("Statistics"), parent);
        this.search = new TextFieldWidget(() -> "", q -> {
            query = q.trim().toLowerCase(Locale.ROOT);
            scroll = 0;
            dirty = true;
        }, true, 40).bare().placeholder("Search stats...");
    }

    public static void show(@Nullable Screen parent) {
        Compat.setScreen(Minecraft.getInstance(), new DuskStatsScreen(parent));
    }

    /** The screen vanilla's StatsScreen goes back to (its only Screen field). */
    @Nullable
    public static Screen parentOf(Screen stats) {
        for (Class<?> k = stats.getClass(); k != null && k != Screen.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Screen.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    return (Screen) f.get(stats);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---- data ---------------------------------------------------------------

    private static String modName(String id) {
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        return MOD_NAMES.computeIfAbsent(ns, n -> FabricLoader.getInstance().getModContainer(n)
                .map(m -> m.getMetadata().getName()).orElse(n));
    }

    private static <T> int value(StatsCounter st, StatType<T> type, T v) {
        // contains first: get() would add the stat to the shared map, which the
        // integrated server also writes
        return type.contains(v) ? st.getValue(type.get(v)) : 0;
    }

    private static Map<String, Item> eggs() {
        if (eggs == null) {
            Map<String, Item> m = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                var key = BuiltInRegistries.ITEM.getKey(item);
                String path = key.getPath();
                if (path.endsWith("_spawn_egg")) {
                    m.put(key.getNamespace() + ":" + path.substring(0, path.length() - "_spawn_egg".length()), item);
                }
            }
            eggs = m;
        }
        return eggs;
    }

    private static String generalKind(String id) {
        if (id.endsWith("_one_cm")) return "Distance";
        if (id.contains("time") || id.contains("since")) return "Time";
        if (id.contains("damage")) return "Damage";
        if (id.contains("interact_with") || id.contains("open_") || id.contains("inspect_")) return "Interactions";
        return "Other";
    }

    private static String mobKind(EntityType<?> type) {
        return switch (type.getCategory().name()) {
            case "MONSTER" -> "Hostile";
            case "CREATURE" -> "Animals";
            case "WATER_CREATURE", "WATER_AMBIENT", "UNDERGROUND_WATER_CREATURE", "AXOLOTLS" -> "Water";
            case "AMBIENT" -> "Ambient";
            default -> "Other";
        };
    }

    private List<Entry> base(int t, StatsCounter st) {
        if (entries[t] != null) return entries[t];
        List<Entry> out = new ArrayList<>();
        if (t == GENERAL) {
            for (Stat<?> s : Stats.CUSTOM) {
                String id = s.getValue().toString();
                String name = Component.translatable("stat." + id.replace(':', '.')).getString();
                out.add(new Entry(name, id, generalKind(id), null, null, null, s, 1));
            }
        } else if (t == ITEMS) {
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                ItemStack stack = new ItemStack(item);
                Block block = Block.byItem(item);
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                out.add(new Entry(stack.getHoverName().getString(), id, block == Blocks.AIR ? "Items" : "Blocks",
                        stack, null, block == Blocks.AIR ? null : block, null, ITEM_STATS.length));
            }
        } else {
            Map<String, Item> eggs = eggs();
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
                Item egg = eggs.get(id);
                boolean listed = egg != null || BOSSES.contains(id)
                        || value(st, Stats.ENTITY_KILLED, type) > 0 || value(st, Stats.ENTITY_KILLED_BY, type) > 0;
                if (!listed) continue;
                out.add(new Entry(type.getDescription().getString(), id, mobKind(type),
                        egg == null ? null : new ItemStack(egg), type, null, null, MOB_STATS.length));
            }
        }
        entries[t] = out;
        return out;
    }

    private void refresh() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (now - requestedAt > REQUEST_MS) {
            requestedAt = now;
            ClientPacketListener conn = mc.getConnection();
            if (conn != null) conn.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        }
        if (!dirty && now - refreshedAt < REFRESH_MS) return;
        refreshedAt = now;
        dirty = true;
        if (mc.player == null) return;
        StatsCounter st = mc.player.getStats();
        for (Entry e : base(tab, st)) {
            if (e.stat != null) {
                e.values[0] = st.getValue(e.stat);
                e.shown = e.stat.format(e.values[0]);
            } else if (e.type != null) {
                e.values[0] = value(st, Stats.ENTITY_KILLED, e.type);
                e.values[1] = value(st, Stats.ENTITY_KILLED_BY, e.type);
            } else if (e.icon != null) {
                Item item = e.icon.getItem();
                e.values[0] = e.block != null ? value(st, Stats.BLOCK_MINED, e.block) : 0;
                e.values[1] = value(st, Stats.ITEM_CRAFTED, item);
                e.values[2] = value(st, Stats.ITEM_USED, item);
                e.values[3] = value(st, Stats.ITEM_BROKEN, item);
                e.values[4] = value(st, Stats.ITEM_PICKED_UP, item);
                e.values[5] = value(st, Stats.ITEM_DROPPED, item);
            }
        }
    }

    /** The number the grid sorts and filters by. */
    private static long key(Entry e) {
        int s = stat[tab];
        return s >= 0 && s < e.values.length ? e.values[s] : e.total();
    }

    @Nullable
    private Entity model(EntityType<?> type) {
        if (unmodelled.contains(type)) return null;
        Entity e = models.get(type);
        if (e != null) return e;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        try {
            e = MobFactory.create(type, mc.level);
        } catch (RuntimeException ex) {
            e = null;
        }
        if (!(e instanceof LivingEntity)) {
            unmodelled.add(type);
            return null;
        }
        models.put(type, e);
        return e;
    }

    // ---- layout -------------------------------------------------------------

    @Override
    protected String[] tabs() {
        return TABS;
    }

    @Override
    protected int activeTab() {
        return tab;
    }

    @Override
    protected void selectTab(int i) {
        tab = i;
        scroll = 0;
        dirty = true;
        refreshedAt = 0;
    }

    @Override
    @Nullable
    protected TextFieldWidget search() {
        return search;
    }

    @Override
    protected int contentHeight() {
        return height;
    }

    private void layout() {
        layoutPanel();
        barY = bodyY() + pad;
        listX = px + pad;
        listY = barY + BAR_H + 6;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = py + ph - pad;
        refresh();
        if (dirty || cellsW != listW) build();
        layoutButtons();
        clampScroll();
    }

    private void build() {
        dirty = false;
        cellsW = listW;
        List<Entry> base = entries[tab];
        List<Cell> out = new ArrayList<>();
        if (base == null) {
            cells = out;
            height = 0;
            shownCount = 0;
            return;
        }
        List<Entry> shown = new ArrayList<>();
        for (Entry e : base) {
            if (!query.isEmpty() && !e.match.contains(query)) continue;
            if (!showEmpty && key(e) == 0) continue;
            shown.add(e);
        }
        Comparator<Entry> byName = Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT));
        shown.sort(az ? byName : Comparator.comparingLong(DuskStatsScreen::key).reversed().thenComparing(byName));
        shownCount = shown.size();

        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        if (group == 0) {
            groups.put("", shown);
        } else {
            List<String> order = new ArrayList<>();
            for (Entry e : shown) {
                String g = group == 1 ? e.mod : e.kind;
                if (!groups.containsKey(g)) order.add(g);
                groups.computeIfAbsent(g, k -> new ArrayList<>()).add(e);
            }
            order.sort(Comparator.comparingInt((String g) -> groupRank(g)).thenComparing(g -> g));
            Map<String, List<Entry>> sorted = new LinkedHashMap<>();
            for (String g : order) sorted.put(g, groups.get(g));
            groups = sorted;
        }

        int y = 0;
        for (Map.Entry<String, List<Entry>> g : groups.entrySet()) {
            if (group != 0) {
                out.add(new Cell(listX, y, listW, HEAD_H, null,
                        g.getKey().toUpperCase(Locale.ROOT) + "  ·  " + g.getValue().size()));
                y += HEAD_H;
            }
            y = grid(out, g.getValue(), y) + 4;
        }
        cells = out;
        height = Math.max(0, y - 4);
    }

    private int groupRank(String g) {
        if (group == 1) return g.equals("Minecraft") ? 0 : 1;
        if (tab == MOBS) {
            for (int i = 0; i < MOB_KINDS.length; i++) if (MOB_KINDS[i].equals(g)) return i;
        }
        return g.equals("Other") ? 9 : 0;
    }

    private int grid(List<Cell> out, List<Entry> list, int y) {
        if (tab == GENERAL) {
            for (Entry e : list) {
                out.add(new Cell(listX, y, listW, ROW_H, e, null));
                y += ROW_H;
            }
            return y;
        }
        int w = tab == ITEMS ? SLOT_W : MOB_W, h = tab == MOBS ? MOB_H : stat[tab] >= 0 ? SLOT_H_COUNT : SLOT_H;
        int cols = Math.max(1, (listW + GAP) / (w + GAP));
        for (int i = 0; i < list.size(); i++) {
            int col = i % cols, row = i / cols;
            out.add(new Cell(listX + col * (w + GAP), y + row * (h + GAP), w, h, list.get(i), null));
        }
        int rows = (list.size() + cols - 1) / cols;
        return y + rows * (h + GAP) - (rows > 0 ? GAP : 0);
    }

    private void layoutButtons() {
        List<Button> out = new ArrayList<>();
        int x = listX;
        x = button(out, "sort", az ? "SORT: A-Z" : "SORT: MOST", az, x);
        if (tab != GENERAL) {
            int s = stat[tab];
            String[] names = tab == ITEMS ? ITEM_STATS : MOB_STATS;
            x = button(out, "stat", "SHOW: " + (s < 0 ? "ALL" : names[s].toUpperCase(Locale.ROOT)), s >= 0, x);
        }
        x = button(out, "group", "GROUP: " + GROUPS[group], group != 0, x);
        button(out, "empty", showEmpty ? "EMPTY: SHOWN" : "EMPTY: HIDDEN", showEmpty, x);
        buttons = out;
    }

    private int button(List<Button> out, String id, String label, boolean changed, int x) {
        int w = this.font.width(label) + 14;
        out.add(new Button(id, label, changed, x, w));
        return x + w + 3;
    }

    private void cycle(String id, int dir) {
        switch (id) {
            case "sort" -> az = !az;
            case "stat" -> {
                int n = STAT_LABELS_BY_TAB[tab].length + 1;
                stat[tab] = Math.floorMod(stat[tab] + 1 + dir, n) - 1;
            }
            case "group" -> group = Math.floorMod(group + dir, GROUPS.length);
            case "empty" -> showEmpty = !showEmpty;
            default -> {}
        }
        scroll = 0;
        dirty = true;
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        drawPanel(c, mouseX, mouseY);
        for (Button b : buttons) {
            boxButton(c, b.label, b.x, barY, b.w, BAR_H, mouseX, mouseY, Theme.Family.GREY, b.changed, true);
        }
        String count = shownCount + (tab == GENERAL ? " stats" : tab == ITEMS ? " items" : " mobs");
        int cx = listX + listW - c.textWidth(count);
        Button last = buttons.isEmpty() ? null : buttons.get(buttons.size() - 1);
        if (last == null || cx > last.x + last.w + 6) c.text(count, cx, barY + 4, Theme.TEXT_FAINT, false);

        if (cells.isEmpty()) {
            String msg = Minecraft.getInstance().player == null ? "Join a world to see statistics."
                    : !query.isEmpty() ? "Nothing matches \"" + query + "\"."
                    : showEmpty ? "No statistics yet." : "Nothing here yet. EMPTY: SHOWN lists everything.";
            c.text(Theme.ellipsize(c, msg, listW - 8), listX + 4, listY + 4, Theme.TEXT_MUTED, false);
            return;
        }
        boolean inside = inList(mouseX, mouseY);
        int mx = inside ? mouseX : -1, my = inside ? mouseY : -1;
        Entry hover = null;
        c.scissor(listX, listY, listX + listW, listBottom);
        int row = 0;
        for (Cell cell : cells) {
            int y = listY + cell.y - scroll;
            if (cell.entry != null && tab == GENERAL) row++;
            if (y + cell.h < listY || y > listBottom) continue;
            if (cell.head != null) {
                c.text(Theme.ellipsize(c, cell.head, listW - 4), listX + 2, y + 5, Theme.TEXT_FAINT, false);
                continue;
            }
            boolean hot = Vanilla.inside(mx, my, cell.x, y, cell.w, cell.h);
            if (hot) hover = cell.entry;
            switch (tab) {
                case GENERAL -> drawRow(c, cell.entry, y, hot, row);
                case ITEMS -> drawItem(c, cell, y, hot);
                default -> drawMob(c, cell, y, hot, mouseX, mouseY);
            }
        }
        c.unscissor();
        drawScrollbar(c);
        if (hover != null && tab != GENERAL) {
            c.beginLayer();
            tooltip(c, lines(hover), mouseX, mouseY);
            c.endLayer();
        }
    }

    private void drawRow(Canvas c, Entry e, int y, boolean hot, int row) {
        if (hot) c.fill(listX, y, listX + listW, y + ROW_H, 0xFF333333);
        else if (row % 2 == 0) c.fill(listX, y, listX + listW, y + ROW_H, 0xFF1A1A1A);
        int vw = c.textWidth(e.shown);
        c.text(Theme.ellipsize(c, e.name, listW - vw - 16), listX + 4, y + 2, e.values[0] > 0 ? Theme.TEXT : Theme.TEXT_FAINT, false);
        c.text(e.shown, listX + listW - 4 - vw, y + 2, e.values[0] > 0 ? Theme.ACCENT : Theme.TEXT_FAINT, false);
    }

    private void drawItem(Canvas c, Cell cell, int y, boolean hot) {
        Entry e = cell.entry;
        c.fill(cell.x, y, cell.x + cell.w, y + cell.h, hot ? 0xFF3C3C3C : e.total() > 0 ? 0xFF262626 : 0xFF1A1A1A);
        if (e.icon != null) c.item(e.icon, cell.x + (cell.w - 16) / 2, y + 3);
        int s = stat[tab];
        if (s >= 0) {
            String n = compact(e.values[s]);
            c.text(n, cell.x + (cell.w - c.textWidth(n)) / 2 + 1, y + 21, e.values[s] > 0 ? Theme.TEXT : Theme.TEXT_FAINT, false);
        }
    }

    private void drawMob(Canvas c, Cell cell, int y, boolean hot, int mouseX, int mouseY) {
        Entry e = cell.entry;
        int x = cell.x;
        c.fill(x, y, x + cell.w, y + cell.h, hot ? 0xFF3C3C3C : e.total() > 0 ? 0xFF262626 : 0xFF1A1A1A);
        Entity model = e.type == null ? null : model(e.type);
        if (model instanceof LivingEntity living) {
            float bw = Math.max(0.3f, living.getBbWidth()), bh = Math.max(0.3f, living.getBbHeight());
            int size = (int) Math.max(3, Math.min(30, Math.min((MODEL_H - 10) / bh, (cell.w - 14) / bw)));
            try {
                c.entity(living, x + 2, y + 2, x + cell.w - 2, y + MODEL_H, size, mouseX, mouseY);
            } catch (RuntimeException ex) {
                // a mob that won't draw outside a world falls back to its egg
                models.remove(e.type);
                unmodelled.add(e.type);
            }
        } else if (e.icon != null) {
            c.item(e.icon, x + (cell.w - 16) / 2, y + (MODEL_H - 16) / 2);
        }
        String name = Theme.ellipsize(c, e.name, cell.w - 4);
        c.text(name, x + (cell.w - c.textWidth(name)) / 2, y + MODEL_H + 2, Theme.TEXT, false);
        int s = stat[tab];
        if (s >= 0) {
            String n = compact(e.values[s]) + (s == 0 ? " kills" : " deaths");
            c.text(n, x + (cell.w - c.textWidth(n)) / 2, y + MODEL_H + 13,
                    e.values[s] == 0 ? Theme.TEXT_FAINT : s == 0 ? Theme.ACCENT : DEATHS, false);
        } else {
            String k = compact(e.values[0]), d = compact(e.values[1]), sep = " / ";
            int w = c.textWidth(k + sep + d), tx = x + (cell.w - w) / 2, ty = y + MODEL_H + 13;
            c.text(k, tx, ty, e.values[0] > 0 ? Theme.ACCENT : Theme.TEXT_FAINT, false);
            c.text(sep, tx + c.textWidth(k), ty, Theme.TEXT_FAINT, false);
            c.text(d, tx + c.textWidth(k + sep), ty, e.values[1] > 0 ? DEATHS : Theme.TEXT_FAINT, false);
        }
    }

    private static final int DEATHS = 0xFFFF6B5E;

    private List<String> lines(Entry e) {
        List<String> out = new ArrayList<>();
        out.add(e.name);
        String[] names = tab == ITEMS ? ITEM_STATS : MOB_STATS;
        boolean any = false;
        for (int i = 0; i < names.length; i++) {
            if (tab == ITEMS && i == 0 && e.block == null) continue;
            if (e.values[i] == 0 && !showEmpty) continue;
            out.add(names[i] + ": " + String.format(Locale.ROOT, "%,d", e.values[i]));
            any = true;
        }
        if (!any) out.add("Nothing yet");
        out.add(e.mod.equals("Minecraft") ? e.id : e.id + "  (" + e.mod + ")");
        return out;
    }

    /** A box like vanilla's tooltip; the first line bright, the last faint. */
    private void tooltip(Canvas c, List<String> lines, int mx, int my) {
        int w = 0;
        for (String l : lines) w = Math.max(w, c.textWidth(l));
        w += 8;
        int h = lines.size() * 10 + 4;
        int x = Math.min(mx + 10, this.width - w - 2), y = Math.max(2, Math.min(my - 14, this.height - h - 2));
        c.fill(x, y, x + w, y + h, 0xF0100010);
        c.outline(x, y, w, h, 0x505000FF);
        for (int i = 0; i < lines.size(); i++) {
            int col = i == 0 ? Theme.TEXT : i == lines.size() - 1 ? Theme.TEXT_FAINT : Theme.TEXT_MUTED;
            c.text(lines.get(i), x + 4, y + 3 + i * 10, col, true);
        }
    }

    /** 1234 as "1.2k": fits under an item slot. */
    static String compact(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 10_000) return n / 100 / 10.0 + "k";
        if (n < 1_000_000) return n / 1_000 + "k";
        if (n < 10_000_000) return n / 100_000 / 10.0 + "M";
        return n / 1_000_000 + "M";
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (clickChrome(mx, my, button)) return true;
        for (Button b : buttons) {
            if (Vanilla.inside(mx, my, b.x, barY, b.w, BAR_H)) {
                cycle(b.id, button == 1 ? -1 : 1);
                return true;
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    public void removed() {
        super.removed();
        models.clear();
    }
}
