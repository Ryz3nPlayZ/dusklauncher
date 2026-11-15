package dev.dusk.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The modern look's type on 1.8.9: Plus Jakarta Sans, cut the same way the
 * 1.21.11 build's font files cut it (9.4 GUI pixels tall, rasterised at q/4
 * window pixels per GUI pixel, a 2.5 wide space) so text measures and sits
 * the same. 1.8.9 has no TrueType fonts, so the glyphs are drawn with AWT
 * into atlas textures here and drawn as tinted quads. Bold runs take the bold
 * cut; characters neither cut has fall back to the icon font, then to the
 * Minecraft font.
 */
public final class Sans {
    private Sans() {}

    static final int MIN_Q = 2, MAX_Q = 48;
    private static final float SIZE = 9.4f, SPACE = 2.5f;
    /** GUI pixels from the top of a line to the baseline, as the Minecraft font sits. */
    static final float BASELINE = 7f;
    private static final int PAGE = 512;

    private static Font regular, bold, icons;
    private static boolean loaded, ok;
    private static final Cut[][] CUTS = new Cut[2][MAX_Q + 1];

    public static boolean supported() {
        load();
        return ok;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        regular = font("ui.ttf");
        bold = font("ui_bold.ttf");
        icons = font("icons.ttf");
        if (bold == null) bold = regular;
        ok = regular != null;
    }

