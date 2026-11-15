package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ActionSetting;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumParticleTypes;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Particles, from OverflowParticles' option list (behaviour only), with only
 * the particles 1.8.9 has — keyed by the modern names so a config copied from
 * 1.21.11 loads. Each particle can be hidden, recoloured, resized,
 * multiplied or faded; block digging and running dust can be hidden
 * outright, and each can glow: full bright with a soft halo in its own
 * colour (on for both crits by default). Global switches hide your own
 * potion swirls in first person, force full-bright particles, let them pass
 * through blocks, and put crit particles on every hit.
 */
public class Particles extends Module {
    /** spawnEffectParticle verdicts for the mixin. */
    public static final int SPAWN = 0, CANCEL = 1;
    private static final int MAX_EXTRA = 9;
    private static final double CLEAN_VIEW_RANGE_SQ = 0.8 * 0.8;

    /**
     * Each row: modern key, display name, then the 1.8.9 particle names it
     * covers. Names 1.8.9 splits across several types (the two suspendeds,
     * the two mob spells, the two big explosions) share one entry.
     */
    private static final String[][] TYPES = {
            {"explosion", "Explosion", "explode"},
            {"explosion_emitter", "Large Explosion", "largeexplode", "hugeexplode"},
            {"firework", "Firework Spark", "fireworksSpark"},
            {"bubble", "Water Bubble", "bubble"},
            {"splash", "Water Splash", "splash"},
            {"fishing", "Water Wake", "wake"},
            {"underwater", "Suspended", "suspended", "depthsuspend"},
            {"crit", "Critical", "crit"},
            {"enchanted_hit", "Magic Critical (Sharpness)", "magicCrit"},
            {"smoke", "Smoke", "smoke"},
            {"large_smoke", "Large Smoke", "largesmoke"},
            {"effect", "Splash Potion", "spell"},
            {"instant_effect", "Instant Potion", "instantSpell"},
            {"entity_effect", "Potion", "mobSpell", "mobSpellAmbient"},
            {"witch", "Witch Spell", "witchMagic"},
            {"dripping_water", "Water Drip", "dripWater"},
            {"dripping_lava", "Lava Drip", "dripLava"},
            {"angry_villager", "Angry Villager", "angryVillager"},
            {"happy_villager", "Happy Villager", "happyVillager"},
            {"mycelium", "Mycelium", "townaura"},
            {"note", "Note", "note"},
            {"portal", "Portal", "portal"},
            {"enchant", "Enchantment Rune", "enchantmentTable"},
            {"flame", "Flame", "flame"},
            {"lava", "Lava", "lava"},
            {"dust", "Redstone", "reddust"},
            {"item_snowball", "Snowball", "snowballpoof"},
            {"item_slime", "Slime", "slime"},
            {"heart", "Heart", "heart"},
            {"item", "Item Eat / Break", "iconcrack"},
            {"block", "Blocks", "blockcrack", "blockdust"},
    };
    /** Particles an explosion or firework triggers; they never fade. */
    private static final Set<String> FIREWORK_TRIGGERED = new HashSet<String>();
    static {
        FIREWORK_TRIGGERED.add("explosion");
        FIREWORK_TRIGGERED.add("explosion_emitter");
        FIREWORK_TRIGGERED.add("firework");
    }
    /** Particles with no glow option (block and item pieces), and the ones glowing by default. */
    private static final Set<String> NO_GLOW = new HashSet<String>();
    private static final Set<String> GLOW_DEFAULT = new HashSet<String>();
    static {
        NO_GLOW.add("block");
        NO_GLOW.add("item");
        GLOW_DEFAULT.add("crit");
        GLOW_DEFAULT.add("enchanted_hit");
    }

    private static Particles instance;

    public final BoolSetting cleanView = add(new BoolSetting("cleanView", "Hide your own potion swirls (first person)", false));
    public final BoolSetting staticColor = add(new BoolSetting("staticColor", "Ignore lighting (always full bright)", false));
    public final BoolSetting noClip = add(new BoolSetting("noClip", "Particles pass through blocks", false));
    public final BoolSetting alwaysCritical = add(new BoolSetting("alwaysCritical", "Crit particles on every hit", false));
    public final BoolSetting alwaysSharp = add(new BoolSetting("alwaysSharp", "Sharpness particles on every hit", false));
    public final BoolSetting checkInvulnerable = add(new BoolSetting("checkInvulnerable", "Only on hits that land", false));
    public final IntSetting glowStrength = add(new IntSetting("glowStrength", "Glow strength", 70, 10, 200, 10, "%"));
    public final IntSetting glowSize = add(new IntSetting("glowSize", "Glow size", 300, 150, 600, 25, "%"));

    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private final Map<String, Entry> byName189 = new HashMap<String, Entry>();
    private final ChoiceSetting editing;
    private final Random random = new Random();

