package dev.dusk.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The player standing in a box, turned by dragging: 1.8.9's stand-in for
 * the PlayerSkinWidget the modern title screen and wardrobe use. The model
 * fills 97% of the box's height; dragging sideways spins it, dragging up
 * and down tilts it (within 50 degrees), 2.5 degrees a GUI pixel, as
 * vanilla's widget does. There is no entity behind it, so the parts are
 * posed and drawn by hand.
 */
public final class PlayerModelView {
    private static final float UNIT = 0.0625f;
    private static ModelPlayer wide, slim;

    public int x, y, w, h;
    private float pitch = 5f, yaw = 30f;
    private boolean dragging;
    private double lastX, lastY;

    public PlayerModelView(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    /** Starts a drag when the press lands on the box. */
    public boolean press(double mx, double my) {
        if (!contains(mx, my)) return false;
        dragging = true;
        lastX = mx;
        lastY = my;
        return true;
    }

    public boolean drag(double mx, double my) {
        if (!dragging) return false;
        yaw += (float) (mx - lastX) * 2.5f;
        pitch = Math.max(-50f, Math.min(50f, pitch + (float) (my - lastY) * 2.5f));
        lastX = mx;
        lastY = my;
        return true;
    }

    public boolean release() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    /**
     * Draws the model with {@code skin} (a texture id). The depth buffer is
     * cleared after, so later GUI drawing never hides behind the model.
     * {@code cape} (a texture, or null) hangs off the back at rest.
     */
    public void draw(Minecraft mc, String skin, boolean slimArms, ResourceLocation cape) {
        ModelPlayer model = model(slimArms);
        float s = 0.97f * h / 2.125f;

        mc.getTextureManager().bindTexture(new ResourceLocation(skin));
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.disableCull();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        RenderHelper.enableGUIStandardItemLighting();

        GlStateManager.pushMatrix();
        GlStateManager.translate(x + w / 2f, y + h / 2f, 150f);
        // model space has y down like the GUI but faces -z: flipping z turns
        // it to the viewer and undoes the GUI's mirror
        GlStateManager.scale(s, s, -s);
        GlStateManager.rotate(pitch, 1f, 0f, 0f);
        GlStateManager.rotate(-yaw, 0f, 1f, 0f);
        // the model runs from the hat (y -0.5) to the feet (1.5): centre it
        GlStateManager.translate(0f, -0.5f, 0f);
        model.bipedHead.render(UNIT);
        model.bipedBody.render(UNIT);
        model.bipedRightArm.render(UNIT);
        model.bipedLeftArm.render(UNIT);
        model.bipedRightLeg.render(UNIT);
        model.bipedLeftLeg.render(UNIT);
        model.bipedHeadwear.render(UNIT);
        model.bipedBodyWear.render(UNIT);
        model.bipedRightArmwear.render(UNIT);
        model.bipedLeftArmwear.render(UNIT);
        model.bipedRightLegwear.render(UNIT);
        model.bipedLeftLegwear.render(UNIT);
        if (cape != null) {
            // LayerCape's transform for a player standing still
            mc.getTextureManager().bindTexture(cape);
            GlStateManager.pushMatrix();
            GlStateManager.translate(0f, 0f, 0.125f);
            GlStateManager.rotate(6f, 1f, 0f, 0f);
            GlStateManager.rotate(180f, 0f, 1f, 0f);
            model.renderCape(UNIT);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.enableCull();
        GlStateManager.disableDepth();
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** A model at rest: everything straight, the arms 0.1 rad out (vanilla's idle bob at tick 0). */
    private static ModelPlayer model(boolean slimArms) {
        ModelPlayer m = slimArms ? slim : wide;
        if (m == null) {
            m = new ModelPlayer(0f, slimArms);
            m.isChild = false;
            m.bipedRightArm.rotateAngleZ = 0.1f;
            m.bipedLeftArm.rotateAngleZ = -0.1f;
            ModelBase.copyModelAngles(m.bipedHead, m.bipedHeadwear);
            ModelBase.copyModelAngles(m.bipedBody, m.bipedBodyWear);
            ModelBase.copyModelAngles(m.bipedRightArm, m.bipedRightArmwear);
            ModelBase.copyModelAngles(m.bipedLeftArm, m.bipedLeftArmwear);
            ModelBase.copyModelAngles(m.bipedRightLeg, m.bipedRightLegwear);
            ModelBase.copyModelAngles(m.bipedLeftLeg, m.bipedLeftLegwear);
            if (slimArms) slim = m;
            else wide = m;
        }
        return m;
    }
}
