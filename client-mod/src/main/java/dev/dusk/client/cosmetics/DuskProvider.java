package dev.dusk.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only client of the Dusk cosmetics service (server/): what another
 * Dusk player has equipped, as registry ids. The textures themselves are
 * bundled in this jar, so the wire format is just ids and the server never
 * serves images.
 *
 * <p>The base URL comes from {@code -Ddusk.api=...} (the launcher passes
 * it) and falls back to the production host. Blocking; worker thread only.
 */
public final class DuskProvider {
    private static final Logger LOG = LoggerFactory.getLogger("duskclient/dusk");
    public static final String DEFAULT_API = "https://dusk.129-213-43-152.sslip.io";

    /** A player's published loadout. {@code cape} is -1 when none is equipped. */
    public record Loadout(int cape, List<Integer> accessories) {
        public boolean isEmpty() {
            return cape < 0 && accessories.isEmpty();
        }
    }

    private DuskProvider() {}

    public static String apiBase() {
        String s = System.getProperty("dusk.api", DEFAULT_API).trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Fetch a player's loadout; null when they have none, or on any failure. */
    @Nullable
    public static Loadout fetch(UUID uuid) {
        if (uuid.version() != 4) return null; // offline players have no Dusk account
        byte[] json = MinecraftCapesProvider.download(apiBase() + "/v1/loadout/" + uuid.toString().replace("-", ""));
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement cape = o.get("cape");
            int capeId = cape != null && cape.isJsonPrimitive() && cape.getAsJsonPrimitive().isNumber() ? cape.getAsInt() : -1;
            List<Integer> acc = new ArrayList<>();
            JsonElement a = o.get("accessories");
            if (a != null && a.isJsonArray()) {
                for (JsonElement v : (JsonArray) a) {
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) acc.add(v.getAsInt());
                }
            }
            return new Loadout(capeId, List.copyOf(acc));
        } catch (Exception e) {
            LOG.warn("Unparseable Dusk loadout for {}", uuid);
            return null;
        }
    }
}
