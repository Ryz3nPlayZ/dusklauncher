package dev.dusk.client.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.account.Http;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.RemoteImages;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Friends' faces: their skin from Mojang's session server, drawn as the face plus hat layer. */
public final class Heads {
    private static final String PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";
    /** Skin PNGs by undashed uuid, so reopening the friends page doesn't ask Mojang again. */
    private static final Map<String, byte[]> SKINS = new ConcurrentHashMap<>();

    private Heads() {}

    /** The skin PNG for {@code uuid}; null when it has none (Steve/Alex). Blocking. */
    public static byte @Nullable [] skin(String uuid) throws IOException {
        String id = Social.undashed(uuid);
        byte[] hit = SKINS.get(id);
        if (hit != null) return hit;
        Http.Response r = Http.get(PROFILE + id, null);
        if (!r.ok()) throw new IOException("Mojang returned HTTP " + r.code());
        JsonObject o = r.json().getAsJsonObject();
        for (JsonElement p : o.getAsJsonArray("properties")) {
            JsonObject prop = p.getAsJsonObject();
            if (!"textures".equals(prop.get("name").getAsString())) continue;
            String json = new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8);
            JsonObject skin = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN");
            if (skin == null) return null;
            byte[] png = Http.get(skin.get("url").getAsString(), null).body();
            SKINS.put(id, png);
            return png;
        }
        return null;
    }

    /** {@code uuid}'s face {@code size} pixels square from {@code images}, or a grey square while it loads. */
    public static void draw(Canvas c, RemoteImages images, String uuid, int x, int y, int size) {
        RemoteImages.Image img = images.get("head:" + Social.undashed(uuid), () -> skin(uuid));
        if (img == null) {
            c.fill(x, y, x + size, y + size, 0xFF3A3A3A);
            return;
        }
        float k = img.w() / 64f;
        c.push();
        c.translate(x, y);
        c.scale(size / (8 * k), size / (8 * k));
        int s = Math.round(8 * k);
        c.blit(img.id(), 0, 0, 8 * k, 8 * k, s, s, img.w(), img.h());
        c.blit(img.id(), 0, 0, 40 * k, 8 * k, s, s, img.w(), img.h());
        c.pop();
    }
}
