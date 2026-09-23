package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.gui.widget.ButtonWidget;
import dev.dusk.client.gui.widget.ColorWidget;
import dev.dusk.client.gui.widget.CycleWidget;
import dev.dusk.client.gui.widget.LabelWidget;
import dev.dusk.client.gui.widget.PixelGridWidget;
import dev.dusk.client.gui.widget.SliderWidget;
import dev.dusk.client.gui.widget.ToggleWidget;
import dev.dusk.client.gui.widget.Widget;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.Module;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.PixelGridSetting;
import dev.dusk.client.module.setting.Setting;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
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

    private final List<Widget> widgets = new ArrayList<>();
    private View view = View.LIST;
    private Module selected;
    private int scroll;
    private int contentHeight;
    private boolean minimized;
    private Widget dragTarget;

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
        scroll = 0;
        rebuild();
    }

    public void open(Module module) {
        view = View.SETTINGS;
        selected = module;
        scroll = 0;
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
        int cy = contentTop() - scroll;
        int cx = x + PADDING;
        int cw = w - PADDING * 2 - 4; // room for the scrollbar
        for (Widget wd : widgets) {
            wd.setBounds(cx, cy, cw, wd.h);
            cy += wd.h;
        }
        contentHeight = cy + scroll - contentTop();
        clampScroll();
    }

    private int contentTop() { return y + TITLE_H + 2; }

    private int contentBottom() { return y + h - 4; }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - (contentBottom() - contentTop()));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    private void rebuild() {
        widgets.clear();
        if (view == View.LIST) buildList(); else buildSettings(selected);
    }

    private void buildList() {
        var modules = DuskClient.modules();
        if (modules == null) return;
        for (Module.Category cat : Module.Category.values()) {
            List<Module> inCat = modules.all().stream().filter(m -> m.category() == cat).toList();
            if (inCat.isEmpty()) continue;
            LabelWidget header = new LabelWidget(cat.label.toUpperCase(), Theme.ACCENT);
            header.h = 14;
            widgets.add(header);
            for (Module m : inCat) {
                ModuleRow row = new ModuleRow(m);
                row.h = ROW_H;
                widgets.add(row);
            }
        }
    }

    private void buildSettings(Module m) {
        if (!m.description().isEmpty()) {
            for (String line : wrap(m.description(), WIDTH - PADDING * 2 - 8)) {
                LabelWidget l = new LabelWidget(line);
                l.h = 11;
                widgets.add(l);
            }
            LabelWidget gap = new LabelWidget("");
            gap.h = 4;
            widgets.add(gap);
        }
        ToggleWidget enabled = new ToggleWidget("Enabled", m::enabled, v -> { m.setEnabled(v); onChange.run(); });
        enabled.h = ROW_H;
        widgets.add(enabled);
        for (Setting<?> s : m.settings()) {
            Widget wd = widgetFor(m, s);
            if (wd == null) continue;
            if (wd instanceof PixelGridWidget grid) wd.h = grid.preferredHeight();
            else wd.h = wd instanceof SliderWidget ? ROW_H + 4 : ROW_H;
            widgets.add(wd);
        }
        LabelWidget gap = new LabelWidget("");
        gap.h = 6;
        widgets.add(gap);
        ButtonWidget reset = new ButtonWidget(m instanceof HudElement ? "Reset settings & position" : "Reset settings", () -> {
            for (Setting<?> s : m.settings()) s.reset();
            if (m instanceof HudElement) m.setPosition(10, 10);
            onChange.run();
            rebuild();
        });
        reset.h = ROW_H - 2;
        widgets.add(reset);
    }

    private Widget widgetFor(Module module, Setting<?> s) {
        if (s instanceof BoolSetting b) return new ToggleWidget(b.name(), b::get, v -> { b.set(v); onChange.run(); });
        if (s instanceof IntSetting i) return new SliderWidget(i, onChange);
        if (s instanceof ChoiceSetting c) return new CycleWidget(c, onChange);
        if (s instanceof ColorSetting c) return new ColorWidget(c, onChange);
        if (s instanceof PixelGridSetting g && module instanceof CustomCrosshair crosshair) {
            return new PixelGridWidget(g, crosshair.pixelColor(), onChange, crosshair::markCustom);
        }
        return null;
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    // ---- drawing ------------------------------------------------------

    public void render(Canvas c, int mouseX, int mouseY) {
        c.fill(x, y, x + w, y + h, Theme.WINDOW_BG);
        c.outline(x, y, w, h, Theme.BORDER);
        c.fill(x, y, x + w, y + TITLE_H, Theme.HEADER_BG);
        c.fill(x, y + TITLE_H - 1, x + w, y + TITLE_H, Theme.BORDER);

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
            c.text("Dusk Client", x + 6, ty, Theme.ACCENT, false);
            c.text("Modules", x + 6 + font.width("Dusk Client ") , ty, Theme.TEXT, false);
        }
        // title-bar buttons: minimise, close
        int bx = x + w - TITLE_BTN * 2 - 4;
        drawTitleButton(c, bx, "-", inTitleButton(mouseX, mouseY, 0));
        drawTitleButton(c, bx + TITLE_BTN, "x", inTitleButton(mouseX, mouseY, 1));

        // scrolled content
        c.scissor(x + 1, contentTop(), x + w - 1, contentBottom());
        for (Widget wd : widgets) {
            if (wd.y + wd.h < contentTop() || wd.y > contentBottom()) continue;
            wd.render(c, inContent(mouseX, mouseY) ? mouseX : -1, inContent(mouseX, mouseY) ? mouseY : -1);
        }
        c.unscissor();

        int viewH = contentBottom() - contentTop();
        if (contentHeight > viewH) {
            int sx = x + w - 4;
            c.fill(sx, contentTop(), sx + 2, contentBottom(), Theme.TRACK);
            int barH = Math.max(8, viewH * viewH / contentHeight);
            int barY = contentTop() + (viewH - barH) * scroll / Math.max(1, contentHeight - viewH);
            c.fill(sx, barY, sx + 2, barY + barH, Theme.TEXT_MUTED);
        }
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
        for (Widget wd : widgets) {
            if (wd.click(mx, my, button)) {
                dragTarget = wd;
                return true;
            }
        }
        return true;
    }

    public void drag(double mx, double my) {
        if (dragTarget != null) dragTarget.drag(mx, my);
    }

    public void release() {
        if (dragTarget != null) {
            dragTarget.release();
            dragTarget = null;
        }
    }

    public boolean scroll(double mx, double my, double amount) {
        if (!inContent(mx, my)) return contains(mx, my);
        scroll -= (int) Math.signum(amount) * ROW_H;
        clampScroll();
        return true;
    }

    public boolean keyPressed(int key, int modifiers) {
        for (Widget wd : widgets) if (wd.focused() && wd.keyPressed(key, modifiers)) return true;
        return false;
    }

    public boolean charTyped(char ch) {
        for (Widget wd : widgets) if (wd.focused() && wd.charTyped(ch)) return true;
        return false;
    }

    /** Drops text-field focus (commits pending edits). */
    public void blur() {
        for (Widget wd : widgets) if (wd.focused()) wd.setFocused(false);
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
