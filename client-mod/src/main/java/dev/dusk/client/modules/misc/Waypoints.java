package dev.dusk.client.modules.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.WaypointsScreen;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.render.WorldProjection;
import dev.dusk.client.waypoints.WaypointStore;
import dev.dusk.client.waypoints.WaypointStore.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints: a key drops one where you stand, another opens the list, and
 * each shows on screen with its name and distance. A marker is also left
 * where you last died. Overworld and Nether waypoints show in each other at
 * the ×8 scale, so a portal route is easy to follow. A beam of the
 * waypoint's colour rises from it and fades out as you arrive. The list
 * edits names, colours and coordinates and imports another world's points;
 * /waypoint does the same from chat ({@link dev.dusk.client.waypoints.WaypointCommands}).
 */
public class Waypoints extends Module {
    public static final int[] PALETTE = {
            0xFF55C8FF, 0xFFFFD24A, 0xFF7CE06A, 0xFFFF7ACB, 0xFFB08CFF, 0xFFFF9A4A, 0xFF5AE0C8, 0xFFF4F4F4};
    private static final int DEATH_COLOR = 0xFFFF5555;
    private static final String ALWAYS = "Always", NEAR = "Near crosshair", NEVER = "Never";
    private static final String OVERWORLD = "overworld", NETHER = "the_nether";
    // how close to the crosshair (GUI pixels) a marker has to be for its name in "Near crosshair" mode
    private static final int NAME_RADIUS = 40;

    private static Waypoints instance;

    private final KeySetting addKey = add(new KeySetting("waypoint_add", "Add waypoint", InputConstants.KEY_B), "Keybinds");
    private final KeySetting listKey = add(new KeySetting("waypoint_list", "Open waypoints", InputConstants.KEY_U), "Keybinds");

    private final ChoiceSetting names = add(new ChoiceSetting("names", "Show names", NEAR, ALWAYS, NEAR, NEVER), "Markers");
    private final BoolSetting distance = add(new BoolSetting("distance", "Show distance", true), "Markers");
    private final IntSetting scale = add(new IntSetting("scale", "Marker size", 10, 5, 20, 1, "x").decimals(1), "Markers");
    private final IntSetting maxDistance = add(new IntSetting("maxDistance", "Hide beyond (0 = never)", 0, 0, 10000, 250, " blocks"), "Markers");
    private final IntSetting nameDistance = add(new IntSetting("nameDistance", "Hide names beyond (0 = never)", 0, 0, 10000, 250, " blocks"), "Markers");
    private final BoolSetting beam = add(new BoolSetting("beam", "Light beam", true), "Markers");
    private final BoolSetting crossDimension = add(new BoolSetting("crossDimension", "Overworld/Nether in each other (x8)", true), "Markers");

    private final BoolSetting deathPoint = add(new BoolSetting("death", "Mark where you died", true), "Death");
    private final BoolSetting clearDeath = add(new BoolSetting("clearDeath", "Remove it once you get back", true), "Death");

    private boolean wasDead;
    private final float[] screen = new float[2];

    public Waypoints() {
        super("waypoints", "Waypoints", Category.MISC,
                "Save places with a key (B by default) and see them on screen; U opens the list. Also marks where you died.");
        instance = this;
        setEnabled(true);
    }

    @Nullable
    public static Waypoints active() {
        Waypoints m = instance;
        return m != null && m.enabled() ? m : null;
    }

    /* ── keys and death marker (every client tick) ── */

    public void tickKeys() {
        Minecraft mc = Minecraft.getInstance();
        while (addKey.mapping().consumeClick()) {
            if (enabled() && mc.player != null) quickAdd(mc.player);
        }
        while (listKey.mapping().consumeClick()) {
            if (enabled() && mc.player != null && Compat.currentScreen(mc) == null) {
                Compat.setScreen(mc, new WaypointsScreen(null));
            }
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            wasDead = false;
            return;
        }
        boolean dead = player.isDeadOrDying();
        if (dead && !wasDead && enabled() && deathPoint.get()) markDeath(player);
        wasDead = dead;
        if (!dead && enabled() && clearDeath.get()) clearReachedDeath(player);
    }

