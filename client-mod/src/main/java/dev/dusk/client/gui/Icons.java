package dev.dusk.client.gui;

/** Tiny pixel glyphs for the Dusk menus, drawn with fills so they work on every version. */
public enum Icons {
    GRID("###.###", "###.###", "###.###", ".......", "###.###", "###.###", "###.###"),
    PERSON("..###..", "..###..", "..###..", ".......", ".#####.", "#######", "#######"),
    PEOPLE(".##.##.", ".##.##.", ".......", "##.#.##", "##.#.##", "##.#.##", "......."),
    GEAR("..#.#..", ".#####.", "##...##", ".#...#.", "##...##", ".#####.", "..#.#.."),
    SLIDERS(".#.....", "#######", ".#.....", ".......", "....#..", "#######", "....#.."),
    HUD("#######", "#.....#", "#.##..#", "#.....#", "#...#.#", "#.....#", "#######"),
    EYE(".......", "..###..", ".#...#.", "#..#..#", ".#...#.", "..###..", "......."),
    ARROW(".......", "...#...", "....#..", "#######", "....#..", "...#...", "......."),
    DOTS(".......", ".......", ".......", "##.##.##", ".......", ".......", "......."),
    STAR("...#...", "...#...", "#######", ".#####.", "..###..", ".##.##.", ".#...#."),
    BACK("..#", ".#.", "#..", ".#.", "..#"),
    SEARCH(".###...", "#...#..", "#...#..", "#...#..", ".###...", ".....#.", "......#"),
    HEART(".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."),
    HEART_OUTLINE(".##.##.", "#..#..#", "#.....#", ".#...#.", "..#.#..", "...#..."),
    GLOBE("..###..", ".#.#.#.", "#######", "#..#..#", "#######", ".#.#.#.", "..###.."),
    REALMS("#.#.#.#", "#######", "#.....#", "#..#..#", "#.###.#", "#.###.#", "#######"),
    ACCESS("...#...", ".......", "#######", "...#...", "...#...", "..#.#..", ".#...#."),
    CLOSE("#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#");

    private final String[] rows;

    Icons(String... rows) {
        this.rows = rows;
    }

    public int width() {
        int w = 0;
        for (String r : rows) w = Math.max(w, r.length());
        return w;
    }

    public int height() { return rows.length; }

    public void draw(Canvas c, int x, int y, int color) {
        draw(c, x, y, color, 1);
    }

    /** Draws each glyph pixel as a {@code scale} x {@code scale} block. */
    public void draw(Canvas c, int x, int y, int color, int scale) {
        for (int ry = 0; ry < rows.length; ry++) {
            String r = rows[ry];
            int run = -1;
            for (int rx = 0; rx <= r.length(); rx++) {
                boolean on = rx < r.length() && r.charAt(rx) == '#';
                if (on && run < 0) run = rx;
                if (!on && run >= 0) {
                    c.fill(x + run * scale, y + ry * scale, x + rx * scale, y + (ry + 1) * scale, color);
                    run = -1;
                }
            }
        }
    }
}
