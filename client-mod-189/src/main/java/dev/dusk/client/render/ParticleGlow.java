package dev.dusk.client.render;

import dev.dusk.client.modules.render.Particles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Set;

/**
 * The halo behind glowing particles: after the particle pass, each glowing
 * particle gets a camera-facing soft disc in its own colour, a few times its
 * size, added onto the scene (additive blend, no depth write) so it reads as
 * light rather than paint.
 */
public final class ParticleGlow {
    private static final int TEX = 64;
    /** Enough for a busy fight; past this the rest go without a halo. */
    private static final int MAX_HALOS = 3000;

    private static ResourceLocation texture;

    private ParticleGlow() {}

    /** Called at the end of EffectRenderer.renderParticles, lightmap on, camera set up. */
    public static void render(float partialTicks) {
        Set<EntityFX> particles = Particles.glowing();
        if (particles.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        float strength = Particles.glowStrength();
        float size = Particles.glowSize();
        // The camera's right and up vectors, as vanilla's particle billboards use them.
        float rx = ActiveRenderInfo.getRotationX(), rz = ActiveRenderInfo.getRotationZ();
        float ux = ActiveRenderInfo.getRotationYZ(), uy = ActiveRenderInfo.getRotationXZ(), uz = ActiveRenderInfo.getRotationXY();

        mc.getTextureManager().bindTexture(texture(mc));
        mc.entityRenderer.disableLightmap();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        int drawn = 0;
        for (EntityFX fx : particles) {
            if (fx.isDead) continue;
            if (++drawn > MAX_HALOS) break;
            float r = fx.getRedColorF(), g = fx.getGreenColorF(), b = fx.getBlueColorF();
            float max = Math.max(r, Math.max(g, b));
            if (max > 0.001f) {
                r /= max;
                g /= max;
                b /= max;
            } else {
                r = g = b = 1f;
            }
            float a = Math.min(1f, fx.getAlpha() * strength);
            if (a <= 0.003f) continue;
            float s = 0.1f * ((ParticleAge) fx).dusk$scale() * size;
            double x = fx.prevPosX + (fx.posX - fx.prevPosX) * partialTicks - EntityFX.interpPosX;
            double y = fx.prevPosY + (fx.posY - fx.prevPosY) * partialTicks - EntityFX.interpPosY;
            double z = fx.prevPosZ + (fx.posZ - fx.prevPosZ) * partialTicks - EntityFX.interpPosZ;
            float sx = rx * s, sz = rz * s, tx = ux * s, ty = uy * s, tz = uz * s;
            wr.pos(x - sx - tx, y - ty, z - sz - tz).tex(1, 1).color(r, g, b, a).endVertex();
            wr.pos(x - sx + tx, y + ty, z - sz + tz).tex(1, 0).color(r, g, b, a).endVertex();
            wr.pos(x + sx + tx, y + ty, z + sz + tz).tex(0, 0).color(r, g, b, a).endVertex();
            wr.pos(x + sx - tx, y - ty, z + sz - tz).tex(0, 1).color(r, g, b, a).endVertex();
        }
        tessellator.draw();

        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        mc.entityRenderer.enableLightmap();
    }

    /** A white disc with a soft gaussian falloff, made once and filtered smoothly. */
    private static ResourceLocation texture(Minecraft mc) {
        if (texture != null) return texture;
        DynamicTexture tex = new DynamicTexture(TEX, TEX);
        int[] data = tex.getTextureData();
        float half = TEX / 2f;
        for (int py = 0; py < TEX; py++) {
            for (int px = 0; px < TEX; px++) {
                float dx = (px + 0.5f - half) / half, dy = (py + 0.5f - half) / half;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float edge = d >= 1f ? 0f : d <= 0.75f ? 1f : 1f - smooth((d - 0.75f) / 0.25f);
                float alpha = (float) Math.exp(-d * d * 4.5) * edge;
                data[py * TEX + px] = Math.round(alpha * 255f) << 24 | 0xFFFFFF;
            }
        }
        tex.updateDynamicTexture();
        texture = mc.getTextureManager().getDynamicTextureLocation("duskclient_particle_glow", tex);
        mc.getTextureManager().bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        return texture;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }
}
