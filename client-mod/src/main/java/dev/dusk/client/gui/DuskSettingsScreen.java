package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.Module;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Our own mod menu: every registered module with an on/off toggle, the
 * background path field (shared with the launcher via duskclient.json),
 * and the entry point to the module window / HUD layout editor.
 */
public class DuskSettingsScreen extends DuskScreen {
    private static final int ROW_W = 260;
    private static final int ROW_H = 20;

    private final Screen parent;
    private EditBox backgroundField;

    public DuskSettingsScreen(Screen parent) {
        super(Component.literal("Dusk Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        int y = 48;

        this.addRenderableWidget(Button.builder(Component.literal("Modules & HUD Editor..."), b -> {
                    if (this.minecraft != null) Compat.setScreen(this.minecraft, new HudEditorScreen(this));
                })
                .bounds(cx - ROW_W / 2, y, ROW_W, ROW_H).build());
        y += 24;

        // Quick toggles for the non-HUD modules; HUD elements live in the editor.
        var modules = DuskClient.modules() != null ? DuskClient.modules().all() : java.util.List.<Module>of();
        for (Module m : modules) {
            if (m instanceof HudElement) continue;
            String label = m.name() + ": " + (m.enabled() ? "ON" : "OFF");
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                        m.setEnabled(!m.enabled());
                        DuskClient.modules().saveConfig();
                        b.setMessage(Component.literal(m.name() + ": " + (m.enabled() ? "ON" : "OFF")));
                    })
                    .bounds(cx - ROW_W / 2, y, ROW_W, ROW_H).build());
            y += 24;
            if (y > this.height - 110) break;
        }

        y = Math.max(y + 8, this.height - 104);
        this.backgroundField = new EditBox(this.font, cx - ROW_W / 2, y, ROW_W, ROW_H,
                Component.literal("Background image path"));
        this.backgroundField.setMaxLength(512);
        this.backgroundField.setValue(DuskConfig.get().backgroundPath);
        this.backgroundField.setHint(Component.literal("Background PNG/JPG path (empty = panorama)"));
        this.addRenderableWidget(this.backgroundField);

        y += 26;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
                    DuskConfig.get().backgroundPath = this.backgroundField.getValue().trim();
                    DuskConfig.save();
                    b.setMessage(Component.literal("Saved"));
                })
                .bounds(cx - ROW_W / 2, y, 126, ROW_H).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
                    if (this.minecraft != null) Compat.setScreen(this.minecraft, this.parent);
                })
                .bounds(cx + 4, y, 126, ROW_H).build());
    }

    @Override
    protected void drawOverlay(Canvas canvas, int mouseX, int mouseY, float delta) {
        canvas.centeredText(Component.literal("DUSK SETTINGS"), this.width / 2, 20, 0xFFFFD000);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) Compat.setScreen(this.minecraft, this.parent);
    }
}
