package dev.dusk.client.module.setting;

import java.util.ArrayList;
import java.util.List;

/**
 * A fixed-size grid of ARGB pixels — the crosshair texture, drawn one
 * screen pixel per cell. Saved as a list of rows of ints so a config file
 * stays readable and diffable.
 */
public class PixelGridSetting extends Setting<int[][]> {
    private final int width;
    private final int height;

    public PixelGridSetting(String id, String name, int width, int height, int[][] defaultValue) {
        super(id, name, copy(defaultValue, width, height));
        this.width = width;
        this.height = height;
        this.value = copy(defaultValue, width, height);
    }

    public int width() { return width; }

    public int height() { return height; }

    public int pixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        return value[y][x];
    }

    public void setPixel(int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        value[y][x] = argb;
    }

    public void clear() {
        for (int[] row : value) java.util.Arrays.fill(row, 0);
    }

    /** Replaces the whole grid (preset buttons); the array is copied. */
    public void setGrid(int[][] grid) {
        this.value = copy(grid, width, height);
    }

    @Override
    public void set(int[][] v) {
        setGrid(v);
    }

    @Override
    public void reset() {
        this.value = copy(defaultValue(), width, height);
    }

    @Override
    public Object save() {
        List<List<Integer>> rows = new ArrayList<>(height);
        for (int y = 0; y < height; y++) {
            List<Integer> row = new ArrayList<>(width);
            for (int x = 0; x < width; x++) row.add(value[y][x]);
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void load(Object raw) {
        if (!(raw instanceof List<?> rows)) return;
        int[][] grid = new int[height][width];
        for (int y = 0; y < Math.min(height, rows.size()); y++) {
            if (!(rows.get(y) instanceof List<?> row)) continue;
            for (int x = 0; x < Math.min(width, row.size()); x++) {
                if (row.get(x) instanceof Number n) grid[y][x] = n.intValue();
            }
        }
        this.value = grid;
    }

    private static int[][] copy(int[][] src, int width, int height) {
        int[][] out = new int[height][width];
        if (src == null) return out;
        for (int y = 0; y < Math.min(height, src.length); y++) {
            int[] row = src[y];
            if (row == null) continue;
            System.arraycopy(row, 0, out[y], 0, Math.min(width, row.length));
        }
        return out;
    }
}
