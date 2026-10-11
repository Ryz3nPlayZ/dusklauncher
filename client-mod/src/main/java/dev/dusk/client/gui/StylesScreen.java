package dev.dusk.client.gui;

import dev.dusk.client.modules.render.Styles;
import dev.dusk.client.styles.StylePacks;
import dev.dusk.client.styles.StylePacks.Category;
import dev.dusk.client.styles.StylePacks.Style;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Styles page: categories down the left, the packs for the chosen one
 * on the right. Clicking a pack picks it for that category and reloads the
 * game's resources with it on top; "Default" goes back to the normal look.
 * Add-ons (packs that only add to a category, like an anchor glow) toggle
 * on and off on their own. See {@link StylePacks}.
 */
public class StylesScreen extends PanelScreen {
    private static final String[] TABS = {"STYLES"};
    private static final int GAP = 6, TILE = 74, LABEL_H = 12, SIDE_GAP = 2;
    private static String lastCategory = "crystal";

    private record Cell(@Nullable Style style, int x, int y) {}

    private final RemoteImages icons = new RemoteImages(64);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duskclient-styles");
        t.setDaemon(true);
        return t;
    });

    private List<Style> styles = List.of();
    private boolean loading;
    private String category = lastCategory;
    private final List<Category> shown = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private int addonHeaderY = -1;
    private int sideX, sideY, sideW, sideRow, tileW, tileH, headerY, contentH;

    public StylesScreen(@Nullable Screen parent) {
        super(Component.literal("Styles"), parent);
        refresh();
    }

    // ---- data -----------------------------------------------------------------

    private void refresh() {
        loading = true;
        for (Style s : styles) icons.invalidate(iconKey(s));
        worker.execute(() -> {
            List<Style> found = StylePacks.scan();
            Minecraft.getInstance().execute(() -> {
                styles = found;
                loading = false;
            });
        });
    }

    private static String iconKey(Style s) {
        return "style/" + s.file();
    }

    private List<Style> inCategory(String id, boolean addons) {
        List<Style> out = new ArrayList<>();
        for (Style s : styles) if (s.category().equals(id) && s.addon() == addons) out.add(s);
        return out;
    }

    @Nullable
    private Style byFile(@Nullable String file) {
        if (file == null) return null;
        for (Style s : styles) if (s.file().equals(file)) return s;
        return null;
    }

    private void choose(@Nullable Style s) {
        Styles m = Styles.get();
        if (m == null) return;
        if (s != null && s.addon()) m.setAddon(s.file(), !m.addonOn(s.file()));
        else if (s == null && m.pick(category) == null) return;
        else if (s != null && s.file().equals(m.pick(category))) return;
        else m.setPick(category, s == null ? null : s.file());
        saveModules();
        if (m.enabled()) StylePacks.apply();
        else m.setEnabled(true); // turning it on applies the picks
    }

    private boolean picked(Style s) {
        Styles m = Styles.get();
        if (m == null) return false;
        return s.addon() ? m.addonOn(s.file()) : s.file().equals(m.pick(s.category()));
    }

    // ---- layout -----------------------------------------------------------------

    @Override
    protected String[] tabs() {
        return TABS;
    }

    @Override
    protected int activeTab() {
        return 0;
    }

    @Override
    protected void selectTab(int i) {}

    @Override
    protected List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("close", Icons.CLOSE, "", "Close"));
        t.add(new Tool("folder", null, "OPEN FOLDER", "Open the styles folder: put resource packs there"));
        t.add(new Tool("refresh", null, "REFRESH", "Look for new packs in the styles folder"));
        return t;
    }

    @Override
    protected void onTool(String id) {
        switch (id) {
            case "folder" -> {
                Path dir = StylePacks.folder();
                try {
                    Files.createDirectories(dir);
                } catch (IOException ignored) {
                }
                PackBrowserScreen.openFolder(dir);
            }
            case "refresh" -> refresh();
            default -> super.onTool(id);
        }
    }

    private void layout() {
        layoutPanel();
        shown.clear();
        for (Category c : StylePacks.CATEGORIES) {
            for (Style s : styles) {
                if (s.category().equals(c.id())) {
                    shown.add(c);
                    break;
                }
            }
        }
        if (!shown.isEmpty() && shown.stream().noneMatch(c -> c.id().equals(category))) category = shown.get(0).id();

        int top = bodyY() + pad, bottom = py + ph - pad;
        sideX = px + pad;
        sideY = top;
        sideW = Math.max(90, Math.min(130, Math.round(pw * 0.22f)));
        sideRow = shown.isEmpty() ? 22 : Math.max(12, Math.min(22, (bottom - top + SIDE_GAP) / shown.size() - SIDE_GAP));

        listX = sideX + sideW + pad;
        headerY = top;
        listY = top + 16;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = bottom;

        int cols = Math.max(2, (listW + GAP) / (TILE + GAP));
        tileW = (listW - GAP * (cols - 1)) / cols;
        tileH = tileW + LABEL_H;
        cells.clear();
        addonHeaderY = -1;
        List<Style> main = inCategory(category, false), addons = inCategory(category, true);
        int i = 0;
        cells.add(cell(null, i++, cols, 0));
        for (Style s : main) cells.add(cell(s, i++, cols, 0));
        int rows = (i + cols - 1) / cols;
        contentH = rows * (tileH + GAP) - GAP;
        if (!addons.isEmpty()) {
            addonHeaderY = contentH + GAP + 4;
            int off = addonHeaderY + 14;
            int j = 0;
            for (Style s : addons) cells.add(cell(s, j++, cols, off));
            contentH = off + ((j + cols - 1) / cols) * (tileH + GAP) - GAP;
        }
        clampScroll();
    }

    private Cell cell(@Nullable Style s, int i, int cols, int yOff) {
        return new Cell(s, (i % cols) * (tileW + GAP), yOff + (i / cols) * (tileH + GAP));
    }

    @Override
    protected int contentHeight() {
        return contentH;
    }

    // ---- drawing ----------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        drawPanel(c, mouseX, mouseY);
        if (styles.isEmpty()) {
            drawEmpty(c);
            return;
        }
        drawSide(c, mouseX, mouseY);
        drawHeader(c, mouseX, mouseY);

        boolean hot = inList(mouseX, mouseY);
        c.scissor(listX, listY, listX + listW, listBottom);
        if (addonHeaderY >= 0) {
            c.text("ADD-ONS: stack with the pick above", listX, listY + addonHeaderY - scroll, Theme.TEXT_MUTED, false);
        }
        Category cat = StylePacks.category(category);
        for (Cell cell : cells) {
            int x = listX + cell.x, y = listY + cell.y - scroll;
            if (y + tileH < listY || y > listBottom) continue;
            drawTile(c, cell.style, cat, x, y, hot && Vanilla.inside(mouseX, mouseY, x, y, tileW, tileH));
        }
        c.unscissor();
        drawScrollbar(c);
    }

    private void drawEmpty(Canvas c) {
        int w = Math.min(pw - pad * 2, 300), x = px + (pw - w) / 2, y = bodyY() + ph / 3;
        String msg = loading ? "Looking for styles..."
                : "No styles yet. Put resource packs (zips or folders) in the styles folder with OPEN FOLDER, then press REFRESH. "
                + "Each pack is listed under the part of the game it changes, and you pick one per part.";
        for (String line : wrap(c, msg, w)) {
            c.centeredText(line, px + pw / 2, y, Theme.TEXT_MUTED, false);
            y += 11;
        }
        if (!loading) c.centeredText(StylePacks.folder().toString(), px + pw / 2, y + 6, Theme.TEXT_FAINT, false);
    }

    private void drawSide(Canvas c, int mouseX, int mouseY) {
        Styles m = Styles.get();
        for (int i = 0; i < shown.size(); i++) {
            Category cat = shown.get(i);
            int y = sideY + i * (sideRow + SIDE_GAP);
            boolean sel = cat.id().equals(category), hover = Vanilla.inside(mouseX, mouseY, sideX, y, sideW, sideRow);
            if (sel) c.fill(sideX, y, sideX + sideW, y + sideRow, 0x40FFFFFF);
            else if (hover) c.fill(sideX, y, sideX + sideW, y + sideRow, 0x1AFFFFFF);
            int tx = sideX + 4;
            if (sideRow >= 18) {
                c.item(new ItemStack(cat.icon()), sideX + 3, y + (sideRow - 16) / 2);
                tx = sideX + 23;
            }
            c.text(Theme.ellipsize(c, cat.name(), sideX + sideW - 10 - tx), tx, y + (sideRow - 8) / 2, sel ? Theme.TEXT : Theme.TEXT_MUTED, false);
            if (m != null && m.pick(cat.id()) != null) {
                c.fill(sideX + sideW - 7, y + sideRow / 2 - 2, sideX + sideW - 3, y + sideRow / 2 + 2, Theme.GREEN_LO);
            }
        }
        Theme.vDivider(c, listX - pad / 2 - 1, sideY, listBottom);
    }

    private void drawHeader(Canvas c, int mouseX, int mouseY) {
        Category cat = StylePacks.category(category);
        Styles m = Styles.get();
        String name = cat == null ? category : cat.name();
        c.text(name, listX, headerY + 2, Theme.TEXT, false);
        Style pick = m == null ? null : byFile(m.pick(category));
        String now = pick != null ? "Using " + pick.name() : "Using the default look";
        if (m != null && !m.enabled()) now = "Styles is off; picking one turns it on";
        int nx = listX + c.textWidth(name) + 8;
        int right = listX + listW;
        int[] st = seeThroughBox();
        if (st != null && m != null) {
            flatButton(c, seeThroughLabel(m), st[0], st[1], st[2], st[3], mouseX, mouseY, !m.seeThrough().equals("Off"), true);
            right = st[0] - 6;
        }
        c.text(Theme.ellipsize(c, now, right - nx), nx, headerY + 2, Theme.TEXT_FAINT, false);
    }

    @Nullable
    private int[] seeThroughBox() {
        Styles m = Styles.get();
        if (!category.equals("crystal") || !Styles.crystalHooked || m == null) return null;
        int bw = this.font.width(seeThroughLabel(m)) + 12;
        return new int[] {listX + listW - bw, headerY - 1, bw, 13};
    }

    private static String seeThroughLabel(Styles m) {
        return "SEE-THROUGH: " + m.seeThrough().toUpperCase(Locale.ROOT);
    }

    private void drawTile(Canvas c, @Nullable Style s, @Nullable Category cat, int x, int y, boolean hover) {
        Styles m = Styles.get();
        boolean on = s == null ? m == null || m.pick(category) == null : picked(s);
        Theme.plate(c, x, y, tileW, tileH, Theme.SURFACE, Theme.SURFACE_BOT, hover);
        int is = tileW - 10, ix = x + 5, iy = y + 5;
        c.fill(ix, iy, ix + is, iy + is, 0x33000000);
        RemoteImages.Image img = s == null || s.icon() == null ? null : icons.get(iconKey(s), s::icon);
        if (img != null) {
            c.push();
            c.translate(ix, iy);
            c.scale(is / (float) img.w(), is / (float) img.h());
            c.blit(img.id(), 0, 0, 0, 0, img.w(), img.h(), img.w(), img.h());
            c.pop();
        } else if (cat != null) {
            float k = Math.max(1, is / 2 / 16f * 1.5f);
            c.push();
            c.translate(ix + (is - 16 * k) / 2f, iy + (is - 16 * k) / 2f);
            c.scale(k, k);
            c.item(new ItemStack(cat.icon()), 0, 0);
            c.pop();
        }
        String label = s == null ? "Default" : s.name();
        c.centeredText(Theme.ellipsize(c, label, tileW - 6), x + tileW / 2, y + tileW - 2, on ? Theme.GREEN_UP : Theme.TEXT, false);
        if (on) {
            c.outline(x, y, tileW, tileH, Theme.GREEN_LO);
            c.outline(x + 1, y + 1, tileW - 2, tileH - 2, Theme.GREEN_LO);
            Icons.CHECK.draw(c, x + tileW - 12, y + 4, Theme.GREEN_UP);
        }
    }

    // ---- input ------------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (clickChrome(mx, my, button)) return true;
        if (button != 0 || styles.isEmpty()) return Vanilla.inside(mx, my, px, py, pw, ph);
        for (int i = 0; i < shown.size(); i++) {
            int y = sideY + i * (sideRow + SIDE_GAP);
            if (Vanilla.inside(mx, my, sideX, y, sideW, sideRow)) {
                category = lastCategory = shown.get(i).id();
                scroll = 0;
                return true;
            }
        }
        int[] st = seeThroughBox();
        if (st != null && Vanilla.inside(mx, my, st[0], st[1], st[2], st[3])) {
            Styles m = Styles.get();
            if (m != null) m.cycleSeeThrough();
            saveModules();
            return true;
        }
        if (inList(mx, my)) {
            for (Cell cell : cells) {
                int x = listX + cell.x, y = listY + cell.y - scroll;
                if (Vanilla.inside(mx, my, x, y, tileW, tileH)) {
                    choose(cell.style);
                    return true;
                }
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    public void removed() {
        super.removed();
        icons.releaseAll();
        worker.shutdown();
    }
}
