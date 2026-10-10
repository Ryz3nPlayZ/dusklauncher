package dev.dusk.client.modules.render;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Crosshair Indicator (SwimmingDog1 and MGC8, CC0): four corner brackets
 * around the crosshair while it is on a player close enough to hit. The game
 * only sets the crosshair's target within reach, so no distance check is needed.
 */
public class CrosshairIndicator extends Module {
    private static final String PLAYERS = "Players", LIVING = "Players and mobs", ANY = "Anything you can hit";

    /** The mod's sprite, on the crosshair's 15x15 grid. */
    private static final int[][] BRACKETS = {
            {4, 4}, {5, 4}, {9, 4}, {10, 4},
            {4, 5}, {10, 5},
            {4, 9}, {10, 9},
            {4, 10}, {5, 10}, {9, 10}, {10, 10},
    };

    private static CrosshairIndicator instance;

    private final ChoiceSetting targets = add(new ChoiceSetting("targets", "Show on", PLAYERS, PLAYERS, LIVING, ANY));
    private final BoolSetting invert = add(new BoolSetting("invert", "Invert what's behind it, like the crosshair", true));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFFFFFFFF));

    public CrosshairIndicator() {
        super("crosshairindicator", "Crosshair Indicator", Category.RENDER,
                "Brackets around your crosshair while it's on a player you can hit.");
        instance = this;
    }

    /** The module when the brackets should show this frame, else null. */
    public static CrosshairIndicator active(Minecraft mc) {
        CrosshairIndicator m = instance;
        if (m == null || !m.enabled() || mc.player == null) return null;
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return null;
        if (mc.getDebugOverlay().showDebugScreen()) return null;
        Entity target = mc.crosshairPickEntity;
        boolean hit = switch (m.targets.get()) {
            case LIVING -> target instanceof LivingEntity;
            case ANY -> target != null;
            default -> target instanceof Player;
        };
        return hit ? m : null;
    }

    public boolean invert() { return invert.get(); }

    /** The brackets match the custom crosshair's size while it is the one drawn. */
    public static float scaleFactor(Minecraft mc) {
        CustomCrosshair crosshair = CustomCrosshair.instance();
        return crosshair != null && crosshair.shouldDraw(mc) ? crosshair.scaleFactor() : 1f;
    }

    public void forEachPixel(CustomCrosshair.PixelSink sink) {
        int argb = 0xFF000000 | color.argb();
        for (int[] p : BRACKETS) sink.pixel(p[0], p[1], argb);
    }

    /** Canvas fallback for the versions without a blit hook (no inverting blend there). */
    public void render(Canvas c, float cx, float cy, Minecraft mc) {
        float s = scaleFactor(mc);
        c.push();
        c.translate(cx, cy);
        c.scale(s, s);
        c.translate(-CustomCrosshair.SIZE / 2f, -CustomCrosshair.SIZE / 2f);
        forEachPixel((x, y, argb) -> c.fill(x, y, x + 1, y + 1, argb));
        c.pop();
    }
}