    private static Font font(String file) {
        try (InputStream in = Minecraft.getMinecraft().getResourceManager()
                .getResource(new ResourceLocation("duskclient", "font/" + file)).getInputStream()) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The quarter-pixel step for text drawn {@code e} window pixels to the GUI pixel. */
    static int step(double e) {
        return Math.max(MIN_Q, Math.min(MAX_Q, (int) Math.round(e * 4)));
    }

    static Cut cut(boolean b, int q) {
        load();
        Cut c = CUTS[b ? 1 : 0][q];
        if (c == null) {
            c = new Cut(b ? bold : regular, q);
            CUTS[b ? 1 : 0][q] = c;
        }
        return c;
    }

    /** One glyph: where it is in its page and how it sits, in window pixels at its cut. */
    static final class Glyph {
        Page page;
        float u0, v0, u1, v1;
        int w, h;
        /** Top-left of the bitmap from the pen position on the baseline. */
        int left, top;
        float advance;
        /** No bitmap of ours: draw this character in the Minecraft font. */
        boolean vanilla;
    }

    static final class Page {
        final DynamicTexture tex = new DynamicTexture(PAGE, PAGE);
        int x = 1, y = 1, row;
        boolean dirty;

        boolean fits(int w, int h) {
            if (x + w + 1 > PAGE) {
                x = 1;
                y += row + 1;
                row = 0;
            }
            return y + h + 1 <= PAGE && w + 2 <= PAGE;
        }
    }

    /** One face at one size: its glyphs, rasterised as they are first drawn. */
    static final class Cut {
        final int q;
        /** Window pixels per GUI pixel this cut is rasterised for. */
        final float os;
        private final Font face, icon;
        private final Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>();
        private final List<Page> pages = new ArrayList<Page>();
        private final FontRenderContext frc = new FontRenderContext(null, true, true);

        Cut(Font base, int q) {
            this.q = q;
            this.os = q / 4f;
            this.face = sized(base, SIZE * os);
            this.icon = icons != null ? sized(icons, SIZE * os) : null;
        }

        /** {@code base} sized like stb_truetype's ScaleForPixelHeight: ascent plus descent is {@code px}. */
        private Font sized(Font base, float px) {
            Font probe = base.deriveFont(100f);
            LineMetrics lm = probe.getLineMetrics("Hg", frc);
            float span = lm.getAscent() + lm.getDescent();
            return base.deriveFont(span > 0 ? 100f * px / span : px);
        }

        Glyph glyph(char ch) {
            Glyph g = glyphs.get(ch);
            if (g == null) {
                g = make(ch);
                glyphs.put(ch, g);
            }
            return g;
        }

        private Glyph make(char ch) {
            Glyph g = new Glyph();
            if (ch == ' ') {
                g.advance = SPACE * os;
                return g;
            }
            Font f = face.canDisplay(ch) ? face : icon != null && icon.canDisplay(ch) ? icon : null;
            if (f == null) {
                g.vanilla = true;
                g.advance = Minecraft.getMinecraft().fontRendererObj.getCharWidth(ch) * os;
                return g;
            }
            GlyphVector gv = f.createGlyphVector(frc, String.valueOf(ch));
            g.advance = gv.getGlyphMetrics(0).getAdvance();
            Rectangle pb = gv.getPixelBounds(frc, 0, 0);
            if (pb.width <= 0 || pb.height <= 0) return g;
            int w = pb.width + 2, h = pb.height + 2;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(java.awt.Color.WHITE);
            g2.drawGlyphVector(gv, 1 - pb.x, 1 - pb.y);
            g2.dispose();
            Page page = pages.isEmpty() ? null : pages.get(pages.size() - 1);
            if (page == null || !page.fits(w, h)) {
                page = new Page();
                pages.add(page);
                if (!page.fits(w, h)) return g;
            }
            int[] data = page.tex.getTextureData();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            for (int yy = 0; yy < h; yy++) {
                System.arraycopy(px, yy * w, data, (page.y + yy) * PAGE + page.x, w);
            }
            page.dirty = true;
            g.page = page;
            g.w = w;
            g.h = h;
            g.left = pb.x - 1;
            g.top = pb.y - 1;
            g.u0 = page.x / (float) PAGE;
            g.v0 = page.y / (float) PAGE;
            g.u1 = (page.x + w) / (float) PAGE;
            g.v1 = (page.y + h) / (float) PAGE;
            page.x += w + 1;
            page.row = Math.max(page.row, h);
            return g;
        }

        /** {@code s}'s advance in GUI pixels. */
        float width(String s) {
            float w = 0;
            for (int i = 0; i < s.length(); i++) w += glyph(s.charAt(i)).advance;
            return w / os;
        }

        /**
         * Draws {@code s} with its line top at x,y (GUI pixels, current GL
         * transform) in {@code argb}; returns the advance in GUI pixels.
         */
        float draw(String s, float x, float y, int argb) {
            float a = (argb >>> 24) / 255f, r = (argb >> 16 & 255) / 255f, gr = (argb >> 8 & 255) / 255f, b = (argb & 255) / 255f;
            float pen = 0, base = y + BASELINE;
            float inv = 1f / os;
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.enableTexture2D();
            GlStateManager.disableAlpha();
            Page bound = null;
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            boolean open = false;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                Glyph g = glyph(ch);
                if (g.vanilla) {
                    if (open) { tess.draw(); open = false; }
                    bound = null;
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(x + pen * inv, y, 0);
                    Minecraft.getMinecraft().fontRendererObj.drawString(String.valueOf(ch), 0, 0, argb, false);
                    GlStateManager.popMatrix();
                    GlStateManager.enableBlend();
                    GlStateManager.disableAlpha();
                } else if (g.page != null) {
                    if (g.page != bound) {
                        if (open) { tess.draw(); open = false; }
                        if (g.page.dirty) {
                            g.page.tex.updateDynamicTexture();
                            g.page.dirty = false;
                        }
                        GlStateManager.bindTexture(g.page.tex.getGlTextureId());
                        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                        bound = g.page;
                    }
                    if (!open) {
                        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                        open = true;
                    }
                    float x0 = x + (pen + g.left) * inv, y0 = base + g.top * inv;
                    float x1 = x0 + g.w * inv, y1 = y0 + g.h * inv;
                    wr.pos(x0, y1, 0).tex(g.u0, g.v1).color(r, gr, b, a).endVertex();
                    wr.pos(x1, y1, 0).tex(g.u1, g.v1).color(r, gr, b, a).endVertex();
                    wr.pos(x1, y0, 0).tex(g.u1, g.v0).color(r, gr, b, a).endVertex();
                    wr.pos(x0, y0, 0).tex(g.u0, g.v0).color(r, gr, b, a).endVertex();
                }
                pen += g.advance;
            }
            if (open) tess.draw();
            GlStateManager.enableAlpha();
            GlStateManager.color(1, 1, 1, 1);
            return pen * inv;
        }
    }
}
