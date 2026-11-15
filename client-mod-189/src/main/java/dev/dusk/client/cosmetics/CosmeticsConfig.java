package dev.dusk.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code cosmetics} block of {@code config/duskclient.json}.
 *
 * <p>{@link #loadout} is written by the launcher at launch time (slot →
 * id, see docs/COSMETICS.md §1.3) and is kept as raw JSON so the launcher
 * never has to know which slots this mod version understands. The other
 * flags are the in-game toggles.
 */
public final class CosmeticsConfig {
    /** slot → id (or the {@code settings} object). */
    public Map<String, JsonElement> loadout = new LinkedHashMap<>();
    /** Render other players' cosmetics (Dusk service and MinecraftCapes). */
    public boolean showOthers = true;
    /** Query api.minecraftcapes.net at all (self and others). */
    public boolean minecraftCapes = true;
    /** Hide Mojang/Migrator/etc. capes for players without a custom cape. */
    public boolean hideOfficialCapes = false;

    /** The registry cape the local player has equipped, or -1. */
    public int capeId() {
        return slot("cape");
    }

    /** The registry accessories the local player has equipped ({@code accessories: [ids]}). */
    public List<Integer> accessoryIds() {
        List<Integer> out = new ArrayList<>();
        if (loadout == null) return out;
        JsonElement e = loadout.get("accessories");
        if (e == null || !e.isJsonArray()) return out;
        JsonArray a = e.getAsJsonArray();
        for (JsonElement v : a) {
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) out.add(v.getAsInt());
        }
        return out;
    }

    public int slot(String name) {
        if (loadout == null) return -1;
        JsonElement e = loadout.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return -1;
        return e.getAsInt();
    }
}
