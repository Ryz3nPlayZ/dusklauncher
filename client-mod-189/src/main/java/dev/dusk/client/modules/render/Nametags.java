package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Nametags, written from PolyNametag 1.2.0's option list (behaviour only).
 * Vanilla nametags are drawn in world space, so they shrink into the
 * distance; these are drawn on the screen instead, at a size you pick, with
 * an optional health readout — the two things that matter in bedwars fights.
 * Named mobs and armour stands keep their names; everything honours the F1
 * and inventory rules below. The Dusk badge needs the cosmetics service and
 * arrives with it.
 */
public class Nametags extends Module {
    private static final double MAX_DISTANCE = 64;

    private static Nametags instance;

    private final BoolSetting removeNametags = add(new BoolSetting("removeNametags", "Remove nametags", false), "General");
    private final IntSetting heightOffset = add(new IntSetting("heightOffset", "Height offset", 0, -1000, 1000, 25, " blocks").decimals(2), "General");
    private final IntSetting scale = add(new IntSetting("scale", "Scale", 100, 0, 100, 5, "%"), "General");
    private final BoolSetting textShadow = add(new BoolSetting("textShadow", "Text shadow", false), "General");
    private final BoolSetting showOwnNametag = add(new BoolSetting("showOwnNametag", "Show own nametag", true), "General");
    private final BoolSetting showInInventory = add(new BoolSetting("showInInventory", "Show with a screen open", false), "General");
    private final BoolSetting duskBadge = add(new BoolSetting("duskBadge", "Dusk badge (needs cosmetics)", true), "General");
    private final BoolSetting showHealth = add(new BoolSetting("showHealth", "Show health", false), "General");
    private final BoolSetting hideEntityF1 = add(new BoolSetting("hideEntityF1", "Hide entities in F1", true), "Hidden HUD");
    private final BoolSetting hidePlayerF1 = add(new BoolSetting("hidePlayerF1", "Hide players in F1", true), "Hidden HUD");
    private final BoolSetting hideArmorStandF1 = add(new BoolSetting("hideArmorStandF1", "Hide armour stands in F1", true), "Hidden HUD");
    private final BoolSetting background = add(new BoolSetting("background", "Background", true), "Style");
    private final ColorSetting backgroundColor = add(new ColorSetting("backgroundColor", "Background colour", 0x3F000000), "Style");
    private final ColorSetting textColor = add(new ColorSetting("textColor", "Text colour", 0xFFFFFFFF), "Style");
    private final BoolSetting overrideTextColor = add(new BoolSetting("overrideTextColor", "Override text colour", false), "Style");
    private final BoolSetting rounded = add(new BoolSetting("rounded", "Rounded corners", false), "Style");
    private final IntSetting cornerRadius = add(new IntSetting("cornerRadius", "Corner radius", 3, 0, 10, 1, " px"), "Style");
    private final IntSetting paddingX = add(new IntSetting("paddingX", "Horizontal padding", 0, 0, 10, 1, " px"), "Style");
    private final IntSetting paddingY = add(new IntSetting("paddingY", "Vertical padding", 0, 0, 10, 1, " px"), "Style");

    public Nametags() {
        super("nametags", "Nametags", Category.RENDER,
                "Nametags at a readable size, with an optional health readout.");
        instance = this;
    }

    public static Nametags instance() {
        return instance;
    }

    /** True when vanilla nametag rendering is replaced by this module. */
    public static boolean handling() {
        Nametags m = instance;
        return m != null && m.enabled();
    }

    /** True when no nametag at all should be drawn for this entity. */
    public static boolean removing() {
        Nametags m = instance;
        return m != null && m.enabled() && m.removeNametags.get();
    }

    /** Called from RenderWorldLastEvent, after the motion blur pass, in the world's GL state. */
    public static void renderWorldLast(Minecraft mc, float partialTicks) {
        Nametags m = instance;
        if (m == null || !m.enabled() || m.removeNametags.get() || mc.theWorld == null) return;
        if (!m.showInInventory.get() && mc.currentScreen != null) return;

        List<EntityLivingBase> visible = new ArrayList<EntityLivingBase>();
        for (Entity entity : mc.theWorld.getLoadedEntityList()) {
            if (!(entity instanceof EntityLivingBase) || entity.isDead) continue;
            if (!m.shouldTag(mc, (EntityLivingBase) entity)) continue;
            visible.add((EntityLivingBase) entity);
        }
        if (visible.isEmpty()) return;

        float[] mvp = captureMatrix();
        if (mvp == null) return;

        FontRenderer font = mc.fontRendererObj;
        float s = m.scale.get() / 100f;
        if (s <= 0f) return;
        int displayW = mc.displayWidth, displayH = mc.displayHeight;

        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, displayW, displayH, 0, -100, 100);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();

        for (EntityLivingBase entity : visible) {
            double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
            double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
            double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
            y += entity.height + 0.5 + m.heightOffset.get() / 100.0;
            // camera-relative, the space entity rendering used this frame
            double rx = x - mc.getRenderManager().viewerPosX;
            double ry = y - mc.getRenderManager().viewerPosY;
            double rz = z - mc.getRenderManager().viewerPosZ;
            float[] screen = project(mvp, rx, ry, rz, displayW, displayH);
            if (screen == null) continue;
            m.draw(font, entity, screen[0], screen[1], s);
        }

