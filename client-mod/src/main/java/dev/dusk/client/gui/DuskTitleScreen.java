package dev.fasterlauncher.client.gui;

import dev.fasterlauncher.client.config.DuskConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Themed main menu: DUSK wordmark, single-player / multi-player entries,
 * account tile, Minecraft settings, Dusk settings (our own mod menu), custom
 * background shortcut, quit. No partners, no store chrome.
 *
 * <p>Backgrounds: vanilla panorama with a dark overlay for now. When
 * {@link DuskConfig#backgroundPath} points at an image, the launcher already
 * themes itself; the in-game texture pass (NativeImage upload) hooks into
 * {@link #renderCustomBackground} once it is pinned against a Yarn version.
 */
public class DuskTitleScreen extends Screen {
    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int GOLD = 0xFFFFD000;
    private static final int WHITE = 0xFFFFFFFF;

    public DuskTitleScreen() {
        super(Text.literal("DUSK"));
    }

    @Override
    protected void init() {
        this.clearChildren();
        int cx = this.width / 2;
        int y = this.height / 4 + 24;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Singleplayer"), b ->
                open(new SelectWorldScreen(this)))
                .dimensions(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Multiplayer"), b ->
                open(new MultiplayerScreen(this)))
                .dimensions(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Dusk Settings"), b ->
                open(new DuskSettingsScreen(this)))
                .dimensions(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Minecraft Settings"), b -> {
                    if (this.client != null) open(new OptionsScreen(this, this.client.options));
                })
                .dimensions(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Quit Game"), b -> {
                    if (this.client != null) this.client.scheduleStop();
                })
                .dimensions(cx - 100, y, 200, BUTTON_H).build());

        // background shortcut, bottom-right (mirrors the launcher footer tile)
        this.addDrawableChild(ButtonWidget.builder(Text.literal("BG"), b ->
                open(new DuskSettingsScreen(this)))
                .dimensions(this.width - 48, this.height - 28, 40, 20).build());
    }

    private void open(Screen screen) {
        if (this.client != null) this.client.setScreen(screen);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        renderCustomBackground(context);
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("DUSK"), cx, this.height / 4 - 28, GOLD);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("dusklauncher  ·  " + mcVersion()), cx, this.height / 4 - 12, 0xFF8B98A5);

        if (DuskConfig.get().showAccountTile && this.client != null) {
            String name = this.client.getSession().getUsername();
            int x0 = this.width - this.textRenderer.getWidth(name) - 24;
            context.fill(x0 - 8, 8, this.width - 8, 30, 0xB0101012);
            context.drawTextWithShadow(this.textRenderer, Text.literal(name), x0, 15, WHITE);
        }
    }

    /**
     * Hook for the image-background pass. Currently a dark overlay so the
     * menu reads on any panorama; replaced with a texture blit once the
     * NativeImage upload is pinned to a Yarn version.
     */
    protected void renderCustomBackground(DrawContext context) {
        context.fill(0, 0, this.width, this.height, 0x66000000);
    }

    private String mcVersion() {
        return this.client != null ? this.client.getGameVersion() : "minecraft";
    }
}
