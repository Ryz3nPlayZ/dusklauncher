package dev.dusk.client.gui;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

/**
 * Themed main menu: DUSK wordmark, single-player / multi-player entries,
 * account tile, Minecraft settings, Dusk settings (our own mod menu), custom
 * background shortcut, quit. No partners, no store chrome.
 *
 * <p>Backgrounds: vanilla panorama with a dark overlay for now. When
 * {@link DuskConfig#backgroundPath} points at an image, the launcher already
 * themes itself; the in-game texture pass (NativeImage upload) hooks into
 * {@link #drawBackgroundOverlay} once it is pinned against a game version.
 */
public class DuskTitleScreen extends DuskScreen {
    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int GOLD = 0xFFFFD000;
    private static final int WHITE = 0xFFFFFFFF;

    public DuskTitleScreen() {
        super(Component.literal("DUSK"));
    }

    @Override
    protected void init() {
        this.clearWidgets();
        int cx = this.width / 2;
        int y = this.height / 4 + 24;

        this.addRenderableWidget(Button.builder(Component.literal("Singleplayer"), b ->
                open(new SelectWorldScreen(this)))
                .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Multiplayer"), b ->
                open(new JoinMultiplayerScreen(this)))
                .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Dusk Settings"), b ->
                open(new DuskSettingsScreen(this)))
                .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Minecraft Settings"), b -> {
                    if (this.minecraft != null) open(Compat.optionsScreen(this, this.minecraft));
                })
                .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Quit Game"), b -> {
                    if (this.minecraft != null) this.minecraft.stop();
                })
                .bounds(cx - 100, y, 200, BUTTON_H).build());

        // background shortcut, bottom-right (mirrors the launcher footer tile)
        this.addRenderableWidget(Button.builder(Component.literal("BG"), b ->
                open(new DuskSettingsScreen(this)))
                .bounds(this.width - 48, this.height - 28, 40, 20).build());
    }

    private void open(Screen screen) {
        if (this.minecraft != null) Compat.setScreen(this.minecraft, screen);
    }

    /**
     * Hook for the image-background pass. Currently a dark overlay so the
     * menu reads on any panorama; replaced with a texture blit once the
     * NativeImage upload is pinned to a game version. Runs once per frame
     * right after the vanilla background (drawing it again in
     * {@link #drawOverlay} would blur twice, which the GUI render state
     * rejects).
     */
    @Override
    protected void drawBackgroundOverlay(Canvas canvas) {
        canvas.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    protected void drawOverlay(Canvas canvas, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        canvas.centeredText(Component.literal("DUSK"), cx, this.height / 4 - 28, GOLD);
        canvas.centeredText(Component.literal("dusklauncher  ·  " + mcVersion()), cx, this.height / 4 - 12, 0xFF8B98A5);

        if (DuskConfig.get().showAccountTile && this.minecraft != null) {
            String name = this.minecraft.getUser().getName();
            int x0 = this.width - canvas.textWidth(name) - 24;
            canvas.fill(x0 - 8, 8, this.width - 8, 30, 0xB0101012);
            canvas.text(Component.literal(name), x0, 15, WHITE);
        }
    }

    private String mcVersion() {
        return this.minecraft != null ? this.minecraft.getLaunchedVersion() : "minecraft";
    }
}
