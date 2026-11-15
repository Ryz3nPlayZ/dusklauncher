package dev.dusk.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.cosmetics.CosmeticsConfig;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * The menu settings shared with the 1.21.11 build and the launcher, in
 * {@code config/duskclient.json}. The 1.8.9 build only knows the menu fields;
 * everything else in the file (cosmetics, recording, favourites) is kept as it
 * was when this writes the file back.
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
    /** Title screen: Dusk's menu (true), or the vanilla one. */
    public boolean duskTitle = true;
    /** HUD text in the smooth sans (true), or the Minecraft font. */
    public boolean hudSmoothFont = true;
    /** Keyboard editor layout: "100", "tkl" or "60". */
    public String keyboardLayout = "100";
    /** macOS: render at the screen's real resolution on Retina displays (read once, at window creation). */
    public boolean retina = true;
    /** macOS: size and place crosshairs (custom and texture pack) so their pixels land on whole screen pixels. */
    public boolean crispCrosshair = false;
    /** Writes frame times and what slow frames waited for to logs/dusk-perf.log. */
    public boolean perfLog = false;
    /** Cape and accessory choices, written by the launcher's wardrobe. */
    public CosmeticsConfig cosmetics = new CosmeticsConfig();

    private static DuskConfig instance;
    /** The file as last read, so fields this build doesn't know survive a save. */
    private static JsonObject raw = new JsonObject();

    private DuskConfig() {}

    public static synchronized DuskConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static synchronized void save() {
        if (instance == null) return;
        try {
            File file = file();
            file.getParentFile().mkdirs();
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : raw.entrySet()) out.add(e.getKey(), e.getValue());
            for (Map.Entry<String, JsonElement> e : GSON.toJsonTree(instance).getAsJsonObject().entrySet()) {
                out.add(e.getKey(), e.getValue());
            }
            raw = out;
            Files.write(file.toPath(), GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // best-effort persistence
        }
    }

    private static File file() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "duskclient.json");
    }

    private static DuskConfig load() {
        try {
            File file = file();
            if (file.isFile()) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JsonElement tree = new JsonParser().parse(text);
                if (tree != null && tree.isJsonObject()) {
                    raw = tree.getAsJsonObject();
                    DuskConfig parsed = GSON.fromJson(tree, DuskConfig.class);
                    if (parsed != null) {
                        if (parsed.backgroundPath == null) parsed.backgroundPath = "";
                        if (parsed.uiStyle == null) parsed.uiStyle = "dusk";
                        if (parsed.keyboardLayout == null) parsed.keyboardLayout = "100";
                        if (parsed.cosmetics == null) parsed.cosmetics = new CosmeticsConfig();
                        return parsed;
                    }
                }
            }
        } catch (Exception e) {
            // corrupt config: start fresh rather than crash
        }
        return new DuskConfig();
    }
}
