package dev.dusk.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.modules.misc.Waypoints;
import dev.dusk.client.waypoints.WaypointStore;
import dev.dusk.client.waypoints.WaypointStore.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * This world's waypoints: rename (click the name), move (click the
 * coordinates), recolour (click the swatch), hide or delete them, and add one
 * where you stand. The IMPORT tab copies another world's or server's
 * waypoints into this one.
 */
public class WaypointsScreen extends PanelScreen {
    private static final String[] TABS = {"WAYPOINTS", "IMPORT"};
    private static final int ROW_H = 30, ROW_GAP = 4, BTN_W = 52, BTN_H = 16, SWATCH = 12;

    private final TextFieldWidget search;
    private String query = "";
    @Nullable private Waypoint editing, confirmDelete;
    // the open text field: a name, or (editingCoords) "x y z"
    @Nullable private TextFieldWidget nameField;
    private boolean editingCoords;
    private List<Waypoint> shown = List.of();
    private int tab;
    private List<String> worlds = List.of();
    private final java.util.Map<String, Integer> imported = new java.util.HashMap<>();

    public WaypointsScreen(@Nullable Screen parent) {
        super(Component.literal("Waypoints"), parent);
        this.search = new TextFieldWidget(() -> "", q -> {
            query = q.trim().toLowerCase(Locale.ROOT);
            scroll = 0;
        }, true, 40).bare().placeholder("Search waypoints...");
    }

    // ---- data ---------------------------------------------------------------

