package dev.dusk.client.gui;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** {@link Canvas} over GuiGraphicsExtractor (26.2). */
public record GraphicsCanvas(GuiGraphicsExtractor g, Font font) implements Canvas {
    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        g.fill(x0, y0, x1, y1, argb);
    }

    @Override
    public void fillGradient(int x0, int y0, int x1, int y1, int argbTop, int argbBottom) {
        g.fillGradient(x0, y0, x1, y1, argbTop, argbBottom);
    }

    @Override
    public void outline(int x, int y, int w, int h, int argb) {
        g.outline(x, y, w, h, argb);
    }

    @Override
    public void text(Component text, int x, int y, int argb) {
        text(text, x, y, argb, true);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        if (Theme.sansOn()) text(Component.literal(text), x, y, argb, shadow);
        else g.text(font, text, x, y, argb, shadow);
    }

    @Override
    public void text(Component text, int x, int y, int argb, boolean shadow) {
        // the sans cut has no pixel shadow to drop; on the HUD it gets a soft one
        if (Theme.sansOn()) {
            Component s = sans(text);
            if (shadow && Theme.sansShadow()) {
                push();
                translate(0.5f, 0.5f);
                g.text(font, s, x, y, Theme.sansShadowColor(argb), false);
                pop();
            }
            g.text(font, s, x, y, argb, false);
        } else g.text(font, text, x, y, argb, shadow);
    }

    @Override
    public int textWidth(Component text) {
        return font.width(Theme.sansOn() ? sans(text) : text);
    }

    @Override
    public void centeredText(Component text, int x, int y, int argb) {
        text(text, x - textWidth(text) / 2, y, argb, true);
    }

    @Override
    public void centeredText(String text, int x, int y, int argb, boolean shadow) {
        text(text, x - textWidth(text) / 2, y, argb, shadow);
    }

    @Override
    public int textWidth(String text) {
        return Theme.sansOn() ? font.width(sans(Component.literal(text))) : font.width(text);
    }

    /** The sans cut for the scale the current transform draws at. */
    private Component sans(Component text) {
        return Sans.wrap(text, (float) Math.hypot(g.pose().m00(), g.pose().m01()));
    }

    @Override
    public int lineHeight() {
        return font.lineHeight;
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        g.item(stack, x, y);
    }

    @Override
    public void itemDecorations(ItemStack stack, int x, int y) {
        g.itemDecorations(font, stack, x, y);
    }

    @Override
    public void blit(String texture, int x, int y, float u, float v, int w, int h, int texW, int texH, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, Identifier.parse(texture), x, y, u, v, w, h, texW, texH, argb);
    }

    @Override
    public void effectIcon(Holder<MobEffect> effect, int x, int y, int size) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(effect), x, y, size, size);
    }

    @Override
    public void push() {
        g.pose().pushMatrix();
    }

    @Override
    public void pop() {
        g.pose().popMatrix();
    }

    @Override
    public void translate(float x, float y) {
        g.pose().translate(x, y);
    }

    @Override
    public void rotate(float radians) {
        g.pose().rotate(radians);
    }

    @Override
    public void scale(float x, float y) {
        g.pose().scale(x, y);
    }

    @Override
    public void scissor(int x0, int y0, int x1, int y1) {
        g.enableScissor(x0, y0, x1, y1);
    }

    @Override
    public void unscissor() {
        g.disableScissor();
    }

    @Override
    public void beginLayer() {
        g.nextStratum();
    }

    @Override
    public void entity(LivingEntity e, int x0, int y0, int x1, int y1, int size, float mouseX, float mouseY) {
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, x0, y0, x1, y1, size, 0.0625f, mouseX, mouseY, e);
    }
}
