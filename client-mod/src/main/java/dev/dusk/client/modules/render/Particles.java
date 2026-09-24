package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.Setting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of Polyfrost's OverflowParticles: per-particle toggle, colour
 * (multiply/override), fade-out, size and spawn multiplier, plus the global
 * clean-view / static-colour / no-clip / always-crit options. Particles are
 * keyed by registry path so this class stays version-neutral; the mixins in
 * the 1.21.11+ layer resolve ParticleTypes to these keys.
 *
 * Sizes, multipliers and fade starts are percentages (the original's floats
 * x100) because the settings API has no float type.
 */
public class Particles extends Module {
    /** Same order and names as OverflowParticles' VanillaParticles. */
    private static final String[][] VANILLA = {
            {"explosion", "Explosion"},
            {"explosion_emitter", "Large Explosion"},
            {"firework", "Firework Spark"},
            {"bubble", "Water Bubble"},
            {"splash", "Water Splash"},
            {"fishing", "Water Wake"},
            {"underwater", "Suspended"},
            {"crit", "Critical"},
            {"enchanted_hit", "Magic Critical (Sharpness)"},
            {"smoke", "Smoke"},
            {"large_smoke", "Large Smoke"},
            {"effect", "Splash Potion"},
            {"instant_effect", "Instant Potion"},
            {"entity_effect", "Potion"},
            {"witch", "Witch Spell"},
            {"dripping_water", "Water Drip"},
            {"dripping_lava", "Lava Drip"},
            {"angry_villager", "Angry Villager"},
            {"happy_villager", "Happy Villager"},
            {"mycelium", "Mycelium"},
            {"note", "Note"},
            {"portal", "Portal"},
            {"enchant", "Enchantment Rune"},
            {"flame", "Flame"},
            {"lava", "Lava"},
            {"dust", "Redstone"},
            {"item_snowball", "Snowball"},
            {"item_slime", "Slime"},
            {"heart", "Heart"},
            {"item", "Item Eat / Break"},
            {"block", "Blocks"},
            {"rain", "Rain Drop"},
            {"dragon_breath", "Dragon Breath"},
            {"end_rod", "End Rod"},
            {"damage_indicator", "Damage Indicator"},
            {"sweep_attack", "Sweep Attack"},
            {"totem_of_undying", "Totem"},
            {"spit", "Spit"},
            {"bubble_column_up", "Bubble Column Up"},
            {"bubble_pop", "Bubble Pop"},
            {"current_down", "Bubble Column Down"},
            {"dolphin", "Dolphin"},
            {"nautilus", "Conduit (Nautilus)"},
            {"campfire_cosy_smoke", "Campfire Smoke"},
            {"campfire_signal_smoke", "Campfire Signal Smoke"},
            {"dripping_honey", "Honey Drip"},
            {"falling_honey", "Falling Honey"},
            {"landing_honey", "Landing Honey"},
            {"falling_nectar", "Falling Nectar"},
            {"soul_fire_flame", "Soul Fire Flame"},
            {"crimson_spore", "Crimson Spore"},
            {"warped_spore", "Warped Spore"},
            {"ash", "Ash"},
            {"white_ash", "White Ash"},
            {"dripping_obsidian_tear", "Obsidian Tear Drip"},
            {"falling_obsidian_tear", "Falling Obsidian Tear"},
            {"landing_obsidian_tear", "Landing Obsidian Tear"},
            {"glow", "Glow"},
            {"glow_squid_ink", "Glow Squid Ink"},
            {"electric_spark", "Electric Spark"},
            {"snowflake", "Snowflake"},
            {"dripping_dripstone_water", "Dripstone Water Drip"},
            {"falling_dripstone_water", "Falling Dripstone Water"},
            {"dripping_dripstone_lava", "Dripstone Lava Drip"},
            {"falling_dripstone_lava", "Falling Dripstone Lava"},
            {"spore_blossom_air", "Spore Blossom Air"},
            {"falling_spore_blossom", "Falling Spore Blossom"},
            {"wax_on", "Wax On"},
            {"wax_off", "Wax Off"},
            {"scrape", "Scrape"},
            {"vibration", "Vibration"},
            {"sculk_charge", "Sculk Charge"},
            {"sculk_charge_pop", "Sculk Charge Pop"},
            {"sculk_soul", "Sculk Soul"},
            {"shriek", "Shriek"},
            {"sonic_boom", "Sonic Boom"},
            {"cherry_leaves", "Cherry Leaves"},
            {"gust", "Gust"},
            {"gust_emitter_small", "Gust Emitter (Small)"},
            {"gust_emitter_large", "Gust Emitter (Large)"},
            {"dust_plume", "Dust Plume"},
            {"dust_pillar", "Dust Pillar"},
            {"egg_crack", "Egg Crack"},
            {"ominous_spawning", "Ominous Spawning"},
            {"pale_oak_leaves", "Pale Oak Leaves"},
            {"item_cobweb", "Item Cobweb"},
            {"infested", "Infested"},
            {"raid_omen", "Raid Omen"},
            {"white_smoke", "White Smoke"},
            {"block_marker", "Block Marker"},
            {"block_crumble", "Block Crumble"},
    };
    /** Types that share another entry's config (OverflowParticles' redirectsTo). */
    private static final Map<String, String> REDIRECTS = Map.of("falling_dust", "block");
    /** Particles an explosion or firework triggers; they never fade. */
    private static final java.util.Set<String> FIREWORK_TRIGGERED = java.util.Set.of("explosion", "explosion_emitter", "firework");
    /** Particles you could farm information from; capped at 1x size, no multiplier. */
    private static final java.util.Set<String> UNFAIR = java.util.Set.of("block");
    /** Shown first and expanded, as in OverflowParticles' config tree. */
    public static final java.util.List<String> PRIORITY = java.util.List.of("crit", "enchanted_hit");

