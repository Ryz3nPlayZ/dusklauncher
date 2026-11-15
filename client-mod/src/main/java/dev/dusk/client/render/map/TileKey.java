package dev.dusk.client.render.map;

/** One square of the map: which view ({@link #SURFACE}, or a cave cut at that height) and where. */
record TileKey(int layer, int tileU, int tileV) {
    static final int SURFACE = Integer.MIN_VALUE;

    boolean isCave() {
        return layer != SURFACE;
    }

    int minU() {
        return tileU * MapProjection.TILE_UNITS;
    }

    int minV() {
        return tileV * MapProjection.TILE_UNITS;
    }
}
