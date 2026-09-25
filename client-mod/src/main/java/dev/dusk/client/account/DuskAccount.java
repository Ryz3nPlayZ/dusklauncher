package dev.dusk.client.account;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.DuskProvider;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Dusk cosmetics service as the signed-in player (what the launcher's
 * dusk.rs does): sign-in by joining a random server id at Mojang's session
 * server, reading what the account owns and publishing the loadout. Shares
 * the launcher's token cache and loadout file in its data directory.
 * Blocking; worker threads only.
 */
public final class DuskAccount {
    private static final String MOJANG_JOIN = "https://sessionserver.mojang.com/session/minecraft/join";

    /** {@code GET /v1/me}: owned registry ids and the published loadout. */
    public record Me(Set<Integer> owned, Map<String, JsonElement> loadout) {}

    private DuskAccount() {}

    /** The launcher's data directory (parent of {@code -Ddusk.loadout}), or null outside the launcher. */
    @Nullable
    public static Path dataDir() {
        String p = System.getProperty("dusk.loadout");
        if (p == null || p.isBlank()) return null;
        Path parent = Path.of(p).toAbsolutePath().getParent();
        return parent;
    }

    public static Me me() throws IOException {
        JsonObject o = obj(call("GET", "/v1/me", null));
        Set<Integer> owned = new LinkedHashSet<>();
        if (o.has("owned") && o.get("owned").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("owned")) if (e.isJsonPrimitive()) owned.add(e.getAsInt());
        }
        return new Me(owned, map(o.get("loadout")));
    }

    /** What the account owns when the service cannot be reached: the launcher's cache. */
    public static Set<Integer> cachedOwned() {
        Set<Integer> owned = new LinkedHashSet<>();
        Path dir = dataDir();
        if (dir == null) return owned;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(dir.resolve("inventory.json"))).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("owned")) owned.add(e.getAsInt());
        } catch (Exception ignored) {
        }
        return owned;
    }

    /**
     * Publish {@code loadout} (absent slots are unequipped), then wear what
     * the service answered: the launcher's loadout file and this game's config.
     */
    public static Map<String, JsonElement> publish(Map<String, JsonElement> loadout) throws IOException {
        JsonObject body = new JsonObject();
        loadout.forEach(body::add);
        Map<String, JsonElement> saved = map(call("PUT", "/v1/me/loadout", body));
        Path dir = dataDir();
        if (dir != null) {
            JsonObject out = new JsonObject();
            saved.forEach(out::add);
            try {
                Files.writeString(dir.resolve("cosmetics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(out));
            } catch (IOException ignored) {
                // the launcher refetches from the service anyway
            }
        }
        Minecraft.getInstance().execute(() -> {
            DuskConfig.get().cosmetics.loadout = new LinkedHashMap<>(saved);
            DuskConfig.save();
            CosmeticsManager.reloadLocal();
        });
        return saved;
    }

    // ---- auth -----------------------------------------------------------------

    private static JsonElement call(String method, String path, @Nullable JsonElement body) throws IOException {
        String token = token();
        for (int attempt = 0; ; attempt++) {
            Http.Response r = Http.json(method, DuskProvider.apiBase() + path, token, body);
            if (r.code() == 401 && attempt == 0) {
                token = signIn();
                continue;
            }
            if (!r.ok()) throw new IOException(r.error("Dusk service"));
            return r.json();
        }
    }

    private static String token() throws IOException {
        Path dir = dataDir();
        if (dir != null) {
            try {
                JsonObject t = JsonParser.parseString(Files.readString(dir.resolve("dusk-session.json"))).getAsJsonObject();
                String self = undashed(Minecraft.getInstance().getUser().getProfileId().toString());
                if (self.equals(undashed(t.get("uuid").getAsString()))) return t.get("token").getAsString();
            } catch (Exception ignored) {
                // none cached, or another account's
            }
        }
        return signIn();
    }

    private static String signIn() throws IOException {
        Minecraft mc = Minecraft.getInstance();
        String access = mc.getUser().getAccessToken();
        if (access == null || access.length() < 20) throw new IOException("Not signed in with a Microsoft account");
        byte[] raw = new byte[20];
        new SecureRandom().nextBytes(raw);
        String serverId = HexFormat.of().formatHex(raw);
        JsonObject join = new JsonObject();
        join.addProperty("accessToken", access);
        join.addProperty("selectedProfile", undashed(mc.getUser().getProfileId().toString()));
        join.addProperty("serverId", serverId);
        Http.Response j = Http.json("POST", MOJANG_JOIN, null, join);
        if (!j.ok()) throw new IOException("Mojang rejected the session (HTTP " + j.code() + ")");
        JsonObject auth = new JsonObject();
        auth.addProperty("username", mc.getUser().getName());
        auth.addProperty("serverId", serverId);
        Http.Response r = Http.json("POST", DuskProvider.apiBase() + "/v1/auth/minecraft", null, auth);
        if (!r.ok()) throw new IOException(r.error("Dusk service"));
        JsonObject t = obj(r.json());
        Path dir = dataDir();
        if (dir != null) {
            try {
                Files.write(dir.resolve("dusk-session.json"), t.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        return t.get("token").getAsString();
    }

    private static String undashed(String uuid) {
        return uuid.replace("-", "");
    }

    private static JsonObject obj(JsonElement e) throws IOException {
        if (e == null || !e.isJsonObject()) throw new IOException("bad response from Dusk service");
        return e.getAsJsonObject();
    }

    private static Map<String, JsonElement> map(@Nullable JsonElement e) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        if (e != null && e.isJsonObject()) e.getAsJsonObject().entrySet().forEach(en -> out.put(en.getKey(), en.getValue()));
        return out;
    }
}
