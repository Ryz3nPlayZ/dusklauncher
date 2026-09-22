package dev.dusk.client.modules.render;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Replaces the vanilla crosshair with a configurable shape. Colour
 * responds to what you are looking at and the gap opens while a bow is
 * drawn or the attack cooldown is recharging.
 */
public class CustomCrosshair extends Module {
    private static CustomCrosshair instance;

    private final ChoiceSetting shape = add(new ChoiceSetting("shape", "Shape", "Cross", "Cross", "Square", "Dot", "Circle", "Vanilla"));
    private final IntSetting size = add(new IntSetting("size", "Size", 5, 1, 20, 1, "px"));
    private final IntSetting gap = add(new IntSetting("gap", "Gap", 2, 0, 12, 1, "px"));
    private final IntSetting thickness = add(new IntSetting("thickness", "Thickness", 1, 1, 5, 1, "px"));
    private final BoolSetting centerDot = add(new BoolSetting("centerDot", "Centre dot", false));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFFFFF));
    private final BoolSetting outline = add(new BoolSetting("outline", "Outline", true));
    private final ColorSetting outlineColor = add(new ColorSetting("outlineColor", "Outline colour", 0x80000000));
    private final BoolSetting chroma = add(new BoolSetting("chroma", "Rainbow", false));
    private final BoolSetting targetColors = add(new BoolSetting("targetColors", "Colour by target", true));
    private final ColorSetting playerColor = add(new ColorSetting("playerColor", "Player target", 0xFFFF5555));
    private final ColorSetting hostileColor = add(new ColorSetting("hostileColor", "Hostile target", 0xFFFFAA00));
    private final ColorSetting friendlyColor = add(new ColorSetting("friendlyColor", "Friendly target", 0xFF55FF55));
    private final BoolSetting dynamicBow = add(new BoolSetting("dynamicBow", "Open while drawing a bow", true));
    private final BoolSetting dynamicAttack = add(new BoolSetting("dynamicAttack", "Open during attack cooldown", false));
    private final BoolSetting hideThirdPerson = add(new BoolSetting("hideThirdPerson", "Hide in third person", true));

    public CustomCrosshair() {
        super("crosshair", "Custom Crosshair", Category.RENDER, "Your own crosshair: shape, size, colour and target highlighting.");
        instance = this;
        setEnabled(true);
    }

    public static CustomCrosshair instance() {
        return instance;
    }

    /** False falls back to the vanilla crosshair (or nothing, in third person). */
    public boolean shouldDraw(Minecraft mc) {
        if (shape.is("Vanilla")) return false;
        if (mc.player == null) return false;
        if (mc.getDebugOverlay().showDebugScreen()) return false;
        return true;
    }

    private int currentColor(Minecraft mc) {
        if (targetColors.get() && mc.hitResult instanceof EntityHitResult hit) {
            Entity e = hit.getEntity();
            if (e instanceof Player) return playerColor.argb();
            if (e instanceof Enemy) return hostileColor.argb();
            if (e instanceof Animal) return friendlyColor.argb();
        }
        if (chroma.get()) return Fmt.chroma(3000, 0);
        return color.argb();
    }

    private int currentGap(Minecraft mc) {
        int g = gap.get();
        if (mc.player == null) return g;
        if (dynamicBow.get() && mc.player.isUsingItem()) {
            var item = mc.player.getUseItem().getItem();
            if (item == Items.BOW || item == Items.CROSSBOW || item == Items.TRIDENT) {
                float charge = Math.min(1f, mc.player.getTicksUsingItem() / 20f);
                g += Math.round((1f - charge) * 6f);
            }
        }
        if (dynamicAttack.get()) {
            float strength = mc.player.getAttackStrengthScale(0f);
            g += Math.round((1f - strength) * 4f);
        }
        return g;
    }

    public void render(Canvas c, int cx, int cy, Minecraft mc) {
        if (hideThirdPerson.get() && mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        int col = currentColor(mc);
        int g = currentGap(mc);
        int s = size.get();
        int t = thickness.get();
        int half = t / 2;
        switch (shape.get()) {
            case "Cross" -> {
                // arms: top, bottom, left, right
                rect(c, cx - half, cy - g - s, t, s, col);
                rect(c, cx - half, cy + g + 1, t, s, col);
                rect(c, cx - g - s, cy - half, s, t, col);
                rect(c, cx + g + 1, cy - half, s, t, col);
                if (centerDot.get()) rect(c, cx - half, cy - half, t, t, col);
            }
            case "Square" -> {
                int r = g + s;
                rect(c, cx - r, cy - r, r * 2 + 1, t, col);
                rect(c, cx - r, cy + r - t + 1, r * 2 + 1, t, col);
                rect(c, cx - r, cy - r, t, r * 2 + 1, col);
                rect(c, cx + r - t + 1, cy - r, t, r * 2 + 1, col);
                if (centerDot.get()) rect(c, cx - half, cy - half, t, t, col);
            }
            case "Dot" -> {
                int r = Math.max(1, s / 2);
                rect(c, cx - r + 1, cy - r + 1, r * 2 - 1, r * 2 - 1, col);
            }
            case "Circle" -> {
                int r = g + s;
                int segs = Math.max(12, r * 4);
                int px = 0, py = 0;
                for (int i = 0; i <= segs; i++) {
                    double a = i * Math.PI * 2 / segs;
                    int x = (int) Math.round(Math.cos(a) * r), y = (int) Math.round(Math.sin(a) * r);
                    if (i > 0) plotLine(c, cx + px, cy + py, cx + x, cy + y, t, col);
                    px = x;
                    py = y;
                }
                if (centerDot.get()) rect(c, cx - half, cy - half, t, t, col);
            }
            default -> {}
        }
    }

    private void rect(Canvas c, int x, int y, int w, int h, int col) {
        if (outline.get()) {
            int oc = outlineColor.argb();
            c.fill(x - 1, y - 1, x + w + 1, y + h + 1, oc);
        }
        c.fill(x, y, x + w, y + h, col);
    }

    /** Bresenham with a square brush; small radii only. */
    private void plotLine(Canvas c, int x0, int y0, int x1, int y1, int t, int col) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int half = t / 2;
        while (true) {
            c.fill(x0 - half, y0 - half, x0 - half + t, y0 - half + t, col);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }
}
