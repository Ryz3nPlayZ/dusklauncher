package dev.dusk.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.DuskClient;
import dev.dusk.client.module.Module;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The open server API (docs/SERVER-API.md). A server sends {@code dusk:rules}, a UTF-8 JSON
 * body such as {@code {"disable":["fullbright","hitbox"]}}, to switch modules off for as
 * long as the player stays connected; each packet replaces the previous set. When the server
 * registers {@code dusk:hello} the client announces itself on it. The wire side lives in the
 * per-version {@link ServerChannel}.
 */
public final class ServerApi {
    public static final int PROTOCOL = 1;
    private static final Set<String> blocked = new HashSet<>();
    private static boolean greeted;

    private ServerApi() {}

    public static void init() {
        ServerChannel.register();
    }

    /** Applies a {@code dusk:rules} body; a malformed one is ignored rather than half-applied. */
    public static void applyRules(String json) {
        Set<String> next = new HashSet<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IllegalArgumentException("not an object");
            JsonElement disable = root.getAsJsonObject().get("disable");
            if (disable != null && !disable.isJsonNull()) {
                if (!disable.isJsonArray()) throw new IllegalArgumentException("disable is not a list");
                for (JsonElement e : disable.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) next.add(e.getAsString());
                }
            }
        } catch (Exception e) {
            DuskClient.LOGGER.warn("[DuskServerApi] ignoring malformed rules: {}", e.getMessage());
            return;
        }
        List<String> newlyOff = new ArrayList<>();
        for (Module m : DuskClient.modules().all()) {
            boolean block = next.contains(m.id());
            if (block && !m.blocked() && m.enabled()) newlyOff.add(m.name());
            m.setBlocked(block);
        }
        blocked.clear();
        blocked.addAll(next);
        DuskClient.LOGGER.info("[DuskServerApi] server disabled {}", next);
        if (!newlyOff.isEmpty()) ServerChannel.notice("This server turned off " + String.join(", ", newlyOff) + ".");
    }

    /** Leaving a server lifts its blocks and forgets the handshake. */
    public static void reset() {
        greeted = false;
        if (blocked.isEmpty()) return;
        blocked.clear();
        for (Module m : DuskClient.modules().all()) m.setBlocked(false);
    }

    /** Sends {@code dusk:hello} once per connection, as soon as the server can receive it. */
    public static void greetIfListening() {
        if (greeted || !ServerChannel.canGreet()) return;
        greeted = true;
        ServerChannel.greet(hello());
    }

    public static Set<String> blocked() {
        return Set.copyOf(blocked);
    }

    static String hello() {
        JsonObject o = new JsonObject();
        o.addProperty("protocol", PROTOCOL);
        o.addProperty("version", modVersion("duskclient"));
        o.addProperty("mc", modVersion("minecraft"));
        JsonArray modules = new JsonArray();
        for (Module m : DuskClient.modules().all()) modules.add(m.id());
        o.add("modules", modules);
        return o.toString();
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
    }
}
