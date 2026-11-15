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
        return level.getMinY();
    }

    /** One above the highest block the world allows. */
    static int maxY(Level level) {
        return level.getMaxY() + 1;
    }

    static int minSectionY(Level level) {
        return level.getMinSectionY();
    }

    static ColorResolver dryFoliage() {
        return BiomeColors.FOLIAGE_COLOR_RESOLVER;
    }

    static int pixel(NativeImage image, int x, int y) {
        return image.getPixel(x, y);
    }
}