    /** Particles marked full-bright, and the glowing ones; weak so dropped particles fall out on their own. */
    private final Set<EntityFX> bright = Collections.newSetFromMap(new WeakHashMap<EntityFX, Boolean>());
    private final Set<EntityFX> glowing = Collections.newSetFromMap(new WeakHashMap<EntityFX, Boolean>());
    /** Particles fading out: base alpha and the fraction of life where fading starts. */
    private final Map<EntityFX, float[]> fading = new HashMap<EntityFX, float[]>();

    /** One particle's settings, keyed like the modern config (key.setting). */
    final class Entry {
        final String key, name;
        final BoolSetting enabled;
        BoolSetting customColor;
        ChoiceSetting colorMode;
        ColorSetting color;
        IntSetting size;
        IntSetting multiplier;
        BoolSetting fade;
        IntSetting fadeStart;
        BoolSetting hideDigging;
        BoolSetting hideRunning;
        BoolSetting glow;

        Entry(String key, String name) {
            this.key = key;
            this.name = name;
            boolean blocks = key.equals("block");
            enabled = addSetting(new BoolSetting(key + ".enabled", "Show", true));
            if (blocks) {
                hideDigging = addSetting(new BoolSetting(key + ".hideDigging", "Hide block digging particles", false));
                hideRunning = addSetting(new BoolSetting(key + ".hideRunning", "Hide entity running / falling particles", false));
            } else {
                customColor = addSetting(new BoolSetting(key + ".customColor", "Custom colour", false));
                colorMode = addSetting(new ChoiceSetting(key + ".colorMode", "Colour mode", "Multiply", "Multiply", "Override"));
                color = addSetting(new ColorSetting(key + ".color", "Colour", 0xFFFFFFFF));
                size = addSetting(new IntSetting(key + ".size", "Size", 100, 50, 500, 10, "%"));
                multiplier = addSetting(new IntSetting(key + ".multiplier", "Multiplier", 100, 0, 1000, 10, "%"));
            }
            if (!NO_GLOW.contains(key)) {
                glow = addSetting(new BoolSetting(key + ".glow", "Glow", GLOW_DEFAULT.contains(key)));
            }
            if (!FIREWORK_TRIGGERED.contains(key)) {
                fade = addSetting(new BoolSetting(key + ".fade", "Fade", false));
                fadeStart = addSetting(new IntSetting(key + ".fadeStart", "Fade out start", 50, 0, 100, 1, "%"));
            }
        }

        private <S extends Setting<?>> S addSetting(S s) {
            add(s, name);
            s.visibleWhen(() -> editing != null && editing.is(name));
            return s;
        }

        float sizeScale() { return size == null ? 1f : size.get() / 100f; }

        float multiplierValue() { return multiplier == null ? 1f : multiplier.get() / 100f; }

        float fadeStartFraction() { return fadeStart == null ? 0f : fadeStart.get() / 100f; }
    }

    public Particles() {
        super("particles", "Particles", Category.RENDER,
                "Hides, recolours, resizes, multiplies or fades each particle 1.8.9 has.");
        instance = this;

        String[] names = new String[TYPES.length];
        for (int i = 0; i < TYPES.length; i++) names[i] = TYPES[i][1];
        editing = add(new ChoiceSetting("editing", "Particle", names[0], names));
        add(new ActionSetting("resetParticle", "Reset this particle", "Reset", this::resetSelected));
        for (String[] t : TYPES) {
            Entry e = new Entry(t[0], t[1]);
            entries.put(t[0], e);
            for (int i = 2; i < t.length; i++) byName189.put(t[i], e);
        }
    }

    private void resetSelected() {
        Entry e = entries.get(keyOf(editing.get()));
        if (e == null) return;
        for (Setting<?> s : settings()) {
            if (s.id().startsWith(e.key + ".")) s.reset();
        }
    }

    private static String keyOf(String name) {
        for (String[] t : TYPES) if (t[1].equals(name)) return t[0];
        return null;
    }

    public static Particles instance() {
        return instance;
    }

    private static Entry entry(int particleId) {
        if (instance == null) return null;
        EnumParticleTypes type = EnumParticleTypes.getParticleFromId(particleId);
        return type == null ? null : instance.byName189.get(type.getParticleName());
    }

    /**
     * The spawn decision at the head of spawnEffectParticle: cancel for hidden
     * entries, the multiplier's missing share, and (with clean view) your own
     * first-person swirls.
     */
    public static int gate(int particleId, double x, double y, double z) {
        if (instance == null || !instance.enabled()) return SPAWN;
        EnumParticleTypes type = EnumParticleTypes.getParticleFromId(particleId);
        if (type == null) return SPAWN;
        String name189 = type.getParticleName();
        Entry e = instance.byName189.get(name189);
        if (e == null) return SPAWN;
        if (!e.enabled.get()) return CANCEL;
        if ("blockcrack".equals(name189) && e.hideDigging != null && e.hideDigging.get()) return CANCEL;
        if ("blockdust".equals(name189) && e.hideRunning != null && e.hideRunning.get()) return CANCEL;
        if (instance.cleanView.get() && instance.nearCamera(x, y, z)) return CANCEL;
        float mult = e.multiplierValue();
        if (mult < 1f && instance.random.nextFloat() >= mult) return CANCEL;
        return SPAWN;
    }

