package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.modules.misc.Waypoints;
import dev.dusk.client.render.MapColors;
import dev.dusk.client.render.MapTexture;
import dev.dusk.client.waypoints.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/**
 * A small top-down map of the loaded chunks around you. Blocks take the
 * colour of their top texture with biome tints, slopes are lit from the
 * north-west so hills read in relief, and shallow water shows the ground
 * under it (colours and water follow Pl3xMap, see NOTICE). One pixel per
 * block column in a texture centred on the player that follows you in
 * steps; a few thousand columns are re-read each tick, so the whole map
 * refreshes about once a second and costs nothing per frame but the draw.
 */
public class Minimap extends HudElement {
    /** Texture side in blocks; covers the widest view rotated (160 × √2). */
    private static final int TEX = 256;
    /** Recentre once the player is this far from the texture's middle. */
    private static final int STEP = 32;
    /** Column reads per tick (a full pass every 1.6 s); a ceiling (the Nether) costs more per column. */
    private static final int BUDGET = 2048, CEILING_BUDGET = 512;
    /** Brightness per block of rise toward the north-west light, and the steepest step that counts. */
    private static final float RELIEF = 0.07f;
    private static final int MAX_STEP = 2;
    /** Water deeper than this is drawn as water only. */
    private static final int MAX_DEPTH = 12;

    private final IntSetting size = add(new IntSetting("size", "Size", 96, 48, 160, 8, "px"));
    private final IntSetting range = add(new IntSetting("range", "Blocks across", 64, 32, 160, 16, ""));
    private final BoolSetting rotate = add(new BoolSetting("rotate", "Rotate with view", true));
    private final BoolSetting showWaypoints = add(new BoolSetting("waypoints", "Show waypoints", true));
    private final BoolSetting showPlayers = add(new BoolSetting("players", "Show players", false));
    private final BoolSetting showMobs = add(new BoolSetting("mobs", "Show mobs", false));
    private final BoolSetting showCoords = add(new BoolSetting("coords", "Show coordinates", true));

    private final MapColors palette = new MapColors();
    private MapTexture texture;
    private final int[] heights = new int[TEX * TEX];
    private final int[] colors = new int[TEX * TEX];
    private ClientLevel level;
    /** World block at texture pixel (0, 0); MIN_VALUE until the first centring. */
    private int ox = Integer.MIN_VALUE, oz;
    /** Rows read since the last recentre; they go outwards from the middle. */
    private int pass;
    private boolean dirty;

    public Minimap() {
        super("minimap", "Minimap", "A small top-down map of the area around you.");
        setPosition(4, 40);
    }

    @Override
    protected void onDisable() {
        if (texture != null) texture.close();
        texture = null;
        level = null;
        ox = Integer.MIN_VALUE;
    }

    @Override
    public int width(HudContext ctx) {
        return size.get();
    }

    @Override
    public int height(HudContext ctx) {
        return size.get() + (showCoords.get() ? ctx.lineHeight() + 2 : 0);
    }

    /* ── reading the world ── */

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (texture == null) texture = new MapTexture(TEX);
        if (mc.level != level) {
            level = mc.level;
            ox = Integer.MIN_VALUE;
        }
        int px = Mth.floor(player.getX()), pz = Mth.floor(player.getZ());
        recentre(px, pz);

