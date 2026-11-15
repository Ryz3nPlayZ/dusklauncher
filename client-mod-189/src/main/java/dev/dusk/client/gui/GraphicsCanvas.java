package dev.dusk.client.gui;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link Canvas} over 1.8.9's fixed-function GL. Keeps its own copy of the 2D
 * transform alongside GL's (for the sans cut and for scissor boxes, which GL
 * takes in window pixels).
 */
public final class GraphicsCanvas implements Canvas {
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
    private static final Map<String, ResourceLocation> TEXTURES = new HashMap<String, ResourceLocation>();
    private static final ResourceLocation INVENTORY = new ResourceLocation("textures/gui/container/inventory.png");

    private final Minecraft mc;
    private final FontRenderer font;
    private final int guiScale;
    /** The GUI-pixel transform: x' = a*x + c*y + tx, y' = b*x + d*y + ty. */
    private float a = 1, b, c, d = 1, tx, ty;
    private final ArrayDeque<float[]> stack = new ArrayDeque<float[]>();
    private final ArrayDeque<int[]> clips = new ArrayDeque<int[]>();

    public GraphicsCanvas(Minecraft mc, FontRenderer font) {
        this.mc = mc;
        this.font = font;
        this.guiScale = Compat.guiScale(mc);
        MATRIX.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        a = MATRIX.get(0);
        b = MATRIX.get(1);
        c = MATRIX.get(4);
        d = MATRIX.get(5);
        tx = MATRIX.get(12);
        ty = MATRIX.get(13);
    }

    /** Pops whatever a draw left pushed or clipped (one that threw half way). */
    public void finish() {
        while (!stack.isEmpty()) pop();
        if (!clips.isEmpty()) {
            clips.clear();
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GlStateManager.color(1, 1, 1, 1);
    }

    // ---- shapes ----------------------------------------------------------------

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        rect(x0, y0, x1, y1, argb);
    }

