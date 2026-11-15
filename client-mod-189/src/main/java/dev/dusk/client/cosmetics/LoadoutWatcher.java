package dev.dusk.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.config.DuskConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Picks up loadout changes made in the launcher while the game runs. The
 * launcher passes its own loadout file as {@code -Ddusk.loadout}; the mod
 * never writes it. Polled once a second from the client tick.
 */
public final class LoadoutWatcher {
    private static final File FILE = file();
    private static long lastModified = -1;
    private static int ticks;

    private LoadoutWatcher() {}

    public static void tick() {
        if (FILE == null || ++ticks < 20) return;
        ticks = 0;
        long mtime = FILE.lastModified();
        if (mtime == 0L || mtime == lastModified) return;
        lastModified = mtime;
        try {
            JsonElement root = new JsonParser().parse(new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            Map<String, JsonElement> loadout = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : ((JsonObject) root).entrySet()) loadout.put(e.getKey(), e.getValue());
            CosmeticsConfig cfg = DuskConfig.get().cosmetics;
            if (loadout.equals(cfg.loadout)) return; // already written into duskclient.json at launch
            cfg.loadout = loadout;
            DuskConfig.save();
            CosmeticsManager.reloadLocal();
        } catch (Exception e) {
            lastModified = -1; // half-written file: read it again next poll
        }
    }

    private static File file() {
        String p = System.getProperty("dusk.loadout");
        return p == null || p.trim().isEmpty() ? null : new File(p);
    }
}