        boolean ceiling = level.dimensionType().hasCeiling();
        int rows = (ceiling ? CEILING_BUDGET : BUDGET) / TEX;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int py = Mth.floor(player.getY());
        for (int n = 0; n < rows; n++) {
            int k = pass++ % TEX;
            readRow(TEX / 2 + ((k & 1) == 0 ? k / 2 : -(k / 2 + 1)), ceiling, py, pos);
        }
        if (dirty) {
            texture.upload();
            dirty = false;
        }
    }

    /** Keeps the texture centred on the player, moving what's already read along with it. */
    private void recentre(int px, int pz) {
        int nx = Math.floorDiv(px, STEP) * STEP - TEX / 2, nz = Math.floorDiv(pz, STEP) * STEP - TEX / 2;
        if (ox != Integer.MIN_VALUE && Math.abs(px - (ox + TEX / 2)) < STEP && Math.abs(pz - (oz + TEX / 2)) < STEP) {
            return;
        }
        if (ox == Integer.MIN_VALUE || Math.abs(nx - ox) >= TEX || Math.abs(nz - oz) >= TEX) {
            Arrays.fill(heights, Integer.MIN_VALUE);
            Arrays.fill(colors, 0);
            pass = 0; // read from the middle out
        } else {
            shift(heights, nx - ox, nz - oz, Integer.MIN_VALUE);
            shift(colors, nx - ox, nz - oz, 0);
            pass = TEX / 2; // the new edges are far out, the regular pass gets there
        }
        ox = nx;
        oz = nz;
        for (int i = 0; i < TEX * TEX; i++) texture.set(i % TEX, i / TEX, colors[i]);
        dirty = true;
    }

    /** Moves what's known by (dx, dz) blocks; what comes into view is {@code empty} until read. */
    private static void shift(int[] a, int dx, int dz, int empty) {
        int[] old = a.clone();
        Arrays.fill(a, empty);
        for (int z = 0; z < TEX; z++) {
            int sz = z + dz;
            if (sz < 0 || sz >= TEX) continue;
            for (int x = 0; x < TEX; x++) {
                int sx = x + dx;
                if (sx >= 0 && sx < TEX) a[z * TEX + x] = old[sz * TEX + sx];
            }
        }
    }

    private void readRow(int row, boolean ceiling, int py, BlockPos.MutableBlockPos pos) {
        int z = oz + row;
        for (int col = 0; col < TEX; col++) {
            int x = ox + col;
            int i = row * TEX + col;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue; // keep what was seen before it unloaded
            }
            int north = row > 0 ? heights[i - TEX] : Integer.MIN_VALUE;
            int west = col > 0 ? heights[i - 1] : Integer.MIN_VALUE;
            int argb = column(x, z, ceiling, py, pos, i, north, west);
            if (argb != colors[i]) {
                colors[i] = argb;
                texture.set(col, row, argb);
                dirty = true;
            }
        }
    }

    /** The colour of the top of one column; stores its height for the columns south and east of it. */
    private int column(int x, int z, boolean ceiling, int py, BlockPos.MutableBlockPos pos, int i, int north, int west) {
        int y;
        if (ceiling) {
            // under a roof: down from head height to the floor, so walls round you show too
            y = py + 1;
            pos.set(x, y, z);
            for (int n = 0; n < 64 && level.getBlockState(pos).isAir(); n++) pos.setY(--y);
        } else {
            y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            pos.set(x, y, z);
        }
        BlockState state = level.getBlockState(pos);
        boolean water = state.getFluidState().is(FluidTags.WATER);
        int rgb = water ? 0 : palette.color(state, level, pos);
        // see through plants, glass, torches and the like
        for (int n = 0; n < 8 && rgb == MapColors.CLEAR && !state.isAir(); n++) {
            pos.setY(--y);
            state = level.getBlockState(pos);
            water = state.getFluidState().is(FluidTags.WATER);
            rgb = water ? 0 : palette.color(state, level, pos);
        }
        heights[i] = y;
        if (water) return water(y, pos);
        if (rgb == MapColors.CLEAR) return 0xFF000000;

        // lit from the north-west: rising toward the light is brighter, falling away darker
        int rise = step(y, north) + step(y, west);
        return MapColors.shade(rgb, 0.9f + RELIEF * rise);
    }

    private static int step(int y, int neighbour) {
        return neighbour == Integer.MIN_VALUE ? 0 : Mth.clamp(y - neighbour, -MAX_STEP, MAX_STEP);
    }

    /** Water over its floor: clear when shallow, bluer and darker as it deepens (Pl3xMap's fancy fluids). */
    private int water(int surface, BlockPos.MutableBlockPos pos) {
        int tint = MapColors.water(level, pos);
        int depth = 1;
        pos.setY(surface - 1);
        while (depth < MAX_DEPTH && level.getFluidState(pos).is(FluidTags.WATER)) pos.setY(surface - ++depth);
        int floor = depth < MAX_DEPTH ? palette.color(level.getBlockState(pos), level, pos) : MapColors.CLEAR;

        float d = depth * 0.025f;
        float dark = Math.min(0.45f, 1 - cube(1 - d / 1.5f));
        float cover = 1 - (float) Math.pow(1 - Math.min(1, d * 5), 5);
        int deep = MapColors.mix(tint, 0, dark);
        return MapColors.shade(floor == MapColors.CLEAR ? deep : MapColors.mix(floor, deep, cover), 1);
    }

    private static float cube(float v) {
        return v * v * v;
    }

    /* ── drawing ── */

    @Override
    public void render(Canvas c, HudContext ctx) {
        int s = size.get();
        c.fill(0, 0, s, s, 0xFF0E0E12);
        c.outline(0, 0, s, s, 0xFF3A3A44);
        LocalPlayer player = ctx.player();
        if (player == null || texture == null || ox == Integer.MIN_VALUE) {
            if (ctx.editing()) c.centeredText("Minimap", s / 2, s / 2 - 4, 0xFF8A8A96, false);
            return;
        }
        float t = ctx.partialTick();
        double px = Mth.lerp(t, player.xo, player.getX()), pz = Mth.lerp(t, player.zo, player.getZ());
        float yaw = Mth.lerp(t, player.yRotO, player.getYRot());
        // turns world offsets so the way you face points up (or leaves north up)
        float angle = rotate.get() ? (float) Math.toRadians(-(yaw + 180)) : 0;
        float cos = Mth.cos(angle), sin = Mth.sin(angle);
        float scale = (float) s / range.get();
        float half = s / 2f;

        c.scissor(1, 1, s - 1, s - 1);
        c.push();
        c.translate(half, half);
        c.rotate(angle);
        c.scale(scale, scale);
        c.translate((float) (ox - px), (float) (oz - pz));
        c.blit(MapTexture.ID, 0, 0, 0, 0, TEX, TEX, TEX, TEX);
        c.pop();

        if (showPlayers.get() || showMobs.get()) {
            int shown = 0;
            for (Entity e : ctx.level().entitiesForRendering()) {
                if (e == player || shown >= 128) continue;
                boolean isPlayer = e instanceof Player;
                if (isPlayer ? !showPlayers.get() : !showMobs.get() || !(e instanceof Mob) || !e.isAlive()) continue;
                double ex = Mth.lerp(t, e.xo, e.getX()) - px, ez = Mth.lerp(t, e.zo, e.getZ()) - pz;
                float sx = half + (float) (ex * cos - ez * sin) * scale;
                float sy = half + (float) (ex * sin + ez * cos) * scale;
                if (sx < 2 || sy < 2 || sx > s - 2 || sy > s - 2) continue;
                int color = isPlayer ? 0xFFFFFFFF : e instanceof Enemy ? 0xFFE04848 : 0xFFE8D25A;
                int x = (int) sx, y = (int) sy, r = isPlayer ? 2 : 1;
                c.fill(x - r - 1, y - r - 1, x + r + 1, y + r + 1, 0xFF000000);
                c.fill(x - r, y - r, x + r, y + r, color);
                shown++;
            }
        }

        Waypoints wp = Waypoints.active();
        if (wp != null && showWaypoints.get()) {
            String dim = Waypoints.dimension();
            for (WaypointStore.Waypoint w : WaypointStore.current()) {
                if (!w.visible) continue;
                double[] at = wp.positionIn(w, dim);
                if (at == null) continue;
                double ex = at[0] + 0.5 - px, ez = at[2] + 0.5 - pz;
                float sx = (float) (ex * cos - ez * sin) * scale, sy = (float) (ex * sin + ez * cos) * scale;
                // off the map: pinned to the edge, pointing the way
                float edge = half - 4, far = Math.max(Math.abs(sx), Math.abs(sy));
                if (far > edge) {
                    sx *= edge / far;
                    sy *= edge / far;
                }
                int x = Math.round(half + sx), y = Math.round(half + sy);
                c.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
                c.fill(x - 1, y - 1, x + 2, y + 2, 0xFF000000 | w.color);
            }
        }
        c.unscissor();

        drawArrow(c, half, half, rotate.get() ? 0 : (float) Math.toRadians(yaw + 180));
        drawNorth(c, s, half, cos, sin);

        if (showCoords.get()) {
            String xyz = Mth.floor(player.getX()) + ", " + Mth.floor(player.getY()) + ", " + Mth.floor(player.getZ());
            c.centeredText(xyz, s / 2, s + 2, 0xFFFFFFFF, true);
        }
    }

    /** The player: a small arrow, outlined so it reads on any ground. */
    private static void drawArrow(Canvas c, float x, float y, float angle) {
        c.push();
        c.translate(x, y);
        c.rotate(angle);
        arrow(c, 1, 0xFF000000);
        arrow(c, 0, 0xFFFFFFFF);
        c.pop();
    }

    private static void arrow(Canvas c, int grow, int argb) {
        c.fill(-1 - grow, -4 - grow, 1 + grow, -2, argb);
        c.fill(-2 - grow, -2, 2 + grow, 0, argb);
        c.fill(-3 - grow, 0, 3 + grow, 2 + grow, argb);
    }

    /** "N" on the rim where north is. */
    private static void drawNorth(Canvas c, int s, float half, float cos, float sin) {
        // north is (0, -1) in the world, turned like everything else
        float nx = sin, ny = -cos;
        float edge = half - 5, far = Math.max(Math.abs(nx), Math.abs(ny));
        int x = Math.round(half + nx / far * edge), y = Math.round(half + ny / far * edge);
        c.fill(x - 4, y - 5, x + 4, y + 4, 0xC0000000);
        c.centeredText("N", x, y - 4, 0xFFE8D25A, false);
    }
}
