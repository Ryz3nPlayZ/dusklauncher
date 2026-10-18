package dev.dusk.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dusk.client.cosmetics.CosmeticsConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    /** Look of every Dusk screen: "dusk" (the launcher's pixel frames) or "dawn" (flat dark buttons). */
    public String uiStyle = "dusk";
    /** Title screen: the launcher's animated scene (true) or the vanilla panorama. */
    public boolean titleScene = true;
    /** Title screen: Dusk's menu (true), or the vanilla one, which another mod's own menus can then replace. */
    public boolean duskTitle = true;
    /** HUD text in the smooth sans, sharp at any GUI scale (true), or the Minecraft font. */
    public boolean hudSmoothFont = true;
    /** Cosmetics loadout + toggles (docs/COSMETICS.md). Written by the launcher. */
    public CosmeticsConfig cosmetics = new CosmeticsConfig();
    /** Module ids hearted in the Dusk menu, shown under its favourites tab. */
    public List<String> favorites = new ArrayList<>();
    /** Media recording: "off", "clips" (a rolling buffer the clip key saves) or "full" (every session to a replay). */
    public String recordingMode = "off";
    /** How far back a clip reaches, in seconds. */
    public int clipSeconds = 30;
    /** Screen recording: "off", "manual" (the record key starts and stops it) or "buffer" (also keeps the last stretch for the clip key). */
    public String videoMode = "off";
    /** How much the screen buffer keeps, in seconds. */
    public int videoSeconds = 30;
    public int videoFps = 60;
    /** "standard", "high" or "ultra": the bitrate per pixel. */
    public String videoQuality = "high";
    /** The tallest the video gets: "native", "1440", "1080" or "720" (never upscaled). */
    public String videoResolution = "1440";
    /** "h264", or "hevc" where the GPU encodes it (smaller files, same look). */
    public String videoCodec = "h264";
    /** Whether videos show the HUD and menus, or the world alone. */
    public boolean videoHud = true;
    /** Whether videos carry the game's sound. */
    public boolean videoAudio = true;
    /** The red REC mark in the corner while a recording runs (videos with the HUD show it too). */
    public boolean videoIndicator = true;
    /** The Keybinds keyboard's size: "60", "75" or "100" (with the numpad). */
    public String keyboardLayout = "100";
    /** In game: a toast when a friend messages you, comes online or sends a request. */
    public boolean friendToasts = true;

    private static DuskConfig instance;

    private DuskConfig() {}

    public static synchronized DuskConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Re-read the file (the launcher rewrites the cosmetics block before each launch). */
    public static synchronized DuskConfig reload() {
        instance = load();
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
                if (parsed != null) {
                    if (parsed.cosmetics == null) parsed.cosmetics = new CosmeticsConfig();
                    return parsed;
                }
            }
        } catch (Exception e) {
            // corrupt config: start fresh rather than crash
        }
        return new DuskConfig();
    }
}
