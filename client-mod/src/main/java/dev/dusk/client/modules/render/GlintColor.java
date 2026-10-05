package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import org.jetbrains.annotations.Nullable;

/**
 * Recolours the enchantment glint on items and armour: one colour, a rainbow
 * cycle, or a fade between two colours. The glint shader is patched to take
 * its brightness from the vanilla texture and its hue from here
 * ({@code GlintShaderMixin}, {@code GlintColorMixin}); ported from ZEEG
 * (MIT, Zapaxe).
 */
public class GlintColor extends Module {
    private static GlintColor instance;

    private final ChoiceSetting mode = add(new ChoiceSetting("mode", "Mode", "Static", "Static", "Rainbow", "Two-tone"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0xFF8040FF));
    private final ColorSetting color2 = add(new ColorSetting("color2", "Second colour", 0xFF40C0FF));
    private final IntSetting speed = add(new IntSetting("speed", "Speed", 5, 1, 20, 1, ""));

    public GlintColor() {
        super("glintcolor", "Glint Colour", Category.RENDER,
                "Changes the enchantment glint to any colour, a rainbow, or a fade between two colours.");
        instance = this;
    }

    /**
     * The glint's colour modulator as {r, g, b, a}, or null to leave the glint
     * vanilla. A negative alpha tells the patched shader to recolour; the
     * colour's own alpha is the glint's strength, folded into rgb because the
     * glint blends additively.
     */
    @Nullable
    public static float[] modulator() {
        if (instance == null || !instance.enabled()) return null;
        return instance.compute(System.currentTimeMillis());
    }

    private float[] compute(long now) {
        // one full cycle every 20/speed seconds
        double phase = (now % 1_000_000L) / 1000.0 * speed.get() / 20.0;
        float r, g, b;
        int c = color.argb();
        if (mode.is("Rainbow")) {
            float[] rgb = hsv((float) (phase - Math.floor(phase)));
            r = rgb[0];
            g = rgb[1];
            b = rgb[2];
        } else if (mode.is("Two-tone")) {
            int d = color2.argb();
            float t = (float) (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI));
            r = lerp(channel(c, 16), channel(d, 16), t);
            g = lerp(channel(c, 8), channel(d, 8), t);
            b = lerp(channel(c, 0), channel(d, 0), t);
        } else {
            r = channel(c, 16);
            g = channel(c, 8);
            b = channel(c, 0);
        }
        float strength = channel(c, 24);
        return new float[]{r * strength, g * strength, b * strength, -1f};
    }

    private static float channel(int argb, int shift) {
        return ((argb >>> shift) & 0xFF) / 255f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float[] hsv(float hue) {
        int i = (int) (hue * 6) % 6;
        float f = hue * 6 - (int) (hue * 6);
        float q = 1 - f;
        return switch (i) {
            case 0 -> new float[]{1, f, 0};
            case 1 -> new float[]{q, 1, 0};
            case 2 -> new float[]{0, 1, f};
            case 3 -> new float[]{0, q, 1};
            case 4 -> new float[]{f, 0, 1};
            default -> new float[]{1, 0, q};
        };
    }
}