    private void quickAdd(LocalPlayer player) {
        Waypoint w = add(player, null);
        Compat.actionBar(player, Component.literal("Waypoint \"" + w.name + "\" added · "
                + listKey.mapping().getTranslatedKeyMessage().getString() + " to edit"));
    }

    /** A new waypoint where {@code player} stands; {@code name} null picks the next "Waypoint N". */
    public static Waypoint add(LocalPlayer player, @Nullable String name) {
        List<Waypoint> list = WaypointStore.current();
        int n = 0;
        for (Waypoint w : list) if (!w.death) n++;
        Waypoint w = new Waypoint(name != null ? name : "Waypoint " + (n + 1),
                Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ()),
                dimension(), PALETTE[n % PALETTE.length]);
        list.add(w);
        WaypointStore.save();
        return w;
    }

    private void markDeath(LocalPlayer player) {
        List<Waypoint> list = WaypointStore.current();
        list.removeIf(w -> w.death);
        Waypoint w = new Waypoint("Death", Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ()),
                dimension(), DEATH_COLOR);
        w.death = true;
        list.add(w);
        WaypointStore.save();
    }

    private static void clearReachedDeath(LocalPlayer player) {
        List<Waypoint> list = WaypointStore.current();
        String dim = dimension();
        if (list.removeIf(w -> w.death && w.dim.equals(dim)
                && player.distanceToSqr(w.x + 0.5, w.y, w.z + 0.5) < 4 * 4)) {
            WaypointStore.save();
        }
    }

    public static String dimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? OVERWORLD : Compat.keyPath(level.dimension());
    }

    /** Block position of {@code w} as seen from dimension {@code dim}, or null when it doesn't show there. */
    @Nullable
    public double[] positionIn(Waypoint w, String dim) {
        if (w.dim.equals(dim)) return new double[] {w.x, w.y, w.z};
        if (!crossDimension.get()) return null;
        if (w.dim.equals(OVERWORLD) && dim.equals(NETHER)) return new double[] {w.x / 8.0, w.y, w.z / 8.0};
        if (w.dim.equals(NETHER) && dim.equals(OVERWORLD)) return new double[] {w.x * 8.0, w.y, w.z * 8.0};
        return null;
    }

    /* ── markers (drawn under the HUD) ── */

    private record Marker(Waypoint w, float x, float y, double dist) {}

    public static void drawMarkers(Canvas c, HudContext ctx) {
        Waypoints m = active();
        if (m == null || !WorldProjection.ready() || ctx.player() == null) return;
        m.draw(c, ctx);
    }

    private void draw(Canvas c, HudContext ctx) {
        List<Waypoint> list = WaypointStore.current();
        if (list.isEmpty()) return;
        String dim = dimension();
        double px = WorldProjection.camX(), py = WorldProjection.camY(), pz = WorldProjection.camZ();
        int limit = maxDistance.get();
        List<Marker> markers = new ArrayList<>();
        for (Waypoint w : list) {
            if (!w.visible) continue;
            double[] pos = positionIn(w, dim);
            if (pos == null) continue;
            double x = pos[0] + 0.5, y = pos[1] + 1.0, z = pos[2] + 0.5;
            double dist = Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
            if (dist < 2 || (limit > 0 && dist > limit)) continue;
            if (!WorldProjection.project(x, y, z, ctx.width(), ctx.height(), screen)) continue;
            if (screen[0] < -50 || screen[0] > ctx.width() + 50 || screen[1] < -50 || screen[1] > ctx.height() + 50) continue;
            markers.add(new Marker(w, screen[0], screen[1], dist));
        }
        if (beam.get()) {
            for (Waypoint w : list) {
                if (!w.visible) continue;
                double[] pos = positionIn(w, dim);
                if (pos != null) drawBeam(c, ctx, w, pos, px, py, pz, limit);
            }
        }
        // far first, so nearer markers draw over them
        markers.sort((a, b) -> Double.compare(b.dist, a.dist));
        float s = scale.get() / 10F;
        float cx = ctx.width() / 2F, cy = ctx.height() / 2F;
        int nameLimit = nameDistance.get();
        for (Marker mk : markers) {
            boolean near = Math.abs(mk.x - cx) < NAME_RADIUS && Math.abs(mk.y - cy) < NAME_RADIUS;
            boolean showName = (names.is(ALWAYS) || (names.is(NEAR) && near)) && (nameLimit <= 0 || mk.dist <= nameLimit);
            drawMarker(c, mk, s, showName);
        }
    }

    private static final int BEAM_HEIGHT = 256, BEAM_SEGMENTS = 16;
    private final float[] beamA = new float[2], beamB = new float[2];

    /**
     * A thin column of the waypoint's colour from its block up into the sky,
     * projected in pieces so it bends correctly at the screen edges. It fades
     * in from 8 blocks away, so it doesn't stand in your face on arrival.
     */
    private void drawBeam(Canvas c, HudContext ctx, Waypoint w, double[] pos, double px, double py, double pz, int limit) {
        double x = pos[0] + 0.5, z = pos[2] + 0.5;
        double flat = Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz));
        if (limit > 0 && flat > limit) return;
        float fade = (float) Math.clamp((flat - 8) / 24.0, 0, 1);
        if (fade <= 0) return;
        int color = ((int) (fade * 0x90) << 24) | (w.color & 0xFFFFFF);
        double y0 = pos[1], step = (double) BEAM_HEIGHT / BEAM_SEGMENTS;
        boolean have = WorldProjection.project(x, y0, z, ctx.width(), ctx.height(), beamA);
        for (int i = 1; i <= BEAM_SEGMENTS; i++) {
            boolean next = WorldProjection.project(x, y0 + i * step, z, ctx.width(), ctx.height(), beamB);
            if (have && next) line(c, beamA[0], beamA[1], beamB[0], beamB[1], 2, color);
            beamA[0] = beamB[0];
            beamA[1] = beamB[1];
            have = next;
        }
    }

    private static void line(Canvas c, float x0, float y0, float x1, float y1, int width, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        int len = Math.round((float) Math.sqrt(dx * dx + dy * dy));
        if (len <= 0 || len > 4000) return;
        c.push();
        c.translate(x0, y0);
        c.rotate((float) Math.atan2(dy, dx));
        c.fill(0, -width / 2, len, width - width / 2, color);
        c.pop();
    }

    private void drawMarker(Canvas c, Marker mk, float s, boolean showName) {
        c.push();
        c.translate(mk.x, mk.y);
        c.scale(s, s);
        c.push();
        c.rotate((float) (Math.PI / 4));
        c.fill(-4, -4, 4, 4, 0xC0000000);
        c.fill(-3, -3, 3, 3, mk.w.color | 0xFF000000);
        c.pop();

        String label = showName ? mk.w.name : "";
        String dist = distance.get() ? formatDistance(mk.dist) : "";
        if (!label.isEmpty() || !dist.isEmpty()) {
            String gap = !label.isEmpty() && !dist.isEmpty() ? " " : "";
            int lw = c.textWidth(label), total = lw + c.textWidth(gap + dist);
            int x0 = -total / 2, y0 = -8 - c.lineHeight() - 2;
            c.fill(x0 - 2, y0 - 2, x0 + total + 2, y0 + c.lineHeight(), 0x80000000);
            if (!label.isEmpty()) c.text(label, x0, y0, 0xFFFFFFFF, false);
            if (!dist.isEmpty()) c.text(gap + dist, x0 + lw, y0, 0xFFB8B8B8, false);
        }
        c.pop();
    }

    public static String formatDistance(double d) {
        return d >= 10000 ? String.format(java.util.Locale.ROOT, "%.1fkm", d / 1000) : Math.round(d) + "m";
    }
}
