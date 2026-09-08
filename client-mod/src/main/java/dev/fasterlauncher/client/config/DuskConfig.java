package dev.fasterlauncher.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Launcher-shared client settings (background override, menu prefs).
 * Stored as {@code config/duskclient.json} so the launcher can read and
 * write the same file for the in-game background picker later.
 */
public final class DuskConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Absolute path to a PNG/JPG background. Empty = vanilla panorama. */
    public String backgroundPath = "";
    public boolean showAccountTile = true;

    private static DuskConfig instance;

    private DuskConfig() {}

    public static synchronized DuskConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(instance));
        } catch (Exception e) {
            // best-effort persistence
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("duskclient.json");
    }

    private static DuskConfig load() {
        try {
            Path path = file();
            if (Files.exists(path)) {
                DuskConfig parsed = GSON.fromJson(Files.readString(path), DuskConfig.class);
                if (parsed != null) return parsed;
            }
        } catch (Exception e) {
            // corrupt config: start fresh rather than crash
        }
        return new DuskConfig();
    }
}
