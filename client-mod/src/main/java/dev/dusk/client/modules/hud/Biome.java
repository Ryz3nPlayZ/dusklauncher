package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;

import java.util.HashMap;
import java.util.Map;

/** Flex-HUD's Biome Display: "Biome: plains", the name in a colour that suits the biome. */
public class Biome extends TextHud {
    private static final String PREFIX = "Biome: ";
    private static final Map<String, Integer> COLORS = new HashMap<>();

    static {
        color(0xFFFFFFFF, "the_void");
        color(0xFF5E9D34, "plains");
        color(0xFFFCD500, "sunflower_plains");
        color(0xFF9DBCF0, "snowy_plains", "ice_spikes", "snowy_taiga", "snowy_slopes", "frozen_river", "snowy_beach", "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean");
        color(0xFFE5D9AF, "desert", "beach");
        color(0xFF436024, "swamp", "mangrove_swamp");
        color(0xFF4D8A25, "forest", "windswept_hills", "windswept_forest", "grove");
        color(0xFFFC7DEA, "flower_forest");
        color(0xFFCCCCCC, "birch_forest", "old_growth_birch_forest");
        color(0xFF366821, "dark_forest");
        color(0xFF4D6A4C, "old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga");
        color(0xFF807B39, "savanna", "savanna_plateau", "windswept_savanna");
        color(0xFFFF4D8A, "windswept_gravelly_hills");
        color(0xFF1C6E06, "jungle", "sparse_jungle");
        color(0xFF678C39, "bamboo_jungle");
        color(0xFFB15B25, "badlands", "eroded_badlands", "wooded_badlands");
        color(0xFF8D8D8D, "meadow", "frozen_peaks", "jagged_peaks", "stony_peaks", "stony_shore");
        color(0xFFBF789B, "cherry_grove");
        color(0xFF005EEC, "river", "ocean", "deep_ocean");
        color(0xFF00FCCF, "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean");
        color(0xFF746F82, "mushroom_fields");
        color(0xFF816759, "dripstone_caves");
        color(0xFF576B2C, "lush_caves");
        color(0xFF0D1F25, "deep_dark");
        color(0xFF880F0F, "nether_wastes", "crimson_forest");
        color(0xFF0F8976, "warped_forest");
        color(0xFF62493B, "soul_sand_valley");
        color(0xFF55576A, "basalt_deltas");
        color(0xFF6D00A3, "the_end", "end_highlands", "end_midlands", "small_end_islands", "end_barrens");
        color(0xFF979F96, "pale_garden");
        color(0xFFBDAF66, "sulfur_caves");
        color(0xFFE68E30, "dappled_forest");
    }

    private static void color(int argb, String... paths) {
        for (String p : paths) COLORS.put(p, argb);
    }

    private final BoolSetting biomeColor = add(new BoolSetting("biomeSpecificColor", "Biome colours", true));

    public Biome() {
        super("biome", "Biome", "The biome you are standing in.");
        setPosition(5, 93);
    }

    /** The biome's id path ("plains"), or null. */
    private static String biome(HudContext ctx) {
        var p = ctx.player();
        var level = ctx.level();
        if (p == null || level == null) return null;
        return level.getBiome(p.getOnPos()).unwrapKey().map(Compat::keyPath).orElse(null);
    }

    @Override
    protected String text(HudContext ctx) {
        String b = biome(ctx);
        return b == null ? null : PREFIX + b;
    }

    @Override
    protected String sample() {
        return PREFIX + "plains";
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        String v = current(ctx);
        if (v == null) return;
        String name = v.substring(PREFIX.length());
        int prefixWidth = c.textWidth(PREFIX);
        c.text(PREFIX, 0, 0, textColor(), shadow.get());
        int nameColor = biomeColor.get() ? COLORS.getOrDefault(name, 0xFFFFFFFF) : textColor();
        c.text(name, prefixWidth, 0, nameColor, shadow.get());
    }
}
