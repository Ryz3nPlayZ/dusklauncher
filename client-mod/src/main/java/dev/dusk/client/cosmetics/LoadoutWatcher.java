package dev.dusk.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.config.DuskConfig;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Picks up loadout changes made in the launcher while the game is running.
 *
 * <p>The launcher passes its own loadout file ({@code <data>/cosmetics.json})
 * as {@code -Ddusk.loadout}; the mod never writes it, so there is no race
 * with our own saves of {@code duskclient.json}. Polled once a second.
 */
public final class LoadoutWatcher {
    @Nullable private static final Path FILE = path();
    private static long lastModified = -1;
    private static int ticks;

    private LoadoutWatcher() {}

    /** Client tick. */
    public static void tick() {
        if (FILE == null || ++ticks < 20) return;
        ticks = 0;
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(FILE).toMillis();
        } catch (Exception e) {
            return;
        }
        if (mtime == lastModified) return;
        lastModified = mtime;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(FILE));
            if (!root.isJsonObject()) return;
            Map<String, JsonElement> loadout = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : ((JsonObject) root).entrySet()) loadout.put(e.getKey(), e.getValue());
            CosmeticsConfig cfg = DuskConfig.get().cosmetics;
            if (loadout.equals(cfg.loadout)) return; // first look: already written into duskclient.json at launch
            cfg.loadout = loadout;
            DuskConfig.save();
            CosmeticsManager.reloadLocal();
        } catch (Exception e) {
            // half-written file: read it again next poll
            lastModified = -1;
        }
    }

    @Nullable
    private static Path path() {
        String p = System.getProperty("dusk.loadout");
        return p == null || p.isBlank() ? null : Path.of(p);
    }
}
