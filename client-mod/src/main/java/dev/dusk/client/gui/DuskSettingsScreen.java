package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.gui.widget.DropdownPopup;
import dev.dusk.client.gui.widget.DropdownWidget;
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
 * The module list, after Flex-HUD's: search and a category dropdown on top,
 * then a grid of big item icons, each over its name button (opens the
 * module's settings) and an on/off box.
 */
public class DuskSettingsScreen extends MenuScreen {
    private static final int ICON = 64, CELL_W = 140, COL_STRIDE = 150, ROW_STRIDE = 94, MAX_COLS = 6;
    private static final int LIST_TOP = 50, SCROLL_STEP = 24;

    /** Modules added since the previous release, listed under "New". Update this set every release. */
    private static final Set<String> NEW_IDS = Set.of("behindyou", "nametags", "particles", "hitbox");
    private static final Module.Category[] ORDER = {
            Module.Category.HUD, Module.Category.RENDER, Module.Category.MOVEMENT, Module.Category.MISC};

    private enum Filter {
        ALL("All", null), NEW("New", null), HUD("HUD", Module.Category.HUD), RENDER("Render", Module.Category.RENDER),
        MOVEMENT("Movement", Module.Category.MOVEMENT), UTILITY("Utility", Module.Category.MISC);

        final String label;
        @Nullable final Module.Category category;

        Filter(String label, @Nullable Module.Category category) {
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

    private static Filter filter = Filter.ALL;

    private final TextFieldWidget search;
    private final List<Module> shown = new ArrayList<>();
    private String query = "";
    private int scroll, cols, gridX;
    private boolean draggingBar;

    public DuskSettingsScreen(@Nullable Screen parent) {
        super(Component.literal("Modules"), parent);
        this.search = new TextFieldWidget(() -> "", this::setQuery, true, 64).placeholder("Search...");
        refresh();
    }

    private void setQuery(String q) {
        query = q.toLowerCase(Locale.ROOT).trim();
        scroll = 0;
        refresh();
    }

    private void refresh() {
        shown.clear();
        if (DuskClient.modules() == null) return;
        for (Module.Category cat : ORDER) {
            for (Module m : DuskClient.modules().all()) {
                if (m.category() == cat && filter.shows(m) && matches(m, query)) shown.add(m);
            }
        }
    }

    private static boolean matches(Module m, String q) {
        if (q.isEmpty()) return true;
        if (m.name().toLowerCase(Locale.ROOT).contains(q) || m.description().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (Setting<?> s : m.settings()) if (s.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    // ---- layout --------------------------------------------------------------------

    @Override
    protected void init() {
        cols = Math.max(1, Math.min(MAX_COLS, (this.width - 30) / COL_STRIDE));
        gridX = (this.width - (cols * COL_STRIDE - (COL_STRIDE - CELL_W))) / 2;
        int sw = Math.max(80, Math.min(200, this.width - 2 * 118));
        search.setBounds(this.width / 2 - sw / 2, 20, sw, 20);
        clampScroll();
    }

    private int listBottom() { return this.height - 34; }

    private int contentHeight() {
        int rows = (shown.size() + cols - 1) / cols;
        return rows == 0 ? 0 : rows * ROW_STRIDE - (ROW_STRIDE - ICON - 20);
    }

    private int maxScroll() { return Math.max(0, contentHeight() - (listBottom() - LIST_TOP)); }

    private void clampScroll() { scroll = Math.max(0, Math.min(maxScroll(), scroll)); }

    private int cellX(int i) { return gridX + (i % cols) * COL_STRIDE; }
    private int cellY(int i) { return LIST_TOP + (i / cols) * ROW_STRIDE - scroll; }
    private int barX() { return gridX + cols * COL_STRIDE - (COL_STRIDE - CELL_W) + 6; }
    private int filterX() { return this.width - 110; }

    private boolean inList(double mx, double my) {
        return my >= LIST_TOP && my < listBottom();
    }

    // ---- drawing --------------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        c.centeredText("Modules", this.width / 2, 7, Vanilla.TEXT, true);
        Vanilla.button(c, "Edit layout", 10, 20, 100, 20, Vanilla.inside(mouseX, mouseY, 10, 20, 100, 20), inWorld());
        search.render(c, mouseX, mouseY);
        DropdownWidget.drawButton(c, filter.label, filterX(), 20, 100, 20, Vanilla.inside(mouseX, mouseY, filterX(), 20, 100, 20));

        int bottom = listBottom();
        boolean inList = inList(mouseX, mouseY);
        c.scissor(0, LIST_TOP, this.width, bottom);
        for (int i = 0; i < shown.size(); i++) {
            int x = cellX(i), y = cellY(i);
            if (y + ICON + 20 < LIST_TOP || y > bottom) continue;
            drawCell(c, shown.get(i), x, y, inList ? mouseX : -1, inList ? mouseY : -1);
        }
        c.unscissor();
        if (shown.isEmpty()) {
            c.centeredText(query.isEmpty() ? "Nothing here yet" : "No modules match \"" + search.text().trim() + "\"",
                    this.width / 2, LIST_TOP + 20, Vanilla.TEXT_OFF, true);
        }
        if (maxScroll() > 0) {
            int h = bottom - LIST_TOP, content = contentHeight();
            int barH = Math.max(32, h * h / content);
            Vanilla.scrollbar(c, barX(), LIST_TOP, bottom, LIST_TOP + (h - barH) * scroll / maxScroll(), barH);
        }

        int dx = this.width / 2 - 80, dy = this.height - 27;
        Vanilla.button(c, "Done", dx, dy, 160, 20, Vanilla.inside(mouseX, mouseY, dx, dy, 160, 20), true);
        if (!inWorld() && Vanilla.inside(mouseX, mouseY, 10, 20, 100, 20)) {
            Vanilla.tooltip(c, "Join a world to edit the HUD layout", mouseX, mouseY, this.width, this.height);
        }
    }

    private void drawCell(Canvas c, Module m, int x, int y, int mouseX, int mouseY) {
        int ix = x + (CELL_W - ICON) / 2;
        if (Vanilla.inside(mouseX, mouseY, ix, y, ICON, ICON)) c.fill(ix, y, ix + ICON, y + ICON, 0x33FFFFFF);
        ItemStack stack = ModuleIcons.of(m.id());
        if (!stack.isEmpty()) {
            c.push();
            c.translate(ix, y);
            c.scale(4, 4);
            c.item(stack, 0, 0);
            c.pop();
        } else {
            Icons glyph = fallbackGlyph(m.category());
            glyph.draw(c, ix + 11, y + 11, Vanilla.TEXT, 6);
        }
        if (NEW_IDS.contains(m.id())) c.text("NEW", ix + ICON - c.textWidth("NEW"), y + 1, 0xFFFFFF55, true);

        int by = y + ICON, nameW = CELL_W - 24;
        boolean nameHover = Vanilla.inside(mouseX, mouseY, x, by, nameW, 20);
        Vanilla.button(c, m.name(), x, by, nameW, 20, nameHover, true);
        boolean boxHover = Vanilla.inside(mouseX, mouseY, x + CELL_W - 20, by, 20, 20);
        Vanilla.toggleBox(c, x + CELL_W - 20, by, 20, m.enabled(), boxHover, true);
    }

    private static Icons fallbackGlyph(Module.Category category) {
        return switch (category) {
            case HUD -> Icons.HUD;
            case RENDER -> Icons.EYE;
            case MOVEMENT -> Icons.ARROW;
            default -> Icons.GEAR;
        };
    }

    // ---- input ----------------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        if (search.click(mx, my, button)) return true;
        if (button != 0) return false;
        if (Vanilla.inside(mx, my, 10, 20, 100, 20)) {
            if (inWorld()) open(new HudEditorScreen(this));
            return true;
        }
        if (Vanilla.inside(mx, my, filterX(), 20, 100, 20)) {
            List<String> labels = Arrays.stream(Filter.values()).map(f -> f.label).toList();
            popups.open(new DropdownPopup(filterX(), 20, 100, 20, labels, filter.ordinal(), i -> {
                filter = Filter.values()[i];
                scroll = 0;
                refresh();
            }));
            return true;
        }
        if (Vanilla.inside(mx, my, this.width / 2 - 80, this.height - 27, 160, 20)) {
            onClose();
            return true;
        }
        if (maxScroll() > 0 && Vanilla.inside(mx, my, barX(), LIST_TOP, Vanilla.SCROLLBAR_W, listBottom() - LIST_TOP)) {
            draggingBar = true;
            dragBar(my);
            return true;
        }
        if (!inList(mx, my)) return false;
        for (int i = 0; i < shown.size(); i++) {
            Module m = shown.get(i);
            int x = cellX(i), y = cellY(i), by = y + ICON;
            if (Vanilla.inside(mx, my, x + CELL_W - 20, by, 20, 20)) {
                m.setEnabled(!m.enabled());
                saveModules();
                return true;
            }
            if (Vanilla.inside(mx, my, x, by, CELL_W - 24, 20) || Vanilla.inside(mx, my, x + (CELL_W - ICON) / 2, y, ICON, ICON)) {
                open(ConfigScreen.module(this, m));
                return true;
            }
        }
        return false;
    }

    private void dragBar(double my) {
        int h = listBottom() - LIST_TOP;
        scroll = (int) ((my - LIST_TOP) / h * contentHeight() - h / 2.0);
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
