package dev.dusk.client.render.map;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.ColorResolver;

/**
 * Notes the biome colour a block's tint code asks for. {@link TintWorld} is the empty world handed
 * to that code; knowing which colour it wants, the map blends that colour itself.
 */
abstract class TintProbe {
    private ColorResolver asked;

    final void reset() {
        asked = null;
    }

    final boolean askedForBiome() {
        return asked != null;
    }

    final ColorResolver asked() {
        return asked;
    }

    final void record(ColorResolver resolver) {
        if (asked == null) {
            asked = resolver;
        }
    }

    final byte kind() {
        ColorResolver resolver = asked;
        if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
            return BlockLooks.TINT_GRASS;
        }
        if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
            return BlockLooks.TINT_FOLIAGE;
        }
        if (resolver == MapCompat.dryFoliage()) {
            return BlockLooks.TINT_DRY_FOLIAGE;
        }
        if (resolver == BiomeColors.WATER_COLOR_RESOLVER || resolver == BiomeTints.water()) {
            return BlockLooks.TINT_WATER;
        }
        return BlockLooks.TINT_NONE;
    }
}
