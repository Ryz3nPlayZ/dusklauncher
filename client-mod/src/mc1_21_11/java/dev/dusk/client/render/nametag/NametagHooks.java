package dev.dusk.client.render.nametag;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.modules.render.Nametags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * PolyNametag's NametagRenderer: the colour, shadow, offset and background
 * maths the nametag mixins share. Everything is a pass-through while the
 * Nametags module is off.
 */
public final class NametagHooks {
    private static final int CORNER_SEGMENTS = 8;
    private static final int ARC_POINTS = CORNER_SEGMENTS + 1;
    private static final int PERIMETER_POINTS = ARC_POINTS * 4;
    private static final double HEAD_SEARCH_RADIUS = 0.75;
    private static final double HEAD_SEARCH_TOP = 1.25;
    public static final float BACKGROUND_DEPTH = 0.01F;

    private static final float[] ARC_COS = new float[PERIMETER_POINTS];
    private static final float[] ARC_SIN = new float[PERIMETER_POINTS];
    private static final float[] perimeter = new float[PERIMETER_POINTS * 2];
    private static final float[] quads = new float[PERIMETER_POINTS * 8];

    private static Object lastWidthText;
    private static int lastWidth;

    static {
        double[] starts = {Math.PI, Math.PI * 1.5, 0.0, Math.PI * 0.5};
        double[] ends = {Math.PI * 1.5, Math.PI * 2.0, Math.PI * 0.5, Math.PI};
        int i = 0;
        for (int corner = 0; corner < 4; corner++) {
            for (int s = 0; s <= CORNER_SEGMENTS; s++) {
                double angle = starts[corner] + (ends[corner] - starts[corner]) * s / CORNER_SEGMENTS;
                ARC_COS[i] = (float) Math.cos(angle);
                ARC_SIN[i] = (float) Math.sin(angle);
                i++;
            }
        }
    }

    private NametagHooks() {}

    // ---- visibility -------------------------------------------------------