    /**
     * Gui.drawRect without the alpha test: 1.8.9 leaves GL_GREATER 0.1 on in
     * GUIs, which drops any fill under 10% opacity (the panes' white washes,
     * hairlines), while the rounded corners (drawn with the test off) stayed,
     * so panes showed as four square corners.
     */
    public static void rect(float x0, float y0, float x1, float y1, int argb) {
        if ((argb >>> 24) == 0) return;
        if (x0 > x1) { float t = x0; x0 = x1; x1 = t; }
        if (y0 > y1) { float t = y0; y0 = y1; y1 = t; }
        float a = (argb >>> 24) / 255f, r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, b = (argb & 255) / 255f;
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        w.pos(x0, y1, 0).color(r, g, b, a).endVertex();
        w.pos(x1, y1, 0).color(r, g, b, a).endVertex();
        w.pos(x1, y0, 0).color(r, g, b, a).endVertex();
        w.pos(x0, y0, 0).color(r, g, b, a).endVertex();
        t.draw();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override
    public void fillGradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        if (((top | bottom) >>> 24) == 0) return;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        w.pos(x1, y0, 0).color(top >> 16 & 255, top >> 8 & 255, top & 255, top >>> 24).endVertex();
        w.pos(x0, y0, 0).color(top >> 16 & 255, top >> 8 & 255, top & 255, top >>> 24).endVertex();
        w.pos(x0, y1, 0).color(bottom >> 16 & 255, bottom >> 8 & 255, bottom & 255, bottom >>> 24).endVertex();
        w.pos(x1, y1, 0).color(bottom >> 16 & 255, bottom >> 8 & 255, bottom & 255, bottom >>> 24).endVertex();
        t.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    @Override
    public void outline(int x, int y, int w, int h, int argb) {
        fill(x, y, x + w, y + 1, argb);
        fill(x, y + h - 1, x + w, y + h, argb);
        fill(x, y + 1, x + 1, y + h - 1, argb);
        fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    // ---- text ------------------------------------------------------------------

    private Sans.Cut cut(boolean bold) {
        double scale = Math.sqrt(a * a + b * b);
        return Sans.cut(bold, Sans.step(scale * guiScale));
    }

    private boolean sans() {
        return Theme.sansOn() && Sans.supported();
    }

    @Override
    public void text(Component text, int x, int y, int argb) {
        text(text, x, y, argb, true);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        if (sans()) {
            text(Component.literal(text), x, y, argb, shadow);
            return;
        }
        if ((argb >>> 24) < 4) return;
        font.drawString(text, x, y, argb, shadow);
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override
    public void text(Component text, int x, int y, int argb, boolean shadow) {
        if (sans()) {
            // the sans cut has no pixel shadow to drop; on the HUD it gets a soft one
            if (shadow && Theme.sansShadow()) {
                push();
                translate(0.5f, 0.5f);
                drawSans(text, x, y, Theme.sansShadowColor(argb), true);
                pop();
            }
            drawSans(text, x, y, argb, false);
            return;
        }
        if ((argb >>> 24) < 4) return;
        font.drawString(text.legacy(), x, y, argb, shadow);
        GlStateManager.color(1, 1, 1, 1);
    }

    private void drawSans(Component text, final float x, final float y, final int argb, final boolean shadow) {
        if ((argb >>> 24) < 2) return;
        final float[] pen = {x};
        text.visit(new Component.Visitor() {
            @Override
            public void accept(Component.Run run) {
                if (run.text.isEmpty()) return;
                int color = shadow || run.color == -1 ? argb : (argb & 0xFF000000) | (run.color & 0xFFFFFF);
                Sans.Cut cut = cut(run.bold);
                float start = pen[0];
                if (run.italic) {
                    // a shear about the baseline, as vanilla's italic leans
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(0, y + Sans.BASELINE, 0);
                    GlStateManager.multMatrix(shear());
                    GlStateManager.translate(0, -(y + Sans.BASELINE), 0);
                    pen[0] += cut.draw(run.text, pen[0], y, color);
                    GlStateManager.popMatrix();
                } else {
                    pen[0] += cut.draw(run.text, pen[0], y, color);
                }
                if (run.underline) fillF(start, y + Sans.BASELINE + 1, pen[0], y + Sans.BASELINE + 2, color);
                if (run.strikethrough) fillF(start, y + 4, pen[0], y + 5, color);
            }
        });
    }

    private static FloatBuffer shearBuf;

    private static FloatBuffer shear() {
        if (shearBuf == null) {
            shearBuf = BufferUtils.createFloatBuffer(16);
            shearBuf.put(new float[] {1, 0, 0, 0, -0.2f, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
            shearBuf.flip();
        }
        shearBuf.rewind();
        return shearBuf;
    }

    private void fillF(float x0, float y0, float x1, float y1, int argb) {
        push();
        translate(x0, y0);
        scale((x1 - x0) / 64f, (y1 - y0) / 64f);
        fill(0, 0, 64, 64, argb);
        pop();
    }

    private float sansWidth(Component text) {
        final float[] w = {0};
        text.visit(new Component.Visitor() {
            @Override
            public void accept(Component.Run run) {
                if (!run.text.isEmpty()) w[0] += cut(run.bold).width(run.text);
            }
        });
        return w[0];
    }

    @Override
    public int textWidth(Component text) {
        if (sans()) return (int) Math.ceil(sansWidth(text));
        return font.getStringWidth(text.legacy());
    }

    @Override
    public int textWidth(String text) {
        if (sans()) return (int) Math.ceil(sansWidth(Component.literal(text)));
        return font.getStringWidth(text);
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
    public int lineHeight() {
        return font.FONT_HEIGHT;
    }

    // ---- items and textures ---------------------------------------------------

    @Override
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

    @Override
    public void itemDecorations(ItemStack stack, int x, int y) {
        if (stack == null) return;
        mc.getRenderItem().renderItemOverlayIntoGUI(font, stack, x, y, null);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    private static ResourceLocation texture(String id) {
        ResourceLocation loc = TEXTURES.get(id);
        if (loc == null) {
            loc = new ResourceLocation(id);
            TEXTURES.put(id, loc);
        }
        return loc;
    }

    @Override
    public void blit(String tex, int x, int y, float u, float v, int w, int h, int texW, int texH, int argb) {
        if ((argb >>> 24) == 0) return;
        mc.getTextureManager().bindTexture(texture(tex));
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 771);
        GlStateManager.disableAlpha();
        GlStateManager.color((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        Gui.drawModalRectWithCustomSizedTexture(x, y, u, v, w, h, texW, texH);
        GlStateManager.enableAlpha();
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override
    public void effectIcon(Potion effect, int x, int y, int size) {
        if (effect == null || !effect.hasStatusIcon()) return;
        int i = effect.getStatusIconIndex();
        push();
        translate(x, y);
        scale(size / 18f, size / 18f);
        blit(INVENTORY.toString(), 0, 0, i % 8 * 18, 198 + i / 8 * 18, 18, 18, 256, 256, 0xFFFFFFFF);
        pop();
    }

    @Override
    public void entity(EntityLivingBase e, int x0, int y0, int x1, int y1, int size, float mouseX, float mouseY) {
        if (e == null) return;
        int cx = (x0 + x1) / 2;
        int feet = (y0 + y1) / 2 + size;
        float eyes = feet - e.getEyeHeight() * size;
        GlStateManager.color(1, 1, 1, 1);
        GuiInventory.drawEntityOnScreen(cx, feet, size, cx - mouseX, eyes - mouseY, e);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.color(1, 1, 1, 1);
    }

    // ---- transform -------------------------------------------------------------

    @Override
    public void push() {
        GlStateManager.pushMatrix();
        stack.push(new float[] {a, b, c, d, tx, ty});
    }

    @Override
    public void pop() {
        float[] m = stack.poll();
        if (m == null) return;
        GlStateManager.popMatrix();
        a = m[0];
        b = m[1];
        c = m[2];
        d = m[3];
        tx = m[4];
        ty = m[5];
    }

    @Override
    public void translate(float x, float y) {
        GlStateManager.translate(x, y, 0);
        tx += a * x + c * y;
        ty += b * x + d * y;
    }

    @Override
    public void scale(float x, float y) {
        GlStateManager.scale(x, y, 1);
        a *= x;
        b *= x;
        c *= y;
        d *= y;
    }

    @Override
    public void rotate(float radians) {
        GlStateManager.rotate((float) Math.toDegrees(radians), 0, 0, 1);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
        float na = a * cos + c * sin, nb = b * cos + d * sin;
        float nc = -a * sin + c * cos, nd = -b * sin + d * cos;
        a = na;
        b = nb;
        c = nc;
        d = nd;
    }

    // ---- clipping --------------------------------------------------------------

    @Override
    public void scissor(int x0, int y0, int x1, int y1) {
        float[] xs = {a * x0 + c * y0 + tx, a * x1 + c * y0 + tx, a * x0 + c * y1 + tx, a * x1 + c * y1 + tx};
        float[] ys = {b * x0 + d * y0 + ty, b * x1 + d * y0 + ty, b * x0 + d * y1 + ty, b * x1 + d * y1 + ty};
        float minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        float maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        float minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        float maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
        int[] box = {Math.round(minX * guiScale), Math.round(minY * guiScale),
                Math.round(maxX * guiScale), Math.round(maxY * guiScale)};
        int[] outer = clips.peek();
        if (outer != null) {
            box[0] = Math.max(box[0], outer[0]);
            box[1] = Math.max(box[1], outer[1]);
            box[2] = Math.max(box[0], Math.min(box[2], outer[2]));
            box[3] = Math.max(box[1], Math.min(box[3], outer[3]));
        }
        clips.push(box);
        apply(box);
    }

    @Override
    public void unscissor() {
        clips.poll();
        int[] box = clips.peek();
        if (box == null) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        else apply(box);
    }

    private void apply(int[] box) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(box[0], mc.displayHeight - box[3], Math.max(0, box[2] - box[0]), Math.max(0, box[3] - box[1]));
    }

    @Override
    public void beginLayer() {
        // later draws go over items already drawn, whatever depth those wrote
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
    }
}
