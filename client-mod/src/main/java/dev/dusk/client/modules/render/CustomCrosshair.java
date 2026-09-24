package dev.dusk.client.modules.render;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.PixelGridSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Flex-HUD's crosshair: a 15x15 pixel texture you paint yourself, drawn
 * exactly on the centre of the screen. It replaces only the vanilla
 * crosshair sprite, so the attack indicator keeps working.
 */
public class CustomCrosshair extends Module {
    public static final int SIZE = CrosshairPresets.SIZE;
    private static final String CUSTOM = "Custom";

    private static CustomCrosshair instance;

    private final ChoiceSetting preset = add(new ChoiceSetting("preset", "Preset", CrosshairPresets.NAMES[0], presetOptions()));
    private final PixelGridSetting pixels = add(new PixelGridSetting("pixels", "Crosshair", SIZE, SIZE, CrosshairPresets.defaultGrid()));
    private final ColorSetting pixelColor = add(new ColorSetting("pixelColor", "Pixel colour", CrosshairPresets.WHITE));
    private final IntSetting scale = add(new IntSetting("scale", "Scale", 100, 25, 400, 25, "%"));
    private final BoolSetting disableBlending = add(new BoolSetting("disableBlending", "Disable blending", false));

    /** Tracks the preset box so picking one repaints the grid. */
    private String appliedPreset = CrosshairPresets.NAMES[0];

    /** Receives every non-transparent pixel of the grid. */
    public interface PixelSink {
        void pixel(int x, int y, int argb);
    }

    public CustomCrosshair() {
        super("crosshair", "Custom Crosshair", Category.RENDER,
                "Your own crosshair texture: pick a preset or paint the 15x15 grid.");
        instance = this;
        setEnabled(true);
    }

    public static CustomCrosshair instance() {
        return instance;
    }

    private static String[] presetOptions() {
        String[] options = new String[CrosshairPresets.NAMES.length + 1];
        System.arraycopy(CrosshairPresets.NAMES, 0, options, 0, CrosshairPresets.NAMES.length);
        options[options.length - 1] = CUSTOM;
        return options;
    }

    public PixelGridSetting pixels() { return pixels; }

    public ColorSetting pixelColor() { return pixelColor; }

    public float scaleFactor() { return scale.get() / 100f; }

    public boolean disableBlending() { return disableBlending.get(); }

    /** Call after painting a pixel by hand so the preset box stops overwriting it. */
    public void markCustom() {
        preset.set(CUSTOM);
        appliedPreset = CUSTOM;
    }

    /** Copies the chosen preset into the grid when the selection changed. */
    public void syncPreset() {
        String chosen = preset.get();
        if (chosen.equals(appliedPreset)) return;
        appliedPreset = chosen;
        int[][] grid = CrosshairPresets.byName(chosen);
        if (grid != null) pixels.setGrid(grid);
    }

    /** False falls back to the vanilla crosshair (or nothing, in third person). */
    public boolean shouldDraw(Minecraft mc) {
        if (!enabled()) return false;
        if (mc.player == null) return false;
        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) return false;
        if (mc.getDebugOverlay().showDebugScreen()) return false;
        // An empty grid draws zero fragments; fall back to vanilla rather
        // than suppressing it into total invisibility. The grid can end up
        // empty via right-click clear, an alpha-0 paint colour, or a
        // malformed config load.
        if (!hasVisiblePixels()) return false;
        return true;
    }

    /** True when at least one grid pixel is opaque. */
    public boolean hasVisiblePixels() {
        // A preset picked since the last frame has to land first, or an
        // emptied grid would keep the vanilla fallback forever: the sync
        // otherwise only runs from forEachPixel, which this check gates.
        syncPreset();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((pixels.pixel(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    public void forEachPixel(PixelSink sink) {
        syncPreset();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int argb = pixels.pixel(x, y);
                if ((argb >>> 24) == 0) continue; // transparent
                sink.pixel(x, y, argb);
            }
        }
    }

    /**
     * Canvas fallback for the versions without a blit hook, and for the
     * editor preview. (cx, cy) is the exact centre of the screen.
     */
    public void render(Canvas c, int cx, int cy, Minecraft mc) {
        float s = scaleFactor();
        c.push();
        c.translate(cx, cy);
        c.scale(s, s);
        c.translate(-SIZE / 2f, -SIZE / 2f);
        forEachPixel((x, y, argb) -> c.fill(x, y, x + 1, y + 1, argb));
        c.pop();
    }
}
