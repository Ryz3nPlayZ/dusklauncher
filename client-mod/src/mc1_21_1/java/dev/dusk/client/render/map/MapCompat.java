package dev.dusk.client.render.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;

/** The small differences between game versions the map touches. */
final class MapCompat {
    private MapCompat() {
    }

    static int minY(Level level) {
        return level.getMinBuildHeight();
    }

    /** One above the highest block the world allows. */
    static int maxY(Level level) {
        return level.getMaxBuildHeight();
    }

    static int minSectionY(Level level) {
        return level.getMinSection();
    }

    static ColorResolver dryFoliage() {
        return BiomeColors.FOLIAGE_COLOR_RESOLVER;
    }

    static int pixel(NativeImage image, int x, int y) {
        int abgr = image.getPixelRGBA(x, y);
        return abgr & 0xFF00FF00 | abgr >> 16 & 0xFF | (abgr & 0xFF) << 16;
    }
}
