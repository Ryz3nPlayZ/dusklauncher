package dev.dusk.client.gui;

import net.minecraft.network.chat.Component;

/**
 * The Dusk menus' icons. The modern look draws each as a Phosphor vector
 * glyph from the ui fonts ({@code font/icons.ttf}, U+E000 on in declaration
 * order, built by {@code tools/build-icon-font.py}: rebuild it when adding a
 * constant); the pixel look draws the tiny bitmap below with fills.
 */
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
    NEXT("#..", ".#.", "..#", ".#.", "#.."),
    LIST("#.#####", ".......", "#.#####", ".......", "#.#####", ".......", "#.#####"),
    SEARCH(".###...", "#...#..", "#...#..", "#...#..", ".###...", ".....#.", "......#"),
    GLOBE("..###..", ".#.#.#.", "#######", "#..#..#", "#######", ".#.#.#.", "..###.."),
    REALMS("#.#.#.#", "#######", "#.....#", "#..#..#", "#.###.#", "#.###.#", "#######"),
    ACCESS("...#...", ".......", "#######", "...#...", "...#...", "..#.#..", ".#...#."),
    CLOSE("#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"),
    CHECK("......#", ".....##", "#...##.", "##.##..", ".###...", "..#....", "......."),
    CROSS("##...##", "###.###", ".#####.", "..###..", ".#####.", "###.###", "##...##"),
    RESET("..###.#", ".#...##", "#...###", "#......", "#.....#", ".#...#.", "..###.."),
    PACK("..#####", "..#...#", "#####.#", "#...#.#", "#...###", "#...#..", "#####.."),
    SHADER("#..#..#", ".#.#.#.", "..###..", "#######", "..###..", ".#.#.#.", "#..#..#"),
    MOVE("...#...", "..###..", ".#.#.#.", "#######", ".#.#.#.", "..###..", "...#..."),
    DOWN(".....", "#####", ".###.", "..#.."),
    MEDIA("#######", "#.#...#", "#.##..#", "#.###.#", "#.##..#", "#.#...#", "#######"),
    PLAY("#......", "###....", "#####..", "#######", "#####..", "###....", "#......"),
    PAUSE("##..##", "##..##", "##..##", "##..##", "##..##", "##..##", "##..##"),
    FOLDER("###....", "#..#...", "#######", "#.....#", "#.....#", "#.....#", "#######"),
    CHAT(".#####.", "#.....#", "#.#.#.#", "#.....#", ".#####.", "..#....", ".#....."),
    GIFT("..#.#..", "...#...", "#######", "#..#..#", "#######", "#..#..#", "#######"),
    SPARKLE("...#...", "...#...", "..###..", "#######", "..###..", "...#...", "...#..."),
    RUN("....##.", "....##.", "..###..", ".#.##..", "...#.#.", "..#...#", ".#....."),
    WRENCH(".....##", "....#..", "....#.#", "...###.", "..#....", ".#.....", "#......"),
    PALETTE("..###..", ".#...#.", "#.#...#", "#...#.#", "#....#.", ".#.##..", "..#...."),
    SHIRT(".##.##.", "#######", "##...##", "..#.#..", "..#.#..", "..#.#..", "..###.."),
    MONITOR("#######", "#.....#", "#.....#", "#.....#", "#######", "...#...", "..###.."),
    KEYBOARD(".......", "#######", "#.#.#.#", "#######", "#.###.#", "#######", "......."),
    PIN("..###..", ".#...#.", ".#.#.#.", ".#...#.", "..#.#..", "..#.#..", "...#..."),
    CHART(".....#.", ".....#.", "...#.#.", "...#.#.", ".#.#.#.", ".#.#.#.", "#######"),
    FLAG("#######", "#.....#", "#....#.", "#.....#", "#######", "#......", "#......");

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

    /** Draws the icon in the {@code width() * scale} by {@code height() * scale} box at x, y. */
    public void draw(Canvas c, int x, int y, int color, int scale) {
        if (vectors()) {
            vector(c, x + width() * scale / 2f, y + height() * scale / 2f, Math.max(7, Math.max(width(), height())) * scale, color);
            return;
        }
        pixels(c, x, y, color, scale);
    }

    /**
     * Draws the icon centred on cx, cy: exact for the vector glyph, where a
     * floored corner would sit it up to half a GUI pixel up and left.
     */
    public void drawCentered(Canvas c, float cx, float cy, int color, int scale) {
        if (vectors()) {
            vector(c, cx, cy, Math.max(7, Math.max(width(), height())) * scale, color);
            return;
        }
        pixels(c, Math.round(cx - width() * scale / 2f), Math.round(cy - height() * scale / 2f), color, scale);
    }

    /** Whether icons draw as vector glyphs: the modern look, where the ui fonts (and so the glyphs) load. */
    public static boolean vectors() {
        return Theme.modern() && Sans.supported();
    }

    /** Font units the art of a glyph spans: its 256-unit Phosphor box is 8 units, the art about 6.5. */
    private static final float ART = 6.5f;

    /**
     * The vector glyph centred on cx, cy with its art about {@code size}
     * pixels across, in the current transform. The ui fonts are cut for the
     * scale it draws at, so it stays sharp.
     */
    public void vector(Canvas c, float cx, float cy, float size, int color) {
        float k = size / ART;
        Theme.sansPush();
        c.push();
        // the glyph's box is 8 units wide from the origin, centred 3.5 units down on the cap height
        c.translate(cx - 4 * k, cy - 3.5f * k);
        c.scale(k, k);
        c.text(Component.literal(String.valueOf((char) (0xE000 + ordinal()))), 0, 0, color, false);
        c.pop();
        Theme.sansPop();
    }

    /** Draws each glyph pixel as a {@code scale} x {@code scale} block. */
    public void pixels(Canvas c, int x, int y, int color, int scale) {
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
