package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.entity.EntityLivingBase;

/**
 * DamageTint (simplified for 1.8.9): recolours the red flash an entity shows
 * when it takes a hit. The modern build repaints an overlay texture; here the
 * flash is four constants 1.8 feeds the lightmap combiner, and this module
 * supplies replacements for them. The colour is given the way DamageTint
 * stores it: the alpha channel is how much of the flash shows, so 0x4DFF0000
 * is the vanilla red. Per-damage-type colours need damage-type reporting that
 * 1.8 servers don't send, so only the main colour is kept.
 */
public class DamageTint extends Module {
    /** Vanilla's own red flash, in DamageTint's colour encoding. */
    public static final int VANILLA = 0x4DFF0000;

    private static DamageTint instance;

    private final ColorSetting color = add(new ColorSetting("color", "Tint colour", VANILLA));
    private final BoolSetting fade = add(new BoolSetting("fade", "Fade the tint out", false));
    private final IntSetting fadeDuration = add(new IntSetting("fadeDuration", "Fade length", 10, 1, 10, 1, "t"));
    private final BoolSetting fadeDeath = add(new BoolSetting("fadeDeath", "Fade out dead entities", false));

    /** The replacements for the entity being rendered. Render thread only. */
    private static float r = 1, g = 0, b = 0, mix = 0.3f;

    public DamageTint() {
        super("damagetint", "Damage Tint", Category.RENDER,
                "Recolours the flash entities show when they are hit.");
        instance = this;
    }

    public static boolean active() {
        DamageTint m = instance;
        return m != null && m.enabled();
    }

    /** Called at the top of setBrightness, before its four hurt-branch constants. */
    public static void prepare(EntityLivingBase entity) {
        DamageTint m = instance;
        r = 1;
        g = 0;
        b = 0;
        mix = 0.3f;
        if (m == null || !m.enabled()) return;
        int argb = m.color.argb();
        r = (argb >> 16 & 0xFF) / 255f;
        g = (argb >> 8 & 0xFF) / 255f;
        b = (argb & 0xFF) / 255f;
        mix = (argb >>> 24) / 255f;
        if (m.fade.get()) {
            if (entity.deathTime > 0) {
                if (m.fadeDeath.get()) mix *= clamp((20 - entity.deathTime) / (float) m.fadeDuration.get());
            } else if (entity.hurtTime > 0) {
                mix *= clamp(entity.hurtTime / (float) m.fadeDuration.get());
            }
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static float red(float vanilla) { return active() ? r : vanilla; }
    public static float green(float vanilla) { return active() ? g : vanilla; }
    public static float blue(float vanilla) { return active() ? b : vanilla; }
    public static float mix(float vanilla) { return active() ? mix : vanilla; }
}
