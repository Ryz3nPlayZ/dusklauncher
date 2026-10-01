package dev.dusk.client.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.dusk.client.DuskClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every waypoint, per server or singleplayer world, in
 * {@code config/duskclient-waypoints.json}. Loaded once, written on each change.
 */
public final class WaypointStore {
    private WaypointStore() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Waypoint {
        public String name;
        public int x, y, z;
        /** Dimension path: overworld, the_nether, the_end. */
        public String dim;
        public int color;
        public boolean visible = true;
        /** Set by the automatic death marker, which replaces the previous one. */
        public boolean death;

        public Waypoint(String name, int x, int y, int z, String dim, int color) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.color = color;
        }
    }

    private static Map<String, List<Waypoint>> all;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("duskclient-waypoints.json");
    }

    private static Map<String, List<Waypoint>> all() {
        if (all == null) {
            all = new LinkedHashMap<>();
            try {
                Path path = file();
                if (Files.exists(path)) {
                    Map<String, List<Waypoint>> parsed = GSON.fromJson(Files.readString(path),
                            new TypeToken<LinkedHashMap<String, List<Waypoint>>>() {}.getType());
                    if (parsed != null) all = parsed;
                }
            } catch (Exception e) {
                DuskClient.LOGGER.warn("Could not read waypoints", e);
            }
        }
        return all;
    }

    public static void save() {
        if (all == null) return;
        all.values().removeIf(List::isEmpty);
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(all));
        } catch (Exception e) {
            DuskClient.LOGGER.warn("Could not save waypoints", e);
        }
    }

    /** Which save the player is in: a server's address or a world folder; null in the menus. */
    @Nullable
    public static String worldKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        IntegratedServer local = mc.getSingleplayerServer();
        if (local != null) {
            Path dir = local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return "world:" + (dir != null ? dir : local.getWorldData().getLevelName());
        }
        var server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) return "server:" + server.ip.trim().toLowerCase(Locale.ROOT);
        return null;
    }

    /** The current world's waypoints (live list; call {@link #save} after changing it). Empty in the menus. */
    public static List<Waypoint> current() {
        String key = worldKey();
        if (key == null) return new ArrayList<>();
        return all().computeIfAbsent(key, k -> new ArrayList<>());
    }

    /** Every other save that has waypoints, with how many, for importing. */
    public static Map<String, Integer> otherWorlds() {
        String here = worldKey();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : all().entrySet()) {
            if (e.getKey().equals(here)) continue;
            int n = 0;
            for (Waypoint w : e.getValue()) if (!w.death) n++;
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }

    /** Copies {@code key}'s waypoints into this world, skipping death markers and names already here; returns how many. */
    public static int importFrom(String key) {
        List<Waypoint> source = all().get(key);
        String here = worldKey();
        if (source == null || here == null || key.equals(here)) return 0;
        List<Waypoint> list = current();
        int added = 0;
        for (Waypoint w : source) {
            if (w.death || find(w.name) != null) continue;
            Waypoint copy = new Waypoint(w.name, w.x, w.y, w.z, w.dim, w.color);
            copy.visible = w.visible;
            list.add(copy);
            added++;
        }
        if (added > 0) save();
        return added;
    }

    /** This world's waypoint called {@code name} (ignoring case), or null. */
    @Nullable
    public static Waypoint find(String name) {
        for (Waypoint w : current()) if (w.name.equalsIgnoreCase(name)) return w;
        return null;
    }

    /** "server:play.example.net" → "play.example.net (server)" for lists. */
    public static String describe(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return key;
        String kind = key.substring(0, colon), name = key.substring(colon + 1);
        return name + (kind.equals("server") ? "  ·  server" : "  ·  singleplayer");
    }
}
