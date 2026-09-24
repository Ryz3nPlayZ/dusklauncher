package dev.dusk.client.gui;

import dev.dusk.client.gui.widget.ButtonWidget;
import dev.dusk.client.gui.widget.ColorWidget;
import dev.dusk.client.gui.widget.CycleWidget;
import dev.dusk.client.gui.widget.GroupHeaderWidget;
import dev.dusk.client.gui.widget.LabelWidget;
import dev.dusk.client.gui.widget.PixelGridWidget;
import dev.dusk.client.gui.widget.ScrollPane;
import dev.dusk.client.gui.widget.SliderWidget;
import dev.dusk.client.gui.widget.ToggleWidget;
import dev.dusk.client.gui.widget.Widget;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.PixelGridSetting;
import dev.dusk.client.module.setting.Setting;
import dev.dusk.client.modules.render.CustomCrosshair;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Fills a {@link ScrollPane} with one module's settings: description,
 * enabled switch, each setting (grouped under folding headers), reset.
 * Shared by the HUD editor window and the Dusk menu.
 */
public final class SettingsBuilder {
    public static final int ROW_H = 20;
    /** Modules with more settings than this open with only their first group unfolded. */
    private static final int EXPAND_ALL_LIMIT = 40;

    private SettingsBuilder() {}

    /**
     * @param filter   lower-case search text; empty shows everything, otherwise only
     *                 settings whose name or group matches (groups forced open)
     * @param rebuild  re-runs this build (after a reset, so widgets pick up defaults)
     */
    public static void build(ScrollPane pane, Module m, Font font, int width, String filter,
                             boolean enabledRow, Runnable onChange, Runnable rebuild) {
        boolean searching = filter != null && !filter.isBlank();
        String q = searching ? filter.toLowerCase(Locale.ROOT).trim() : "";
        if (!searching && !m.description().isEmpty()) {
            for (String line : wrap(font, m.description(), width - 8)) pane.add(new LabelWidget(line), 11);
            pane.add(new LabelWidget(""), 4);
        }
        if (enabledRow && !searching) {
            pane.add(new ToggleWidget("Enabled", m::enabled, v -> { m.setEnabled(v); onChange.run(); }), ROW_H);
        }
        List<Setting<?>> settings = m.settings();
        boolean expandAll = settings.size() <= EXPAND_ALL_LIMIT;
        String currentGroup = null;
        GroupHeaderWidget header = null;
        boolean firstGroup = true;
        int shown = 0;
        for (Setting<?> s : settings) {
            if (searching && !matches(s, q)) continue;
            Widget wd = widgetFor(m, s, onChange);
            if (wd == null) continue;
            String g = s.group();
            if (g != null && !Objects.equals(g, currentGroup)) {
                currentGroup = g;
                if (searching) {
                    pane.add(new LabelWidget(g.toUpperCase(Locale.ROOT), Theme.ACCENT), 14);
                    header = null;
                } else {
                    header = new GroupHeaderWidget(m.id() + "/" + g, g, !expandAll && !firstGroup, () -> {});
                    pane.add(header, 16);
                }
                firstGroup = false;
            } else if (g == null) {
                currentGroup = null;
                header = null;
            }
            wd.group = header;
            int height = wd instanceof PixelGridWidget grid ? grid.preferredHeight()
                    : wd instanceof SliderWidget ? ROW_H + 4 : ROW_H;
            pane.add(wd, height);
            shown++;
        }
        if (searching) {
            if (shown == 0) pane.add(new LabelWidget("No settings match \"" + filter.trim() + "\""), ROW_H);
            return;
        }
        pane.add(new LabelWidget(""), 6);
        ButtonWidget reset = new ButtonWidget(m instanceof HudElement ? "Reset settings & position" : "Reset settings", () -> {
            for (Setting<?> s : m.settings()) s.reset();
            if (m instanceof HudElement) m.setPosition(10, 10);
            onChange.run();
            rebuild.run();
        });
        pane.add(reset, ROW_H - 2);
    }

    private static boolean matches(Setting<?> s, String q) {
        return s.name().toLowerCase(Locale.ROOT).contains(q)
                || (s.group() != null && s.group().toLowerCase(Locale.ROOT).contains(q));
    }

    public static Widget widgetFor(Module module, Setting<?> s, Runnable onChange) {
        if (s instanceof BoolSetting b) return new ToggleWidget(b.name(), b::get, v -> { b.set(v); onChange.run(); });
        if (s instanceof IntSetting i) return new SliderWidget(i, onChange);
        if (s instanceof ChoiceSetting c) return new CycleWidget(c, onChange);
        if (s instanceof ColorSetting c) return new ColorWidget(c, onChange);
        if (s instanceof PixelGridSetting g && module instanceof CustomCrosshair crosshair) {
            return new PixelGridWidget(g, crosshair.pixelColor(), onChange, crosshair::markCustom);
        }
        return null;
    }

    public static List<String> wrap(Font font, String text, int maxWidth) {
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
}
