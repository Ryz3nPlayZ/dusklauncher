package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.gui.widget.GroupHeaderWidget;
import dev.dusk.client.gui.widget.ScrollPane;
import dev.dusk.client.gui.widget.ToggleWidget;
import dev.dusk.client.gui.widget.Widget;
import dev.dusk.client.module.Module;
import net.minecraft.client.gui.Font;

import java.util.List;
import java.util.function.Consumer;

/**
 * The single centered window of the HUD editor: a scrollable module list,
 * and (after clicking a module) that module's settings in the same box
 * with a back button. It never covers the whole screen, so the HUD stays
 * visible and draggable around it. Can be collapsed to a small bar.
 */
public class ModuleWindow {
    public static final int WIDTH = 300;
    private static final int MAX_HEIGHT = 250;
    private static final int TITLE_H = 18;
    private static final int ROW_H = 20;
    private static final int PADDING = 6;
    private static final int TITLE_BTN = 14;

    private enum View { LIST, SETTINGS }

    private final Font font;
    private final Runnable onChange;
    private final Consumer<Module> onFocusElement;
    private final Runnable onClose;

    private final ScrollPane pane = new ScrollPane();
    private View view = View.LIST;
    private Module selected;
    private boolean minimized;

    public int x, y, w, h;

    /**
     * @param onChange       called after any toggle/setting edit (persist)
     * @param onFocusElement called when a HUD module's settings open (editor highlights it)
     * @param onClose        the window's close button
     */
    public ModuleWindow(Font font, Runnable onChange, Consumer<Module> onFocusElement, Runnable onClose) {
        this.font = font;
        this.onChange = onChange;
        this.onFocusElement = onFocusElement;
        this.onClose = onClose;
        showList();
    }

    public boolean minimized() { return minimized; }

    public void setMinimized(boolean minimized) { this.minimized = minimized; }

    public Module selected() { return view == View.SETTINGS ? selected : null; }

    public void showList() {
        view = View.LIST;
        selected = null;
        pane.resetScroll();
        rebuild();
    }

    public void open(Module module) {
        view = View.SETTINGS;
        selected = module;
        pane.resetScroll();
        minimized = false;
        rebuild();
        onFocusElement.accept(module);
    }

    // ---- layout -------------------------------------------------------

    public void layout(int screenW, int screenH) {
        if (minimized) {
            w = 110;
            h = TITLE_H;
            x = (screenW - w) / 2;
            y = screenH - h - 6;
            return;
        }
        w = Math.min(WIDTH, screenW - 20);
        h = Math.min(MAX_HEIGHT, screenH - 30);
        x = (screenW - w) / 2;
        y = (screenH - h) / 2;
        pane.layout(x + PADDING, contentTop(), w - PADDING * 2 + 2, contentBottom() - contentTop());
    }

    private int contentTop() { return y + TITLE_H + 2; }

    private int contentBottom() { return y + h - 4; }

    private void rebuild() {
        pane.clear();
        if (view == View.LIST) buildList();
        else SettingsBuilder.build(pane, selected, font, w > 0 ? w - PADDING * 2 : WIDTH - PADDING * 2, "",
                true, onChange, this::rebuild);
    }

    private void buildList() {
        var modules = DuskClient.modules();
        if (modules == null) return;
        for (Module.Category cat : Module.Category.values()) {
            List<Module> inCat = modules.all().stream().filter(m -> m.category() == cat).toList();
            if (inCat.isEmpty()) continue;
            GroupHeaderWidget header = new GroupHeaderWidget("hud-editor/" + cat.name(), cat.label, false, () -> {});
            pane.add(header, 16);
            for (Module m : inCat) {
                ModuleRow row = new ModuleRow(m);
                row.group = header;
                pane.add(row, ROW_H);
            }
        }
    }

    // ---- drawing ------------------------------------------------------

    public void render(Canvas c, int mouseX, int mouseY) {
        Theme.panel(c, x, y, w, h);
        c.fill(x + 2, y + 2, x + w - 2, y + TITLE_H, Theme.HEADER_BG);
        Theme.divider(c, x + 2, x + w - 2, y + TITLE_H);

        int ty = y + (TITLE_H - font.lineHeight) / 2 + 1;
        if (minimized) {
            c.centeredText("^ Modules", x + w / 2, ty, Theme.TEXT, false);
            return;
        }

        if (view == View.SETTINGS) {
            boolean hoverBack = inBackButton(mouseX, mouseY);
            c.text("<", x + 6, ty, hoverBack ? Theme.ACCENT : Theme.TEXT_MUTED, false);
            c.text(selected.name(), x + 16, ty, Theme.TEXT, false);
        } else {
            c.text("Dusk", x + 6, ty, Theme.ACCENT, false);
            c.text("Modules", x + 6 + font.width("Dusk ") , ty, Theme.TEXT, false);
        }
        // title-bar buttons: minimise, close
        int bx = x + w - TITLE_BTN * 2 - 4;
        drawTitleButton(c, bx, "-", inTitleButton(mouseX, mouseY, 0));
        drawTitleButton(c, bx + TITLE_BTN, "x", inTitleButton(mouseX, mouseY, 1));

        pane.render(c, mouseX, mouseY);
    }

