package dev.dusk.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Read-only client of the Dusk cosmetics service: what another Dusk player
 * has equipped, as registry ids (the textures are bundled in this jar). The
 * base URL comes from {@code -Ddusk.api}, which the launcher passes.
 * Blocking; worker thread only.
 */
public final class DuskProvider {
    private static final Logger LOG = LogManager.getLogger("duskclient/dusk");
    public static final String DEFAULT_API = "https://dusk.129-213-43-152.sslip.io";

    /** A player's published loadout; {@code cape} is -1 when none is equipped. */
    public static final class Loadout {
        public final int cape;
        public final List<Integer> accessories;
        public final boolean dusk;

        Loadout(int cape, List<Integer> accessories, boolean dusk) {
            this.cape = cape;
            this.accessories = accessories;
            this.dusk = dusk;
        }
    }

    private DuskProvider() {}

    public static String apiBase() {
        String s = System.getProperty("dusk.api", DEFAULT_API).trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** A player's loadout; null when they have none, or on any failure. */
    public static Loadout fetch(UUID uuid) {
        if (uuid.version() != 4) return null; // offline players have no Dusk account
        byte[] json = MinecraftCapesProvider.download(apiBase() + "/v1/loadout/" + uuid.toString().replace("-", ""));
        if (json == null) return null;
        try {
            JsonObject o = new JsonParser().parse(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement cape = o.get("cape");
            int capeId = cape != null && cape.isJsonPrimitive() && cape.getAsJsonPrimitive().isNumber() ? cape.getAsInt() : -1;
            List<Integer> acc = new ArrayList<>();
            JsonElement a = o.get("accessories");
            if (a != null && a.isJsonArray()) {
                for (JsonElement v : (JsonArray) a) {
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) acc.add(v.getAsInt());
                }
            }
            JsonElement dusk = o.get("dusk");
            boolean isDusk = dusk != null && dusk.isJsonPrimitive() && dusk.getAsJsonPrimitive().isBoolean() && dusk.getAsBoolean();
            return new Loadout(capeId, Collections.unmodifiableList(acc), isDusk);
        } catch (Exception e) {
            LOG.warn("Unparseable Dusk loadout for {}", uuid);
            return null;
        }
    }
}
