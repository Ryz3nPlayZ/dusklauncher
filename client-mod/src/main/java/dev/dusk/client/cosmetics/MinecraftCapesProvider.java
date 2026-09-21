package dev.dusk.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Read-only client of the MinecraftCapes profile API, wire-compatible with
 * the official mod (same endpoints, same JSON, same on-disk cache layout)
 * so a player who uploaded a cape at minecraftcapes.net sees it here and
 * everyone running either mod sees the same thing.
 *
 * <p>Network only, no rendering: the caller turns the bytes into textures.
 * Every call blocks and belongs on the cosmetics worker thread.
 */
public final class MinecraftCapesProvider {
    private static final Logger LOG = LoggerFactory.getLogger("duskclient/minecraftcapes");
    private static final String PROFILE_API = "https://api.minecraftcapes.net/profile/";
    private static final String UUID_API = "https://api.minecraftapi.net/v3/profile/";
    private static final String USER_AGENT = "minecraftcapes-mod/1.21.11 (duskclient)";
    private static final int TIMEOUT_MS = 10_000;

    /** A decoded profile. Byte arrays are null when the player has none. */
    public record Profile(boolean glint, boolean upsideDown, byte @Nullable [] cape, byte @Nullable [] ears) {
        public boolean isEmpty() {
            return cape == null && ears == null;
        }
    }

    private MinecraftCapesProvider() {}

    /**
     * Fetch a player's profile. Offline (v3) UUIDs are resolved to the
     * online one by name first, exactly like MinecraftCapes does; any
     * other UUID version is skipped. Returns null on failure / unknown.
     */
    @Nullable
    public static Profile fetch(UUID uuid, @Nullable String name) {
        UUID online = uuid;
        if (uuid.version() == 3) {
            if (name == null || name.isBlank()) return null;
            online = resolveOnlineUuid(name);
            if (online == null) return null;
        } else if (uuid.version() != 4) {
            return null;
        }

        byte[] json = download(PROFILE_API + online.toString().replace("-", ""));
        if (json == null) return null;
        JsonObject o;
        try {
            o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Unparseable MinecraftCapes profile for {}", online);
            return null;
        }
        boolean glint = bool(o, "capeGlint");
        boolean upsideDown = bool(o, "upsideDown");
        String capeUrl = str(o, "cape_url");
        String earUrl = str(o, "ear_url");
        byte[] cape = capeUrl == null ? null : downloadOrLoad(capeUrl, "capes");
        byte[] ears = earUrl == null ? null : downloadOrLoad(earUrl, "ears");
        return new Profile(glint, upsideDown, cape, ears);
    }

    @Nullable
    private static UUID resolveOnlineUuid(String name) {
        byte[] body = download(UUID_API + name + "?params=[full_uuid,name]");
        if (body == null) return null;
        try {
            JsonObject o = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = str(o, "full_uuid");
            return id == null ? null : UUID.fromString(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** Same cache layout as the MinecraftCapes mod: {@code <type>/<hash[0:2]>/<hash>}. */
    @Nullable
    private static byte[] downloadOrLoad(String url, String type) {
        String hash = url.substring(url.lastIndexOf('/') + 1);
        if (hash.isEmpty() || hash.contains("..")) return null;
        Path cache = cacheDir().resolve(type).resolve(hash.length() > 2 ? hash.substring(0, 2) : "xx").resolve(hash);
        if (Files.isRegularFile(cache)) {
            try {
                return Files.readAllBytes(cache);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(cache);
                } catch (IOException ignored) {
                }
            }
        }
        byte[] bytes = download(url);
        if (bytes == null) return null;
        try {
            Files.createDirectories(cache.getParent());
            Files.write(cache, bytes);
        } catch (IOException e) {
            LOG.debug("Could not cache {}: {}", url, e.toString());
        }
        return bytes;
    }

    public static Path cacheDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("duskclient");
    }

    /** GET a URL through the game's proxy; null on any non-2xx or I/O failure. */
    @Nullable
    static byte[] download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection(Minecraft.getInstance().getProxy());
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.connect();
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                LOG.debug("{} -> HTTP {}", url, code);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            LOG.debug("{} failed: {}", url, e.toString());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }
}