    private List<Waypoint> filtered() {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : WaypointStore.current()) {
            if (query.isEmpty() || w.name.toLowerCase(Locale.ROOT).contains(query)) out.add(w);
        }
        return out;
    }

    private static String dimName(String dim) {
        return switch (dim) {
            case "overworld" -> "Overworld";
            case "the_nether" -> "Nether";
            case "the_end" -> "End";
            default -> dim;
        };
    }

    @Nullable
    private static String distanceTo(Waypoint w) {
        Waypoints m = Waypoints.active();
        var player = Minecraft.getInstance().player;
        if (m == null || player == null) return null;
        double[] pos = m.positionIn(w, Waypoints.dimension());
        if (pos == null) return null;
        return Waypoints.formatDistance(Math.sqrt(player.distanceToSqr(pos[0] + 0.5, pos[1], pos[2] + 0.5)));
    }

    private void commitName() {
        if (nameField != null) nameField.setFocused(false);
        nameField = null;
        editing = null;
        editingCoords = false;
    }

    private void startCoords(Waypoint w) {
        commitName();
        editing = w;
        editingCoords = true;
        nameField = new TextFieldWidget(() -> w.x + " " + w.y + " " + w.z, v -> {
            String[] parts = v.trim().split("[\\s,]+");
            if (parts.length != 3) return;
            try {
                int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
                if (x != w.x || y != w.y || z != w.z) {
                    w.x = x;
                    w.y = y;
                    w.z = z;
                    WaypointStore.save();
                }
            } catch (NumberFormatException ignored) {
                // not three whole numbers: keep the old position
            }
        }, false, 40).themed();
        nameField.setFocused(true);
    }

    private void startRename(Waypoint w) {
        commitName();
        editing = w;
        nameField = new TextFieldWidget(() -> w.name, v -> {
            String name = v.trim();
            if (!name.isEmpty() && !name.equals(w.name)) {
                w.name = name;
                WaypointStore.save();
            }
        }, false, 32).themed();
        nameField.setFocused(true);
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
        commitName();
        confirmDelete = null;
        tab = i;
        scroll = 0;
    }

    @Override
    protected List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("close", Icons.CLOSE, "", "Close"));
        if (tab == 0 && Minecraft.getInstance().player != null) t.add(new Tool("add", null, "ADD HERE", "Add a waypoint where you stand"));
        return t;
    }

    @Override
    protected void onTool(String id) {
        var player = Minecraft.getInstance().player;
        if (id.equals("add") && player != null) {
            startRename(Waypoints.add(player, null));
            scroll = Integer.MAX_VALUE;
        } else {
            super.onTool(id);
        }
    }

    @Override
    protected TextFieldWidget search() {
        return search;
    }

    private void layout() {
        layoutPanel();
        listX = px + pad;
        listY = bodyY() + pad;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = py + ph - pad;
        shown = filtered();
        worlds = new ArrayList<>();
        for (String k : WaypointStore.otherWorlds().keySet()) {
            if (query.isEmpty() || k.toLowerCase(Locale.ROOT).contains(query)) worlds.add(k);
        }
        clampScroll();
    }

    @Override
    protected int contentHeight() {
        int n = Math.max(1, tab == 1 ? worlds.size() : shown.size());
        return n * (ROW_H + ROW_GAP) - ROW_GAP;
    }

    private int rowY(int i) {
        return listY + i * (ROW_H + ROW_GAP) - scroll;
    }

    private int deleteX() { return listX + listW - BTN_W - 6; }

    private int hideX() { return deleteX() - BTN_W - 4; }

    private int nameX() { return listX + 6 + SWATCH + 8; }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        drawPanel(c, mouseX, mouseY);
        boolean hotList = inList(mouseX, mouseY);
        int mx = hotList ? mouseX : -1, my = hotList ? mouseY : -1;
        c.scissor(listX, listY, listX + listW, listBottom);
        if (tab == 1) {
            drawImports(c, mx, my);
            c.unscissor();
            drawScrollbar(c);
            return;
        }
        if (shown.isEmpty()) {
            String msg = !query.isEmpty() ? "No waypoint matches \"" + query + "\"."
                    : Minecraft.getInstance().level == null ? "Join a world to see its waypoints."
                    : "No waypoints in this world yet. Press ADD HERE, or the Add Waypoint key in game.";
            c.text(Theme.ellipsize(c, msg, listW - 8), listX + 4, listY + (ROW_H - 8) / 2, Theme.TEXT_MUTED, false);
        }
        for (int i = 0; i < shown.size(); i++) {
            int y = rowY(i);
            if (y + ROW_H < listY || y > listBottom) continue;
            drawRow(c, shown.get(i), y, mx, my);
        }
        c.unscissor();
        drawScrollbar(c);
    }

    private void drawRow(Canvas c, Waypoint w, int y, int mx, int my) {
        Theme.plate(c, listX, y, listW, ROW_H, Theme.SURFACE, Theme.SURFACE, false);
        int sx = listX + 6, sy = y + (ROW_H - SWATCH) / 2;
        boolean swatchHot = Vanilla.inside(mx, my, sx, sy, SWATCH, SWATCH);
        c.fill(sx - 1, sy - 1, sx + SWATCH + 1, sy + SWATCH + 1, swatchHot ? 0xFFFFFFFF : 0xFF000000);
        c.fill(sx, sy, sx + SWATCH, sy + SWATCH, w.color | 0xFF000000);

        int tx = nameX(), tw = hideX() - 8 - tx;
        if (w == editing && nameField != null && !editingCoords) {
            nameField.setBounds(tx - 2, y + 2, Math.min(tw, 180), 14);
            nameField.render(c, mx, my);
        } else {
            boolean nameHot = Vanilla.inside(mx, my, tx, y + 3, tw, 11);
            c.text(Theme.ellipsize(c, w.name, tw), tx, y + 5, w.visible ? (nameHot ? Theme.ACCENT : 0xFFFFFFFF) : Theme.TEXT_FAINT, false);
        }
        if (w == editing && nameField != null && editingCoords) {
            nameField.setBounds(tx - 2, y + 15, Math.min(tw, 140), 14);
            nameField.render(c, mx, my);
        } else {
            String dist = distanceTo(w);
            String info = w.x + ", " + w.y + ", " + w.z + "  ·  " + dimName(w.dim) + (dist != null ? "  ·  " + dist : "");
            boolean infoHot = Vanilla.inside(mx, my, tx, y + 16, tw, 11);
            c.text(Theme.ellipsize(c, info, tw), tx, y + 18, infoHot ? Theme.ACCENT : Theme.TEXT_MUTED, false);
        }

        int by = y + (ROW_H - BTN_H) / 2;
        flatButton(c, w.visible ? "SHOWN" : "HIDDEN", hideX(), by, BTN_W, BTN_H, mx, my, w.visible, true);
        boolean sure = w == confirmDelete;
        boxButton(c, sure ? "SURE?" : "DELETE", deleteX(), by, BTN_W, BTN_H, mx, my,
                sure ? Theme.Family.ACCENT : Theme.Family.GREY, false, true);
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (nameField != null && nameField.contains(mx, my)) return nameField.click(mx, my, button);
        commitName();
        if (clickChrome(mx, my, button)) return true;
        Waypoint wasConfirming = confirmDelete;
        confirmDelete = null;
        if (!inList(mx, my)) return Vanilla.inside(mx, my, px, py, pw, ph);
        if (tab == 1) return clickImport(mx, my);
        for (int i = 0; i < shown.size(); i++) {
            Waypoint w = shown.get(i);
            int y = rowY(i), by = y + (ROW_H - BTN_H) / 2;
            if (my < y || my >= y + ROW_H) continue;
            int sx = listX + 6, sy = y + (ROW_H - SWATCH) / 2;
            if (Vanilla.inside(mx, my, sx - 2, sy - 2, SWATCH + 4, SWATCH + 4)) {
                w.color = nextColor(w.color, button == 1 ? -1 : 1);
                WaypointStore.save();
            } else if (Vanilla.inside(mx, my, hideX(), by, BTN_W, BTN_H)) {
                w.visible = !w.visible;
                WaypointStore.save();
            } else if (Vanilla.inside(mx, my, deleteX(), by, BTN_W, BTN_H)) {
                if (wasConfirming == w) {
                    WaypointStore.current().remove(w);
                    WaypointStore.save();
                } else {
                    confirmDelete = w;
                }
            } else if (mx >= nameX() && mx < hideX() - 8 && my < y + 15) {
                startRename(w);
            } else if (mx >= nameX() && mx < hideX() - 8) {
                startCoords(w);
            }
            return true;
        }
        return true;
    }

    // ---- import tab ---------------------------------------------------------

    private void drawImports(Canvas c, int mx, int my) {
        if (worlds.isEmpty()) {
            String msg = !query.isEmpty() ? "No world matches \"" + query + "\"."
                    : "No other world or server has waypoints to import.";
            c.text(Theme.ellipsize(c, msg, listW - 8), listX + 4, listY + (ROW_H - 8) / 2, Theme.TEXT_MUTED, false);
            return;
        }
        var counts = WaypointStore.otherWorlds();
        boolean inWorld = WaypointStore.worldKey() != null;
        for (int i = 0; i < worlds.size(); i++) {
            int y = rowY(i);
            if (y + ROW_H < listY || y > listBottom) continue;
            String key = worlds.get(i);
            Theme.plate(c, listX, y, listW, ROW_H, Theme.SURFACE, Theme.SURFACE, false);
            int tx = listX + 8, tw = deleteX() - 8 - tx;
            c.text(Theme.ellipsize(c, WaypointStore.describe(key), tw), tx, y + 5, 0xFFFFFFFF, false);
            int n = counts.getOrDefault(key, 0);
            Integer done = imported.get(key);
            String info = n + " waypoint" + (n == 1 ? "" : "s") + (done != null ? "  ·  imported " + done : "");
            c.text(Theme.ellipsize(c, info, tw), tx, y + 18, Theme.TEXT_MUTED, false);
            int by = y + (ROW_H - BTN_H) / 2;
            if (inWorld) boxButton(c, "IMPORT", deleteX(), by, BTN_W, BTN_H, mx, my, Theme.Family.ACCENT, false, true);
        }
    }

    private boolean clickImport(double mx, double my) {
        if (WaypointStore.worldKey() == null) return true;
        for (int i = 0; i < worlds.size(); i++) {
            int y = rowY(i), by = y + (ROW_H - BTN_H) / 2;
            if (Vanilla.inside(mx, my, deleteX(), by, BTN_W, BTN_H)) {
                String key = worlds.get(i);
                imported.put(key, WaypointStore.importFrom(key));
                return true;
            }
        }
        return true;
    }

    private static int nextColor(int color, int step) {
        int[] p = Waypoints.PALETTE;
        int at = 0;
        for (int i = 0; i < p.length; i++) if (p[i] == (color | 0xFF000000)) at = i;
        return p[Math.floorMod(at + step, p.length)];
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if (nameField != null && nameField.focused()) {
            boolean handled = nameField.keyPressed(key, modifiers);
            if (key == InputConstants.KEY_ESCAPE || !nameField.focused()) {
                nameField = null;
                editing = null;
                editingCoords = false;
            }
            return handled;
        }
        return super.menuKey(key, scancode, modifiers);
    }

    @Override
    protected boolean menuChar(char ch) {
        if (nameField != null && nameField.focused()) return nameField.charTyped(ch);
        return super.menuChar(ch);
    }

    @Override
    protected void beforeClose() {
        commitName();
        WaypointStore.save();
        super.beforeClose();
    }
}