    private void drawTitleButton(Canvas c, int bx, String glyph, boolean hover) {
        int by = y + (TITLE_H - TITLE_BTN) / 2;
        if (hover) c.fill(bx, by, bx + TITLE_BTN, by + TITLE_BTN, Theme.ROW_HOVER);
        c.centeredText(glyph, bx + TITLE_BTN / 2, by + (TITLE_BTN - font.lineHeight) / 2 + 1,
                hover ? Theme.TEXT : Theme.TEXT_MUTED, false);
    }

    // ---- hit testing --------------------------------------------------

    public boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private boolean inContent(double mx, double my) {
        return !minimized && mx >= x && mx < x + w && my >= contentTop() && my < contentBottom();
    }

    private boolean inTitleButton(double mx, double my, int index) {
        if (minimized) return false;
        int bx = x + w - TITLE_BTN * 2 - 4 + index * TITLE_BTN;
        int by = y + (TITLE_H - TITLE_BTN) / 2;
        return mx >= bx && mx < bx + TITLE_BTN && my >= by && my < by + TITLE_BTN;
    }

    private boolean inBackButton(double mx, double my) {
        return view == View.SETTINGS && !minimized && mx >= x && mx < x + 16 && my >= y && my < y + TITLE_H;
    }

    // ---- input --------------------------------------------------------

    public boolean click(double mx, double my, int button) {
        if (!contains(mx, my)) {
            blur();
            return false;
        }
        if (minimized) {
            if (button == 0) minimized = false;
            return true;
        }
        if (button == 0 && inTitleButton(mx, my, 0)) { minimized = true; blur(); return true; }
        if (button == 0 && inTitleButton(mx, my, 1)) { onClose.run(); return true; }
        if (button == 0 && inBackButton(mx, my)) { showList(); return true; }
        if (button == 1 && view == View.SETTINGS && my < contentTop()) { showList(); return true; }
        if (!inContent(mx, my)) return true;
        pane.click(mx, my, button);
        return true;
    }

    public void drag(double mx, double my) {
        pane.drag(mx, my);
    }

    public void release() {
        pane.release();
    }

    public boolean scroll(double mx, double my, double amount) {
        if (!inContent(mx, my)) return contains(mx, my);
        pane.scroll(mx, my, amount);
        return true;
    }

    public boolean keyPressed(int key, int modifiers) {
        return pane.keyPressed(key, modifiers);
    }

    public boolean charTyped(char ch) {
        return pane.charTyped(ch);
    }

    /** Drops text-field focus (commits pending edits). */
    public void blur() {
        pane.blur();
    }

    /** One module in the list: name (click = open settings) and its switch. */
    private class ModuleRow extends Widget {
        private final Module module;

        ModuleRow(Module module) {
            this.module = module;
        }

        private boolean onSwitch(double mx) {
            return mx >= x + w - ToggleWidget.SWITCH_W - 12;
        }

        @Override
        public void render(Canvas c, int mouseX, int mouseY) {
            boolean hover = contains(mouseX, mouseY);
            if (hover) c.fill(x, y, x + w, y + h, Theme.ROW_HOVER);
            int ty = y + (h - c.lineHeight()) / 2 + 1;
            c.text(module.name(), x + 4, ty, module.enabled() ? Theme.TEXT : Theme.TEXT_MUTED, false);
            if (hover && !onSwitch(mouseX)) c.text(">", x + w - ToggleWidget.SWITCH_W - 22, ty, Theme.ACCENT, false);
            ToggleWidget.drawSwitch(c, x + w - ToggleWidget.SWITCH_W - 4, y + (h - ToggleWidget.SWITCH_H) / 2, module.enabled());
        }

        @Override
        public boolean click(double mx, double my, int button) {
            if (!contains(mx, my)) return false;
            if (button == 0 && onSwitch(mx)) {
                module.setEnabled(!module.enabled());
                onChange.run();
            } else {
                open(module);
            }
            return true;
        }
    }
}
