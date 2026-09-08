package dev.fasterlauncher.client.gui;

import dev.fasterlauncher.client.FasterClient;
import dev.fasterlauncher.client.config.DuskConfig;
import dev.fasterlauncher.client.module.Module;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Our own mod menu: every registered module with an on/off toggle, the
 * background path field (shared with the launcher via duskclient.json),
 * and a HUD-layout entry point (drag editor lands here next).
 */
public class DuskSettingsScreen extends Screen {
    private static final int ROW_W = 260;
    private static final int ROW_H = 20;

    private final Screen parent;
    private TextFieldWidget backgroundField;

    public DuskSettingsScreen(Screen parent) {
        super(Text.literal("Dusk Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearChildren();
        int cx = this.width / 2;
        int y = 48;

        var modules = FasterClient.modules() != null ? FasterClient.modules().all() : java.util.List.<Module>of();
        for (Module m : modules) {
            String label = m.name() + ": " + (m.enabled() ? "ON" : "OFF");
            this.addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                        m.setEnabled(!m.enabled());
                        FasterClient.modules().saveConfig();
                        b.setMessage(Text.literal(m.name() + ": " + (m.enabled() ? "ON" : "OFF")));
                    })
                    .dimensions(cx - ROW_W / 2, y, ROW_W, ROW_H).build());
            y += 24;
            if (y > this.height - 110) break; // first page only; scrolling list lands with the HUD editor
        }

        y = Math.max(y + 8, this.height - 104);
        this.backgroundField = new TextFieldWidget(this.textRenderer, cx - ROW_W / 2, y, ROW_W, ROW_H,
                Text.literal("Background image path"));
        this.backgroundField.setMaxLength(512);
        this.backgroundField.setText(DuskConfig.get().backgroundPath);
        this.backgroundField.setPlaceholder(Text.literal("Background PNG/JPG path (empty = panorama)"));
        this.addDrawableChild(this.backgroundField);

        y += 26;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> {
                    DuskConfig.get().backgroundPath = this.backgroundField.getText().trim();
                    DuskConfig.save();
                    b.setMessage(Text.literal("Saved"));
                })
                .dimensions(cx - ROW_W / 2, y, 126, ROW_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> {
                    if (this.client != null) this.client.setScreen(this.parent);
                })
                .dimensions(cx + 4, y, 126, ROW_H).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("DUSK SETTINGS"),
                this.width / 2, 20, 0xFFFFD000);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(this.parent);
    }
}