    private boolean nearCamera(double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.gameSettings.thirdPersonView != 0) return false;
        double dx = x - mc.thePlayer.posX;
        double dy = y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = z - mc.thePlayer.posZ;
        return dx * dx + dy * dy + dz * dz < CLEAN_VIEW_RANGE_SQ;
    }

    /** How many extra copies the multiplier wants, on top of the original. */
    public static int extraCount(int particleId) {
        Entry e = entry(particleId);
        if (e == null || e.multiplier == null) return 0;
        int times = Math.round(e.multiplierValue());
        return Math.min(MAX_EXTRA, Math.max(0, times - 1));
    }

    /** Styles one spawned particle: size, colour, no-clip, brightness, fade. */
    public static void apply(EntityFX fx, int particleId) {
        if (instance == null || !instance.enabled()) return;
        Entry e = entry(particleId);
        if (e == null) return;
        if (e.sizeScale() != 1f) fx.multipleParticleScaleBy(e.sizeScale());
        if (e.customColor != null && e.customColor.get()) {
            int argb = e.color.argb();
            boolean override = e.colorMode.is("Override");
            fx.setRBGColorF(
                    (argb >> 16 & 0xFF) / 255f * (override ? 1f : fx.getRedColorF()),
                    (argb >> 8 & 0xFF) / 255f * (override ? 1f : fx.getGreenColorF()),
                    (argb & 0xFF) / 255f * (override ? 1f : fx.getBlueColorF()));
        }
        boolean glow = e.glow != null && e.glow.get();
        if (glow) instance.glowing.add(fx);
        if (glow || instance.staticColor.get()) instance.bright.add(fx);
        if (instance.noClip.get()) fx.noClip = true;
        if (e.fade != null && e.fade.get()) {
            instance.fading.put(fx, new float[]{fx.getAlpha(), e.fadeStartFraction()});
        }
    }

    /** Whether this particle was marked full-bright. */
    public static boolean isBright(EntityFX fx) {
        return instance != null && instance.bright.contains(fx);
    }

    /** The live glowing particles, for the halo pass; empty while the module is off. */
    public static Set<EntityFX> glowing() {
        return instance == null || !instance.enabled() ? Collections.<EntityFX>emptySet() : instance.glowing;
    }

    /** The halo's opacity and its size against the particle, as fractions. */
    public static float glowStrength() { return instance == null ? 0f : instance.glowStrength.get() / 100f; }

    public static float glowSize() { return instance == null ? 1f : instance.glowSize.get() / 100f; }

    /**
     * An attack happened (Forge's AttackEntityEvent): lay crit or sharpness
     * particles over the target when those switches are on, the way vanilla
     * does for real crits.
     */
    public static void onAttack(Minecraft mc, EntityPlayer player, Entity target) {
        Particles m = instance;
        if (m == null || !m.enabled()) return;
        boolean crit = m.alwaysCritical.get();
        boolean sharp = m.alwaysSharp.get();
        if (!crit && !sharp) return;
        if (m.checkInvulnerable.get() && target.isEntityInvulnerable(DamageSource.causePlayerDamage(player))) return;
        EnumParticleTypes type = sharp ? EnumParticleTypes.CRIT_MAGIC : EnumParticleTypes.CRIT;
        Random rand = m.random;
        for (int i = 0; i < 9; i++) {
            double x = target.posX + (rand.nextDouble() - 0.5) * target.width * 2.0;
            double y = target.posY + rand.nextDouble() * target.height;
            double z = target.posZ + (rand.nextDouble() - 0.5) * target.width * 2.0;
            mc.theWorld.spawnParticle(type, x, y, z,
                    -player.motionX, -player.motionY, -player.motionZ);
        }
    }

    @Override
    public void tick() {
        if (fading.isEmpty()) {
            if (Minecraft.getMinecraft().theWorld == null) {
                bright.clear();
                glowing.clear();
            }
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Iterator<Map.Entry<EntityFX, float[]>> it = fading.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityFX, float[]> e = it.next();
            EntityFX fx = e.getKey();
            if (fx.isDead) {
                it.remove();
                continue;
            }
            float alpha = fadeAlpha(fx, e.getValue()[0], e.getValue()[1]);
            if (alpha >= 0f) fx.setAlphaF(alpha);
        }
        if (mc.theWorld == null) {
            fading.clear();
            bright.clear();
            glowing.clear();
        }
    }

    /** The alpha for a fading particle, or a negative value to leave it alone. */
    private static float fadeAlpha(EntityFX fx, float base, float start) {
        dev.dusk.client.render.ParticleAge age = (dev.dusk.client.render.ParticleAge) fx;
        int life = age.dusk$maxAge() - age.dusk$age();
        if (life <= 0) return -1f;
        float fraction = life / (float) age.dusk$maxAge();
        float scale = start <= 0f ? (life <= 1 ? 0f : 1f) : Math.min(1f, fraction / start);
        return base * scale;
    }

    @Override
    protected void onDisable() {
        fading.clear();
        bright.clear();
        glowing.clear();
    }
}
