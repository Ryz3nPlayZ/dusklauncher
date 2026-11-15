package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

/**
 * Combat hitboxes (simplified for 1.8.9): replaces the F3+B debug box with
 * one that's always on — target and hurt colours, the eye-height slab and the
 * look line, and a line width that thins with distance. The width switches
 * past a distance, and an outline can sit behind the box. Items, fireworks
 * and arrows can be left out. Same settings as the modern module, so a
 * copied config loads.
 */
public class Hitbox extends Module {
    /** Vanilla's stroke width. */
    public static final float VANILLA_WIDTH = 2.5f;

    private static Hitbox instance;

    private final ColorSetting boxColor = add(new ColorSetting("color", "Box colour", 0xFFFFFFFF));
    private final BoolSetting changeTargetColor = add(new BoolSetting("targetColor", "Colour targeted entity", true));
    private final ColorSetting targetColor = add(new ColorSetting("targetBoxColor", "Target colour", 0xFFFF0000));
    private final BoolSetting hurtColor = add(new BoolSetting("hurtColor", "Colour hurt entities", false));
    private final ColorSetting hurtBoxColor = add(new ColorSetting("hurtBoxColor", "Hurt colour", 0xFFFF00FF));
    private final BoolSetting eyeHeight = add(new BoolSetting("eyeHeight", "Eye height", true));
    private final ColorSetting eyeColor = add(new ColorSetting("eyeColor", "Eye height colour", 0xFFFF0000));
    private final BoolSetting lookDir = add(new BoolSetting("lookDir", "Look direction", true));
    private final BoolSetting lookLine = add(new BoolSetting("lookLine", "Look direction as line", true));
    private final ColorSetting lookColor = add(new ColorSetting("lookColor", "Look direction colour", 0xFF0000FF));
    private final IntSetting width = add(new IntSetting("lineScale", "Line width", 100, 20, 400, 10, "%"));
    private final IntSetting farDistance = add(new IntSetting("farDistance", "Far width from", 32, 1, 128, 1, " blocks"));
    private final IntSetting farWidth = add(new IntSetting("farLineScale", "Far line width", 100, 20, 400, 10, "%"));
    private final BoolSetting outline = add(new BoolSetting("outline", "Outline", false));
    private final ColorSetting outlineColor = add(new ColorSetting("outlineColor", "Outline colour", 0xFF000000));
    private final IntSetting outlineScale = add(new IntSetting("outlineScale", "Outline width", 200, 100, 500, 10, "%"));
    private final BoolSetting hideArrows = add(new BoolSetting("hideArrows", "Hide arrow hitboxes", false));
    private final BoolSetting hideFireworks = add(new BoolSetting("hideFireworks", "Hide firework hitboxes", false));
    private final BoolSetting hideItems = add(new BoolSetting("hideItems", "Hide item hitboxes", false));

    public Hitbox() {
        super("hitbox", "Hitboxes", Category.RENDER,
                "Combat hitboxes: target/hurt colours, eye height, look direction.");
        instance = this;
    }

    public static Hitbox instance() {
        return instance;
    }

    public static boolean active() {
        Hitbox m = instance;
        return m != null && m.enabled();
    }

    /** Whether our box replaces the vanilla debug one, given F3+B's own state. */
    public static boolean showing(boolean vanillaDebug) {
        return vanillaDebug || active();
    }

    /** True when this entity's box shouldn't be drawn at all. */
    public boolean skipped(Entity entity) {
        if (entity instanceof EntityItem) return hideItems.get();
        if (entity instanceof EntityFireworkRocket) return hideFireworks.get();
        if (entity instanceof EntityArrow) return hideArrows.get();
        return false;
    }

    /** The box colour for this entity: target and hurt override the base. */
    public int boxColor(Entity entity, Minecraft mc) {
        if (changeTargetColor.get() && entity == mc.pointedEntity) return targetColor.argb();
        if (hurtColor.get() && entity instanceof EntityLivingBase
                && ((EntityLivingBase) entity).hurtTime > 0) return hurtBoxColor.argb();
        return boxColor.argb();
    }

    /**
     * The box itself, standing in for the F3+B debug one: vanilla's shape
     * (bounding box, eye slab, two-block look line) with our colours and
     * widths. Called in renderDebugBoundingBox's place, in its GL state.
     */
    public static void draw(Minecraft mc, Entity entity, double x, double y, double z, float partialTicks) {
        Hitbox h = instance;
        if (h == null || h.skipped(entity)) return;
        double distance = mc.thePlayer == null ? 0 : mc.thePlayer.getDistanceToEntity(entity);

        GlStateManager.depthMask(false);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.disableBlend();

        AxisAlignedBB world = entity.getEntityBoundingBox();
        AxisAlignedBB box = new AxisAlignedBB(
                world.minX - entity.posX + x, world.minY - entity.posY + y, world.minZ - entity.posZ + z,
                world.maxX - entity.posX + x, world.maxY - entity.posY + y, world.maxZ - entity.posZ + z);

        if (h.drawOutline()) {
            GL11.glLineWidth(h.outlineWidth(distance));
            drawBox(box, h.outlineBoxColor());
        }
        GL11.glLineWidth(h.lineWidth(distance));
        drawBox(box, h.boxColor(entity, mc));

        if (h.drawEyeHeight() && entity instanceof EntityLivingBase) {
            float half = entity.width / 2.0F;
            double eye = y + entity.getEyeHeight();
            AxisAlignedBB slab = new AxisAlignedBB(x - half, eye - 0.01, z - half, x + half, eye + 0.01, z + half);
            drawBox(slab, h.eyeBoxColor());
        }

        if (h.drawLookDir()) {
            Vec3 look = entity.getLook(partialTicks);
            double eye = y + entity.getEyeHeight();
            double ex = x + look.xCoord * 2.0, ey = eye + look.yCoord * 2.0, ez = z + look.zCoord * 2.0;
            int argb = h.lookBoxColor();
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            if (h.lookAsLine()) {
                wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
                wr.pos(x, eye, z).color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24).endVertex();
                wr.pos(ex, ey, ez).color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24).endVertex();
                tessellator.draw();
            } else {
                GL11.glPointSize(4.0f);
                wr.begin(GL11.GL_POINTS, DefaultVertexFormats.POSITION_COLOR);
                wr.pos(ex, ey, ez).color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24).endVertex();
                tessellator.draw();
            }
        }

        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
    }

    private static void drawBox(AxisAlignedBB box, int argb) {
        RenderGlobal.drawOutlinedBoundingBox(box, argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24);
    }

    public boolean drawEyeHeight() { return eyeHeight.get(); }
    public int eyeBoxColor() { return eyeColor.argb(); }
    public boolean drawLookDir() { return lookDir.get(); }
    public boolean lookAsLine() { return lookLine.get(); }
    public int lookBoxColor() { return lookColor.argb(); }
    public boolean drawOutline() { return outline.get(); }
    public int outlineBoxColor() { return outlineColor.argb(); }

    /** Stroke width for an entity this far from the player. */
    public float lineWidth(double distance) {
        IntSetting scale = distance > farDistance.get() ? farWidth : width;
        return VANILLA_WIDTH * scale.get() / 100f;
    }

    /** Outline stroke width: the box width times the outline scale. */
    public float outlineWidth(double distance) {
        return lineWidth(distance) * outlineScale.get() / 100f;
    }
}
