package dev.dusk.client.gui;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import net.minecraft.client.Minecraft;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** {@link Canvas} over GuiGraphics (1.21.4–1.21.5: PoseStack pose). */
public record GraphicsCanvas(GuiGraphics g, Font font) implements Canvas {
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
        g.renderOutline(x, y, w, h, argb);
    }

    @Override
    public void text(Component text, int x, int y, int argb) {
        g.drawString(font, text, x, y, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        g.drawString(font, text, x, y, argb, shadow);
    }

    @Override
    public void text(Component text, int x, int y, int argb, boolean shadow) {
        g.drawString(font, text, x, y, argb, shadow);
    }

    @Override
    public int textWidth(Component text) {
        return font.width(text);
    }

    @Override
    public void centeredText(Component text, int x, int y, int argb) {
        g.drawCenteredString(font, text, x, y, argb);
    }

    @Override
    public void centeredText(String text, int x, int y, int argb, boolean shadow) {
        g.drawString(font, text, x - font.width(text) / 2, y, argb, shadow);
    }

    @Override
    public int textWidth(String text) {
        return font.width(text);
    }

    @Override
    public int lineHeight() {
        return font.lineHeight;
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        g.renderItem(stack, x, y);
    }

    @Override
    public void itemDecorations(ItemStack stack, int x, int y) {
        g.renderItemDecorations(font, stack, x, y);
    }

    @Override
    public void blit(String texture, int x, int y, float u, float v, int w, int h, int texW, int texH, int argb) {
        // 1.21.1 has no tinted blit; the shader colour stands in for the tint
        if (argb == 0xFFFFFFFF) {
            g.blit(ResourceLocation.parse(texture), x, y, u, v, w, h, texW, texH);
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor((argb >> 16 & 0xFF) / 255F, (argb >> 8 & 0xFF) / 255F, (argb & 0xFF) / 255F, (argb >>> 24) / 255F);
        g.blit(ResourceLocation.parse(texture), x, y, u, v, w, h, texW, texH);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
    }

    @Override
    public void effectIcon(Holder<MobEffect> effect, int x, int y, int size) {
        g.blit(x, y, 0, size, size, Minecraft.getInstance().getMobEffectTextures().get(effect));
    }

    @Override
    public void push() {
        g.pose().pushPose();
    }

    @Override
    public void pop() {
        g.pose().popPose();
    }

    @Override
    public void translate(float x, float y) {
        g.pose().translate(x, y, 0);
    }

    @Override
    public void rotate(float radians) {
        g.pose().mulPose(Axis.ZP.rotation(radians));
    }

    @Override
    public void scale(float x, float y) {
        g.pose().scale(x, y, 1);
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
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
    }

    @Override
    public void endLayer() {
        g.pose().popPose();
    }

    @Override
    public void entity(LivingEntity e, int x0, int y0, int x1, int y1, int size, float mouseX, float mouseY) {
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, x0, y0, x1, y1, size, 0.0625f, mouseX, mouseY, e);
    }
}
