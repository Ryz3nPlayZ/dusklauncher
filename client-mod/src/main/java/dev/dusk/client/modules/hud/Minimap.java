package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.modules.misc.Waypoints;
import dev.dusk.client.render.map.MapProjection;
import dev.dusk.client.render.map.MapTiles;
import dev.dusk.client.waypoints.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * A small angled map of the loaded chunks around you, always facing north.
 * The terrain is ray traced into tiles on background threads with block
 * colours, sunlight, shadows and water (the look and baker are ported from
 * Diorama, see NOTICE). Underground and in the Nether it shows a slice cut
 * through the world at your height.
 */
public class Minimap extends HudElement {
    private final IntSetting size = add(new IntSetting("size", "Size", 96, 48, 160, 8, "px"));
    private final IntSetting range = add(new IntSetting("range", "Blocks across", 64, 32, 160, 16, ""));
    private final BoolSetting showWaypoints = add(new BoolSetting("waypoints", "Show waypoints", true));
    private final BoolSetting showPlayers = add(new BoolSetting("players", "Show players", false));
    private final BoolSetting showMobs = add(new BoolSetting("mobs", "Show mobs", false));
    private final BoolSetting showCoords = add(new BoolSetting("coords", "Show coordinates", true));

    private MapTiles tiles;

    public Minimap() {
        super("minimap", "Minimap", "A small angled map of the area around you.");
        setPosition(4, 40);
    }

    @Override
    protected void onDisable() {
        closeTiles();
    }

    private void closeTiles() {
        if (tiles != null) tiles.close();
        tiles = null;
    }

    @Override
    public int width(HudContext ctx) {
        return size.get();
    }

    @Override
    public int height(HudContext ctx) {
        return size.get() + (showCoords.get() ? ctx.lineHeight() + 2 : 0);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            closeTiles();
            return;
        }
        if (tiles == null) tiles = new MapTiles();
        tiles.tick(mc.level, player, range.get());
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        int s = size.get();
        c.fill(0, 0, s, s, 0xFF0E0E12);
        c.outline(0, 0, s, s, 0xFF3A3A44);
        LocalPlayer player = ctx.player();
        if (player == null || tiles == null) {
            if (ctx.editing()) c.centeredText("Minimap", s / 2, s / 2 - 4, 0xFF8A8A96, false);
            return;
        }
        float t = ctx.partialTick();
        double px = Mth.lerp(t, player.xo, player.getX());
        double py = Mth.lerp(t, player.yo, player.getY());
        double pz = Mth.lerp(t, player.zo, player.getZ());
        float yaw = Mth.lerp(t, player.yRotO, player.getYRot());
        float scale = (float) s / range.get();
        float half = s / 2f;

        c.scissor(1, 1, s - 1, s - 1);
        tiles.draw(c, half, half, px, MapProjection.v(py, pz), scale);

        if (showPlayers.get() || showMobs.get()) {
            int shown = 0;
            for (Entity e : ctx.level().entitiesForRendering()) {
                if (e == player || shown >= 128) continue;
                boolean isPlayer = e instanceof Player;
                if (isPlayer ? !showPlayers.get() : !showMobs.get() || !(e instanceof Mob) || !e.isAlive()) continue;
                float sx = half + mapX(Mth.lerp(t, e.xo, e.getX()) - px, scale);
                float sy = half + mapY(Mth.lerp(t, e.yo, e.getY()) - py, Mth.lerp(t, e.zo, e.getZ()) - pz, scale);
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
                float sx = mapX(at[0] + 0.5 - px, scale);
                float sy = mapY(at[1] + 0.5 - py, at[2] + 0.5 - pz, scale);
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

        drawArrow(c, half, half, (float) Math.toRadians(yaw + 180));
        c.fill(s / 2 - 4, 0, s / 2 + 4, 9, 0xC0000000);
        c.centeredText("N", s / 2, 1, 0xFFE8D25A, false);

        if (showCoords.get()) {
            String xyz = Mth.floor(player.getX()) + ", " + Mth.floor(player.getY()) + ", " + Mth.floor(player.getZ());
            c.centeredText(xyz, s / 2, s + 2, 0xFFFFFFFF, true);
        }
    }

    /** Screen offset, in pixels, of a world offset from the player. */
    private static float mapX(double dx, float scale) {
        return (float) dx * scale;
    }

    private static float mapY(double dy, double dz, float scale) {
        return (float) (MapProjection.v(dy, dz)) * scale;
    }

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
}
