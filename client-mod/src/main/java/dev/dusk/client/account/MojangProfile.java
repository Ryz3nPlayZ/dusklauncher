package dev.dusk.client.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The signed-in account's Minecraft profile (api.minecraftservices.com):
 * its skins and capes, uploading a skin and switching the shown cape, with
 * the game's own access token. Blocking; worker threads only.
 *
 * <p>Servers read skins from the session server when a player joins, so a
 * change shows to others after leaving and rejoining the server.
 */
public final class MojangProfile {
    private static final String BASE = "https://api.minecraftservices.com/minecraft/profile";

    public record Skin(String id, boolean active, String url, boolean slim) {}

    public record Cape(String id, boolean active, String url, String alias) {}

    public record Profile(String id, String name, List<Skin> skins, List<Cape> capes) {
        @Nullable
        public Skin activeSkin() {
            for (Skin s : skins) if (s.active) return s;
            return skins.isEmpty() ? null : skins.get(0);
        }

        @Nullable
        public Cape activeCape() {
            for (Cape c : capes) if (c.active) return c;
            return null;
        }
    }

    private MojangProfile() {}

    public static Profile fetch() throws IOException {
        return parse(check(Http.get(BASE, token())));
    }

    public static Profile uploadSkin(byte[] png, boolean slim) throws IOException {
        Http.Response r = Http.multipart(BASE + "/skins", token(),
                new String[][] {{"variant", slim ? "slim" : "classic"}}, "file", "skin.png", png);
        check(r);
        // the upload answers with the profile on most accounts; fetch it when it does not
        try {
            JsonElement e = r.json();
            if (e.isJsonObject() && e.getAsJsonObject().has("skins")) return parse(r);
        } catch (IOException ignored) {
        }
        return fetch();
    }

    public static Profile showCape(String capeId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("capeId", capeId);
        return parse(check(Http.json("PUT", BASE + "/capes/active", token(), body)));
    }

    public static Profile hideCape() throws IOException {
        return parse(check(Http.send("DELETE", BASE + "/capes/active", token(), null, null)));
    }

    private static String token() throws IOException {
        String t = Minecraft.getInstance().getUser().getAccessToken();
        if (t == null || t.isBlank() || t.length() < 20) throw new IOException("Not signed in with a Microsoft account");
        return t;
    }

    private static Http.Response check(Http.Response r) throws IOException {
        if (r.ok()) return r;
        if (r.code() == 401) throw new IOException("Mojang rejected the session — restart the game from the launcher");
        if (r.code() == 429) throw new IOException("Mojang is rate limiting — try again in a minute");
        throw new IOException(r.error("Mojang"));
    }

    private static Profile parse(Http.Response r) throws IOException {
        JsonElement root = r.json();
        if (!root.isJsonObject()) throw new IOException("bad profile from Mojang");
        JsonObject o = root.getAsJsonObject();
        List<Skin> skins = new ArrayList<>();
        for (JsonElement e : array(o, "skins")) {
            JsonObject s = e.getAsJsonObject();
            skins.add(new Skin(str(s, "id"), "ACTIVE".equals(str(s, "state")), str(s, "url"),
                    "slim".equals(str(s, "variant").toLowerCase(Locale.ROOT))));
        }
        List<Cape> capes = new ArrayList<>();
        for (JsonElement e : array(o, "capes")) {
            JsonObject c = e.getAsJsonObject();
            capes.add(new Cape(str(c, "id"), "ACTIVE".equals(str(c, "state")), str(c, "url"), str(c, "alias")));
        }
        return new Profile(str(o, "id"), str(o, "name"), skins, capes);
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
