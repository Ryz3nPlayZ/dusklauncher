package dev.dusk.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Read-only client of the MinecraftCapes profile API, wire-compatible with
 * the official mod (same endpoints, same JSON, same cache layout), so a cape
 * uploaded at minecraftcapes.net shows here too. Network only; every call
 * blocks and belongs on the cosmetics worker thread.
 */
public final class MinecraftCapesProvider {
    private static final Logger LOG = LogManager.getLogger("duskclient/minecraftcapes");
    private static final String PROFILE_API = "https://api.minecraftcapes.net/profile/";
    private static final String UUID_API = "https://api.minecraftapi.net/v3/profile/";
    private static final String USER_AGENT = "minecraftcapes-mod/1.8.9 (duskclient)";
    private static final int TIMEOUT_MS = 10_000;

    /** A decoded profile. Byte arrays are null when the player has none. */
    public static final class Profile {
        public final boolean glint, upsideDown;
        public final byte[] cape, ears;

        Profile(boolean glint, boolean upsideDown, byte[] cape, byte[] ears) {
            this.glint = glint;
            this.upsideDown = upsideDown;
            this.cape = cape;
            this.ears = ears;
        }

        public boolean isEmpty() {
            return cape == null && ears == null;
        }
    }

    private MinecraftCapesProvider() {}

    /**
     * A player's profile. Offline (v3) UUIDs are resolved to the online one
     * by name first, like MinecraftCapes does; other versions are skipped.
     */
    public static Profile fetch(UUID uuid, String name) {
        UUID online = uuid;
        if (uuid.version() == 3) {
            if (name == null || name.trim().isEmpty()) return null;
            online = resolveOnlineUuid(name);
            if (online == null) return null;
        } else if (uuid.version() != 4) {
            return null;
        }

        byte[] json = download(PROFILE_API + online.toString().replace("-", ""));
        if (json == null) return null;
        JsonObject o;
        try {
            o = new JsonParser().parse(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Unparseable MinecraftCapes profile for {}", online);
            return null;
        }
        String capeUrl = str(o, "cape_url");
        String earUrl = str(o, "ear_url");
        byte[] cape = capeUrl == null ? null : downloadOrLoad(capeUrl, "capes");
        byte[] ears = earUrl == null ? null : downloadOrLoad(earUrl, "ears");
        return new Profile(bool(o, "capeGlint"), bool(o, "upsideDown"), cape, ears);
    }

    private static UUID resolveOnlineUuid(String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return null;
        byte[] body = download(UUID_API + name + "?params=[full_uuid,name]");
        if (body == null) return null;
        try {
            JsonObject o = new JsonParser().parse(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = str(o, "full_uuid");
            return id == null ? null : UUID.fromString(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** Same cache layout as the MinecraftCapes mod: {@code <type>/<hash[0:2]>/<hash>}. */
    private static byte[] downloadOrLoad(String url, String type) {
        String hash = url.substring(url.lastIndexOf('/') + 1);
        if (hash.isEmpty() || !hash.matches("[A-Za-z0-9._-]+") || hash.contains("..")) return null;
        File cache = new File(new File(new File(cacheDir(), type), hash.length() > 2 ? hash.substring(0, 2) : "xx"), hash);
        if (cache.isFile()) {
            try {
                return Files.readAllBytes(cache.toPath());
            } catch (IOException e) {
                cache.delete();
            }
        }
        byte[] bytes = download(url);
        if (bytes == null) return null;
        try {
            cache.getParentFile().mkdirs();
            Files.write(cache.toPath(), bytes);
        } catch (IOException e) {
            LOG.debug("Could not cache {}: {}", url, e.toString());
        }
        return bytes;
    }

    public static File cacheDir() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "duskclient");
    }

    /** GET a URL through the game's proxy; null on any non-2xx or I/O failure. */
    static byte[] download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection(Minecraft.getMinecraft().getProxy());
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
                return Bytes.readAll(in);
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

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.trim().isEmpty() ? null : s;
    }
}
