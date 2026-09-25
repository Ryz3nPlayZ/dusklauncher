package dev.dusk.client.gui;

import dev.dusk.client.gui.widget.ColorWidget;
import dev.dusk.client.gui.widget.CrosshairWidget;
import dev.dusk.client.gui.widget.DropdownWidget;
import dev.dusk.client.gui.widget.GroupHeaderWidget;
import dev.dusk.client.gui.widget.PopupHost;
import dev.dusk.client.gui.widget.ScrollPane;
import dev.dusk.client.gui.widget.SettingRow;
import dev.dusk.client.gui.widget.SliderWidget;
import dev.dusk.client.gui.widget.ToggleWidget;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.PixelGridSetting;
import dev.dusk.client.module.setting.Setting;
import dev.dusk.client.modules.render.CrosshairPresets;
import dev.dusk.client.modules.render.CustomCrosshair;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fills a {@link ScrollPane} with one module's settings, Flex-HUD style:
 * the enabled toggle, then every setting (grouped under folding headers),
 * each with its own reset button.
 */
public final class SettingsBuilder {
    public static final int ROW_H = 24;
    /** Modules with more settings than this open with only their first group unfolded. */
    private static final int EXPAND_ALL_LIMIT = 40;

    private SettingsBuilder() {}

    /** @param onChange runs after any edit (the screen saves when it closes) */
    public static void build(ScrollPane pane, Module m, PopupHost host, Runnable onChange) {
        pane.add(new ToggleWidget("Enabled", m::enabled, v -> { m.setEnabled(v); onChange.run(); }), ROW_H);
        List<Setting<?>> settings = m.settings();
        boolean expandAll = settings.size() <= EXPAND_ALL_LIMIT;
        String currentGroup = null;
        GroupHeaderWidget header = null;
        boolean firstGroup = true;
        for (Setting<?> s : settings) {
            SettingRow row = rowFor(m, s, host, onChange);
            if (row == null) continue;
            String g = s.group();
            if (g != null && !Objects.equals(g, currentGroup)) {
                currentGroup = g;
                header = new GroupHeaderWidget(m.id() + "/" + g, g, !expandAll && !firstGroup, () -> {});
                pane.add(header, 22);
                firstGroup = false;
            } else if (g == null) {
                currentGroup = null;
                header = null;
            }
            row.group = header;
            pane.add(row, ROW_H);
        }
    }

    private static SettingRow rowFor(Module module, Setting<?> s, PopupHost host, Runnable onChange) {
        if (s instanceof BoolSetting b) {
            return new ToggleWidget(b.name(), b::get, v -> { b.set(v); onChange.run(); }).resets(s, onChange);
        }
        if (s instanceof IntSetting i) return new SliderWidget(i, onChange).resets(s, onChange);
        if (s instanceof ChoiceSetting c) return new DropdownWidget(c, onChange, host).resets(s, onChange);
        if (s instanceof ColorSetting c) return new ColorWidget(c, onChange, host).resets(s, onChange);
        if (s instanceof PixelGridSetting && module instanceof CustomCrosshair crosshair) {
            return new CrosshairWidget(crosshair, onChange, host).resets(s, () -> {
                crosshair.applyPreset(CrosshairPresets.NAMES[0]);
                onChange.run();
            });
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
