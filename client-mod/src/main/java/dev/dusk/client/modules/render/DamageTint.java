package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.render.OverlayTint;

import java.util.Arrays;

/**
 * DamageTint: repaints the red flash an entity shows when it takes a hit.
 * The colour sits in the overlay texture the entity renderers sample, so a
 * repaint is one small texture upload rather than per-entity work.
 *
 * <p>The colour is given the way DamageTint stores it: the alpha channel is
 * how much of the entity's own colour shows through, so 0x4DFF0000 is the
 * vanilla red. Per-damage-type colours use the spare columns of that texture
 * and depend on the server reporting the damage type, which not all do;
 * anything unrecognised falls back to the main colour. DamageTint's chroma
 * cycling is not ported, since the editor's colour picker has no chroma.
 */
public class DamageTint extends Module {
    /** Vanilla's own red flash, in DamageTint's colour encoding. */
    public static final int VANILLA = 0x4DFF0000;

    private static DamageTint instance;

    private final ColorSetting color = add(new ColorSetting("color", "Tint colour", VANILLA));
    private final BoolSetting perType = add(new BoolSetting("perType", "Colour per damage type", false));
    private final ColorSetting melee = add(new ColorSetting("melee", "Melee colour", VANILLA));
    private final ColorSetting mace = add(new ColorSetting("mace", "Mace colour", VANILLA));
    private final ColorSetting ranged = add(new ColorSetting("ranged", "Ranged colour", VANILLA));
    private final ColorSetting explosion = add(new ColorSetting("explosion", "Explosion colour", VANILLA));
    private final ColorSetting magic = add(new ColorSetting("magic", "Magic colour", VANILLA));
    private final ColorSetting crit = add(new ColorSetting("crit", "Critical hit colour", VANILLA));
    private final BoolSetting fade = add(new BoolSetting("fade", "Fade the tint out", false));
    private final IntSetting fadeDuration = add(new IntSetting("fadeDuration", "Fade length", 10, 1, 10, 1, "t"));
    private final BoolSetting fadeDeath = add(new BoolSetting("fadeDeath", "Fade out dead entities", false));

    /** The columns last uploaded, so a settled config uploads nothing. */
    private int[] uploaded;
    private boolean uploadedFade;

    public DamageTint() {
        super("damagetint", "Damage Tint", Category.RENDER,
                "Recolours the flash entities show when they are hit.");
        instance = this;
    }

    public static boolean active() {
        DamageTint m = instance;
        return m != null && m.enabled();
    }

    public static boolean perType() {
        DamageTint m = instance;
        return m != null && m.perType.get();
    }

    public static boolean fading() {
        DamageTint m = instance;
        return m != null && m.fade.get();
    }

    public static boolean fadesDead() {
        DamageTint m = instance;
        return m != null && m.fadeDeath.get();
    }

    public static float fadeDuration() {
        DamageTint m = instance;
        return m == null ? 10f : m.fadeDuration.get();
    }

    @Override
    public void tick() {
        int[] columns = new int[OverlayTint.COLUMNS];
        Arrays.fill(columns, color.argb());
        if (perType.get()) {
            columns[OverlayTint.MELEE] = melee.argb();
            columns[OverlayTint.MACE] = mace.argb();
            columns[OverlayTint.RANGED] = ranged.argb();
            columns[OverlayTint.EXPLOSION] = explosion.argb();
            columns[OverlayTint.MAGIC] = magic.argb();
            columns[OverlayTint.CRIT] = crit.argb();
        }
        upload(columns, fade.get());
    }

    @Override
    protected void onDisable() {
        int[] columns = new int[OverlayTint.COLUMNS];
        Arrays.fill(columns, VANILLA);
        upload(columns, false);
    }

    private void upload(int[] columns, boolean fade) {
        if (fade == uploadedFade && Arrays.equals(columns, uploaded)) return;
        uploaded = columns;
        uploadedFade = fade;
        OverlayTint.apply(columns, fade);
    }
}
