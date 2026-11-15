package dev.dusk.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** The few drawing calls HUD code needs, over 1.8.9's GL state. */
public final class Canvas {
    private final Minecraft mc;

    public Canvas(Minecraft mc) { this.mc = mc; }

    public Minecraft mc() { return mc; }

    private int depth;

    public void push() { GlStateManager.pushMatrix(); depth++; }
    public void pop() { GlStateManager.popMatrix(); depth--; }
    public int depth() { return depth; }
    /** Unwinds pushes left open by a draw that threw. */
    public void popTo(int target) { while (depth > target) pop(); }
    public void translate(float x, float y) { GlStateManager.translate(x, y, 0); }
    public void scale(float x, float y) { GlStateManager.scale(x, y, 1); }

    public void fill(int x1, int y1, int x2, int y2, int argb) {
        dev.dusk.client.gui.GraphicsCanvas.rect(x1, y1, x2, y2, argb);
    }

    public void fill(float x1, float y1, float x2, float y2, int argb) {
        dev.dusk.client.gui.GraphicsCanvas.rect(x1, y1, x2, y2, argb);
    }

    public void outline(int x1, int y1, int x2, int y2, int argb) {
        fill(x1, y1, x2, y1 + 1, argb);
        fill(x1, y2 - 1, x2, y2, argb);
        fill(x1, y1 + 1, x1 + 1, y2 - 1, argb);
        fill(x2 - 1, y1 + 1, x2, y2 - 1, argb);
    }

    public void text(String s, float x, float y, int argb, boolean shadow) {
        if ((argb >>> 24) < 4) return;
        mc.fontRendererObj.drawString(s, x, y, argb, shadow);
        GlStateManager.color(1, 1, 1, 1);
    }

    public void centeredText(String s, float cx, float y, int argb, boolean shadow) {
        text(s, cx - mc.fontRendererObj.getStringWidth(s) / 2f, y, argb, shadow);
    }

    public void item(ItemStack stack, int x, int y) {
        if (stack == null) return;
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.color(1, 1, 1, 1);
    }

    /** The stack-size number and durability bar on an item, as in the hotbar. */
    public void itemOverlay(ItemStack stack, int x, int y, String text) {
        if (stack == null) return;
        mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, stack, x, y, text);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    private static final ResourceLocation INVENTORY = new ResourceLocation("textures/gui/container/inventory.png");

    /** A potion's 18 px icon from the inventory sheet, drawn {@code size} px wide. */
    public void effectIcon(net.minecraft.potion.Potion potion, int x, int y, int size) {
        if (potion == null || !potion.hasStatusIcon()) return;
        int index = potion.getStatusIconIndex();
        push();
        translate(x, y);
        scale(size / 18f, size / 18f);
        blit(INVENTORY, 0, 0, index % 8 * 18, 198 + index / 8 * 18, 18, 18, 256, 256);
        pop();
    }

    public void blit(ResourceLocation tex, int x, int y, float u, float v, int w, int h, float texW, float texH) {
        mc.getTextureManager().bindTexture(tex);
        GlStateManager.enableBlend();
        GlStateManager.color(1, 1, 1, 1);
        Gui.drawModalRectWithCustomSizedTexture(x, y, u, v, w, h, texW, texH);
    }
}