    private static Particles instance;

    public final BoolSetting cleanView = add(new BoolSetting("cleanView", "Clean view", false));
    public final BoolSetting staticColor = add(new BoolSetting("staticColor", "Static particle colour", false));
    public final BoolSetting noClip = add(new BoolSetting("noClip", "Particles no-clip", false));
    public final BoolSetting alwaysCritical = add(new BoolSetting("alwaysCritical", "Always show critical", false));
    public final BoolSetting alwaysSharp = add(new BoolSetting("alwaysSharp", "Always show sharpness", false));
    public final BoolSetting checkInvulnerable = add(new BoolSetting("checkInvulnerable", "Check invulnerability", false));

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Entry> lookup = new HashMap<>();

    public Particles() {
        super("particles", "Particles", Category.RENDER,
                "OverflowParticles: per-particle colour, size, fade and multiplier");
        instance = this;
        for (Setting<?> s : settings()) s.setGroup("General");
        for (String key : PRIORITY) addEntry(key);
        for (String[] p : VANILLA) if (!entries.containsKey(p[0])) addEntry(p[0]);
        lookup.putAll(entries);
        REDIRECTS.forEach((from, to) -> lookup.put(from, entries.get(to)));
    }

    private void addEntry(String key) {
        String name = null;
        for (String[] p : VANILLA) if (p[0].equals(key)) name = p[1];
        entries.put(key, new Entry(key, name));
    }

    public static Particles instance() { return instance; }

    public static boolean active() { return instance != null && instance.enabled(); }

    /** The config for a particle registry path, or null for untracked types. */
    public static Entry entry(String key) {
        return active() && key != null ? instance.lookup.get(key) : null;
    }

    public static Entry blocks() { return instance.entries.get("block"); }

    public java.util.Collection<Entry> entries() { return entries.values(); }

    private <S extends Setting<?>> S grouped(S setting, String group) {
        setting.setGroup(group);
        return add(setting);
    }

    public final class Entry {
        public final String key, name;
        public final BoolSetting enabled;
        public final BoolSetting customColor;
        public final ChoiceSetting colorMode;
        public final ColorSetting color;
        public final BoolSetting fade;
        public final IntSetting fadeStart;
        public final IntSetting size;
        public final IntSetting multiplier;
        /** Blocks only (OverflowParticles' BlockParticleEntry). */
        public final BoolSetting hideDigging, hideRunning;
        public final ChoiceSetting hideMode;

        Entry(String key, String name) {
            this.key = key;
            this.name = name;
            boolean blocks = key.equals("block");
            boolean unfair = UNFAIR.contains(key);
            enabled = grouped(new BoolSetting(key + ".enabled", "Enabled", true), name);
            if (blocks) {
                // The block entry replaces the per-particle page in the original.
                hideDigging = grouped(new BoolSetting(key + ".hideDigging", "Hide block digging particles", false), name);
                hideRunning = grouped(new BoolSetting(key + ".hideRunning", "Hide entity running / falling particles", false), name);
                hideMode = grouped(new ChoiceSetting(key + ".hideMode", "Mode", "ALL", "Visible entities only", "ALL"), name);
                customColor = null;
                colorMode = null;
                color = null;
                size = null;
                multiplier = null;
            } else {
                hideDigging = hideRunning = null;
                hideMode = null;
                customColor = grouped(new BoolSetting(key + ".customColor", "Custom colour", false), name);
                colorMode = grouped(new ChoiceSetting(key + ".colorMode", "Mode", "Multiply", "Multiply", "Override"), name);
                color = grouped(new ColorSetting(key + ".color", "Colour", 0xFFFFFFFF), name);
                size = grouped(new IntSetting(key + ".size", "Size", 100, 50, unfair ? 100 : 500, 10, "%"), name);
                multiplier = unfair ? null : grouped(new IntSetting(key + ".multiplier", "Multiplier", 100, 0, 1000, 10, "%"), name);
            }
            if (FIREWORK_TRIGGERED.contains(key)) {
                fade = null;
                fadeStart = null;
            } else {
                fade = grouped(new BoolSetting(key + ".fade", "Fade", false), name);
                fadeStart = grouped(new IntSetting(key + ".fadeStart", "Fade out start", 50, 0, 100, 1, "%"), name);
            }
        }

        public boolean isBlocks() { return key.equals("block"); }

        public boolean fading() { return fade != null && fade.get(); }

        public float fadeStartFraction() { return fadeStart == null ? 0f : fadeStart.get() / 100f; }

        public float sizeScale() { return size == null ? 1f : size.get() / 100f; }

        public float multiplierValue() { return multiplier == null ? 1f : multiplier.get() / 100f; }

        /** ParticleSpawner.color: channel is 0-255 from the custom colour. */
        public float color(int channel, float original) {
            if (customColor == null || !customColor.get()) return original;
            float scalar = colorMode.is("Override") ? 1f : original;
            return channel / 255f * scalar;
        }

        public int argb() { return color == null ? 0xFFFFFFFF : color.argb(); }
    }

}