        GlStateManager.enableDepth();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
    }

    private boolean shouldTag(Minecraft mc, EntityLivingBase entity) {
        if (entity == mc.thePlayer) return showOwnNametag.get();
        double dist = mc.thePlayer.getDistanceSqToEntity(entity);
        if (dist > MAX_DISTANCE * MAX_DISTANCE) return false;
        if (entity.isInvisibleToPlayer(mc.thePlayer)) return false;
        if (mc.gameSettings.hideGUI) {
            if (hideEntityF1.get()) return false;
            if (entity instanceof EntityPlayer && hidePlayerF1.get()) return false;
            if (entity instanceof net.minecraft.entity.item.EntityArmorStand && hideArmorStandF1.get()) return false;
        }
        // players always; other living things only when named, as vanilla
        return entity instanceof EntityPlayer || entity.getAlwaysRenderNameTag() || entity.hasCustomName();
    }

    private void draw(FontRenderer font, EntityLivingBase entity, float x, float y, float s) {
        String name = entity.getDisplayName().getFormattedText();
        int textArgb = textColor.argb();
        if (overrideTextColor.get()) {
            name = EnumChatFormatting.getTextWithoutFormattingCodes(name);
        } else {
            // keep the name's own colours; the setting's text colour only tints white names
            textArgb = 0xFFFFFFFF;
        }
        String health = null;
        if (showHealth.get() && !(entity instanceof net.minecraft.entity.item.EntityArmorStand)) {
            float hp = entity.getHealth();
            health = hp == (long) hp ? String.valueOf((long) hp) : String.valueOf(Math.round(hp * 10f) / 10f);
        }
        int nameW = font.getStringWidth(name);
        int healthW = health == null ? 0 : 2 + font.getStringWidth(health);
        int textW = nameW + healthW;
        int padX = paddingX.get(), padY = paddingY.get();
        float w = (textW + 4 + padX * 2) * s;
        float h = (font.FONT_HEIGHT + 1 + padY * 2) * s;

        if (background.get() && (backgroundColor.argb() >>> 24) > 0) {
            GlStateManager.disableTexture2D();
            GlStateManager.color(1f, 1f, 1f, 1f);
            int bg = backgroundColor.argb();
            if (rounded.get() && cornerRadius.get() > 0) {
                roundedRect(x - w / 2, y, w, h, Math.min(cornerRadius.get(), w / 2), bg);
            } else {
                rect(x - w / 2, y, x + w / 2, y + h, bg);
            }
            GlStateManager.enableTexture2D();
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(s, s, 1);
        font.drawString(name, -textW / 2f, 1 + padY, textArgb, textShadow.get());
        if (health != null) {
            font.drawString(health, -textW / 2f + nameW + 2, 1 + padY, 0xFFFF5555, textShadow.get());
        }
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** One flat quad in the current 2D matrix. */
    private static void rect(float x1, float y1, float x2, float y2, int argb) {
        float a = (argb >>> 24) / 255f, r = (argb >> 16 & 0xFF) / 255f, g = (argb >> 8 & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        GlStateManager.color(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x1, y2);
        GL11.glEnd();
    }

    /** A rounded box: a full-width middle band plus circular-corner strips. */
    private static void roundedRect(float x, float y, float w, float h, float r, int argb) {
        if (r <= 0 || r * 2 > w || r * 2 > h) {
            rect(x, y, x + w, y + h, argb);
            return;
        }
        rect(x, y + r, x + w, y + h - r, argb);
        for (int i = 0; i < r; i++) {
            float half = (float) Math.sqrt(r * r - (r - i) * (r - i));
            rect(x + r - half, y + i, x + w - r + half, y + i + 1, argb);
            rect(x + r - half, y + h - i - 1, x + w - r + half, y + h - i, argb);
        }
    }

    // LWJGL 2 only accepts direct buffers for GL reads (a heap buffer throws
    // "FloatBuffer is not direct"); allocated once, used on the render thread.
    private static final FloatBuffer PROJ = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODEL = BufferUtils.createFloatBuffer(16);

    /** Projection * modelview as the world pass left it. */
    private static float[] captureMatrix() {
        FloatBuffer proj = PROJ, model = MODEL;
        proj.clear();
        model.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, proj);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, model);
        float[] mvp = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += proj.get(k * 4 + row) * model.get(col * 4 + k);
                mvp[col * 4 + row] = sum;
            }
        }
        return mvp;
    }

    /** A camera-relative point to display pixels, or null when behind the camera. */
    private static float[] project(float[] mvp, double x, double y, double z, int w, int h) {
        double cx = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15];
        if (cx <= 0.0001) return null;
        double cxn = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12];
        double cyn = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13];
        return new float[]{(float) ((cxn / cx * 0.5 + 0.5) * w), (float) ((0.5 - cyn / cx * 0.5) * h)};
    }
}