    /**
     * Whether an entity's extracted nametag survives: "Remove nametags", the
     * per-kind F1 rules, and the inventory preview. Decided per entity at
     * extract time, so each state carries its own answer into the submit pass.
     */
    public static boolean keepNametag(Entity entity, EntityRenderState state) {
        Nametags n = Nametags.active();
        if (n == null) return true;
        if (n.removeNametags.get()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (Compat.hudHidden(mc) && hiddenInF1(n, entity)) return false;
        return n.showInInventory.get() || !(state instanceof AvatarRenderState) || !inventoryOpen(mc);
    }

    /** The F1 rule for an entity's kind: true hides its nametag. */
    public static boolean hiddenInF1(Nametags n, Entity entity) {
        if (entity instanceof ArmorStand) return n.hideArmorStandsF1.get();
        if (entity instanceof Player) return n.hidePlayersF1.get();
        return n.hideEntitiesF1.get();
    }

    private static boolean inventoryOpen(Minecraft mc) {
        Screen screen = Compat.currentScreen(mc);
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    /**
     * Own nametag: shown unless the server already floats a name over your
     * head. Unlike the original it stays hidden in first person, where it
     * would hang over the camera.
     */
    public static boolean showOwn(Entity entity, boolean original) {
        Nametags n = Nametags.active();
        Minecraft mc = Minecraft.getInstance();
        if (n == null || !n.showOwn.get() || entity != mc.player) return original;
        if (mc.options.getCameraType().isFirstPerson()) return original;
        return !hasServerNametag(entity);
    }

    private static boolean hasServerNametag(Entity entity) {
        AABB box = new AABB(
                entity.getX() - HEAD_SEARCH_RADIUS, entity.getY() + entity.getBbHeight() * 0.5, entity.getZ() - HEAD_SEARCH_RADIUS,
                entity.getX() + HEAD_SEARCH_RADIUS, entity.getY() + entity.getBbHeight() + HEAD_SEARCH_TOP, entity.getZ() + HEAD_SEARCH_RADIUS);
        return !entity.level().getEntities(entity, box, e -> e != entity && isNametagEntity(e)).isEmpty();
    }

    private static boolean isNametagEntity(Entity entity) {
        if (entity instanceof Display.TextDisplay) return true;
        return entity instanceof ArmorStand stand && stand.isCustomNameVisible() && stand.getCustomName() != null;
    }

    // ---- text -------------------------------------------------------------

    public static float translateY(float y) {
        Nametags n = Nametags.active();
        return n == null ? y : y - n.heightOffset.get() / 100F;
    }

    public static boolean textShadow(boolean original) {
        Nametags n = Nametags.active();
        return n == null ? original : n.textShadow.index() != 0;
    }

    /** The configured text colour, keeping vanilla's faded see-through alpha. */
    public static int textColor(int original) {
        Nametags n = Nametags.active();
        if (n == null) return original;
        int color = n.textColor.argb();
        int originalAlpha = original >>> 24;
        return originalAlpha >= 1 && originalAlpha <= 254 ? withAlpha(color, Math.min(color >>> 24, originalAlpha)) : color;
    }

    public static int backgroundColor(int original) {
        Nametags n = Nametags.active();
        if (n == null) return original;
        if (!n.background.get() || original == 0) return 0;
        int color = n.backgroundColor.argb();
        int originalAlpha = original >>> 24;
        return originalAlpha >= 1 && originalAlpha <= 32 ? withAlpha(color, Math.min(color >>> 24, originalAlpha)) : color;
    }

    /** "Override text color": drops colour codes and team colours from the name. */
    public static Component text(Component original) {
        Nametags n = Nametags.active();
        return n == null || !n.overrideTextColor.get() ? original : stripColor(original);
    }

    public static FormattedCharSequence text(FormattedCharSequence original) {
        Nametags n = Nametags.active();
        if (n == null || !n.overrideTextColor.get()) return original;
        return sink -> original.accept((index, style, codePoint) -> sink.accept(index, style.withColor((TextColor) null), codePoint));
    }

    private static Component stripColor(Component component) {
        MutableComponent result = MutableComponent.create(component.getContents())
                .setStyle(component.getStyle().withColor((TextColor) null));
        for (Component sibling : component.getSiblings()) result.append(stripColor(sibling));
        return result;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    // ---- background shape -------------------------------------------------

    /** Padding or rounding needs a hand-drawn background instead of vanilla's box. */
    public static boolean useCustomBackground() {
        Nametags n = Nametags.active();
        if (n == null || !n.background.get()) return false;
        return n.rounded.get() || n.paddingX.get() > 0 || n.paddingY.get() > 0;
    }

    public static int backgroundArgb() {
        Nametags n = Nametags.active();
        return n == null ? 0 : n.backgroundColor.argb();
    }

    public static int textWidth(Font font, Object text) {
        if (lastWidthText == text) return lastWidth;
        int width = text instanceof Component c ? font.width(c) : font.width((FormattedCharSequence) text);
        lastWidthText = text;
        lastWidth = width;
        return width;
    }

    public static float[] quadBuffer() {
        return quads;
    }

    /**
     * Fills {@link #quadBuffer()} with x/y pairs, four vertices per quad, for
     * the padded (and optionally rounded) box behind a nametag at x/y.
     * Returns the number of floats written.
     */
    public static int backgroundQuads(float x, float y, float width) {
        Nametags n = Nametags.active();
        float padX = n == null ? 0 : n.paddingX.get();
        float padY = n == null ? 0 : n.paddingY.get();
        float x0 = x - 1.0F - padX;
        float x1 = x + width + padX;
        float y0 = y - 1.0F - padY;
        float y1 = y + 9.0F + padY;
        float radius = n != null && n.rounded.get()
                ? Math.min(n.cornerRadius.get(), Math.min((x1 - x0) / 2.0F, (y1 - y0) / 2.0F)) : 0.0F;

        float[] out = quads;
        if (radius <= 0.0F) {
            out[0] = x0; out[1] = y1;
            out[2] = x1; out[3] = y1;
            out[4] = x1; out[5] = y0;
            out[6] = x0; out[7] = y0;
            return 8;
        }

        int p = 0, t = 0;
        for (int corner = 0; corner < 4; corner++) {
            float ccx = corner == 0 || corner == 3 ? x0 + radius : x1 - radius;
            float ccy = corner == 0 || corner == 1 ? y0 + radius : y1 - radius;
            for (int s = 0; s < ARC_POINTS; s++) {
                perimeter[p++] = ccx + radius * ARC_COS[t];
                perimeter[p++] = ccy + radius * ARC_SIN[t];
                t++;
            }
        }

        float cx = (x0 + x1) / 2.0F;
        float cy = (y0 + y1) / 2.0F;
        int o = 0;
        for (int i = 0; i < PERIMETER_POINTS; i++) {
            float ax = perimeter[i * 2], ay = perimeter[i * 2 + 1];
            int next = (i + 1) % PERIMETER_POINTS;
            float bx = perimeter[next * 2], by = perimeter[next * 2 + 1];
            out[o++] = cx; out[o++] = cy;
            out[o++] = bx; out[o++] = by;
            out[o++] = ax; out[o++] = ay;
            out[o++] = ax; out[o++] = ay;
        }
        return o;
    }

    /** Scale factor for the nametag's pose. */
    public static float scale() {
        Nametags n = Nametags.active();
        return n == null ? 1.0F : n.scale.get() / 100F;
    }

    /** Sneaking no longer hides a nametag while the module is on. */
    public static boolean discrete(boolean original) {
        return Nametags.active() == null && original;
    }

    /** Keeps names visible under F1 for kinds the module is told not to hide. */
    public static boolean renderNamesUnderF1(Entity entity, boolean renderNames) {
        Nametags n = Nametags.active();
        if (renderNames || n == null) return renderNames;
        return !hiddenInF1(n, entity);
    }
}
