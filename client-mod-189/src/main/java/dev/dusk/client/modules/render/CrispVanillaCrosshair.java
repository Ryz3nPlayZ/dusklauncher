package dev.dusk.client.modules.render;

import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.mixin.GuiIngameAccessor;
import dev.dusk.client.util.DisplaySnap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * The vanilla or texture-pack crosshair (the 16x16 corner of icons.png),
 * drawn the way Forge draws it but sized and placed so each of the pack's
 * pixels covers a whole number of real screen pixels after macOS scales the
 * frame. Only with "Crisp crosshair on scaled screens" on, and only when
 * the ratio is known; otherwise vanilla draws as usual.
 */
public final class CrispVanillaCrosshair {
    private CrispVanillaCrosshair() {}

    /** True when it drew, and vanilla's should be cancelled. */
    public static boolean draw(Minecraft mc, ScaledResolution res) {
        if (!DuskConfig.get().crispCrosshair) return false;
        DisplaySnap.ensureStarted();
        if (!DisplaySnap.known()) return false;
        if (mc.ingameGUI == null || !((GuiIngameAccessor) mc.ingameGUI).dusk$showCrosshair()) return false;
        double rx = DisplaySnap.ratioX(), ry = DisplaySnap.ratioY();
        if (Math.abs(rx - 1) < 1e-6 && Math.abs(ry - 1) < 1e-6) return false; // nothing to fix

        int g = res.getScaleFactor();
        mc.getTextureManager().bindTexture(Gui.icons);
        int texW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        if (texW <= 0) return false;
        // the pack's pixels across the 16-unit crosshair cell (16 for vanilla, 32 for a 2x pack...)
        int n = Math.max(1, Math.round(16f * texW / 256f));
        double want = 16.0 * g / n;
        if (want < 1) return false; // the pack has more detail than the screen; nothing to snap
        int k = DisplaySnap.texel(want, rx);
        int x0 = DisplaySnap.edge((res.getScaledWidth() / 2 - 7) * (double) g, rx);
        int y0 = DisplaySnap.edge((res.getScaledHeight() / 2 - 7) * (double) g, ry);
        float x1 = x0 / (float) g, y1 = y0 / (float) g;
        float x2 = (x0 + n * k) / (float) g, y2 = (y0 + n * k) / (float) g;
        float uv = 16f / 256f;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE_MINUS_SRC_COLOR, 1, 0);
        GlStateManager.enableAlpha();
        GlStateManager.color(1, 1, 1, 1);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        w.pos(x1, y2, 0).tex(0, uv).endVertex();
        w.pos(x2, y2, 0).tex(uv, uv).endVertex();
        w.pos(x2, y1, 0).tex(uv, 0).endVertex();
        w.pos(x1, y1, 0).tex(0, 0).endVertex();
        t.draw();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.disableBlend();
        return true;
    }
}
