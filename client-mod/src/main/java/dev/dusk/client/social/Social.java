package dev.dusk.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dusk.client.account.DuskAccount;
import dev.dusk.client.account.Http;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Friends, chat, gifts and outfits on the Dusk service, the same calls the
 * launcher's friends.rs makes, so a conversation is one conversation in
 * both. Blocking; worker threads only. Times are unix seconds.
 */
public final class Social {
    public static final int MESSAGE_MAX = 1000;
    /** The service's upload cap for chat images. */
    public static final long IMAGE_MAX = 8L * 1024 * 1024;

    public record Friend(String uuid, String username, boolean online, @Nullable String playing,
                         @Nullable String server, long lastSeen, int unread) {}

    public record Request(long id, String uuid, String username, long createdAt) {}

    public record Requests(List<Request> incoming, List<Request> outgoing) {}

    public record Message(long id, String from, String to, String body, long sentAt, String kind, JsonObject meta) {
        public String meta(String key) {
            JsonElement e = meta.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
        }
    }

    public record Profile(String uuid, String username, boolean online, long lastSeen, @Nullable String playing,
                          @Nullable String server, int cape, List<Integer> accessories) {}

    public record Outfit(long id, String name, Map<String, JsonElement> loadout) {}

    public record StoreItem(int id, String name, String kind, long price) {}

    public record Store(List<StoreItem> items, long coins) {}

    private Social() {}

    // ---- friends ------------------------------------------------------------

    public static List<Friend> friends() throws IOException {
        List<Friend> out = new ArrayList<>();
        for (JsonElement e : arr(DuskAccount.api("GET", "/v1/friends", null))) out.add(friend(e.getAsJsonObject()));
        return out;
    }

    public static Requests requests() throws IOException {
        return requests(DuskAccount.api("GET", "/v1/friends/requests", null));
    }

    public static void sendRequest(String username) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("username", username);
        DuskAccount.api("POST", "/v1/friends/requests", b);
    }

    public static Requests accept(long id) throws IOException {
        return requests(DuskAccount.api("POST", "/v1/friends/requests/" + id + "/accept", null));
    }

    /** Declines one they sent, or cancels one this account sent. */
    public static Requests decline(long id) throws IOException {
        return requests(DuskAccount.api("POST", "/v1/friends/requests/" + id + "/decline", null));
    }

    public static void remove(String uuid) throws IOException {
        DuskAccount.api("DELETE", "/v1/friends/" + undashed(uuid), null);
    }

    public static void block(String uuid) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("uuid", uuid);
        DuskAccount.api("POST", "/v1/blocks", b);
    }

    public static Profile profile(String uuid) throws IOException {
        JsonObject o = DuskAccount.api("GET", "/v1/profile/" + undashed(uuid), null).getAsJsonObject();
        List<Integer> acc = new ArrayList<>();
        if (o.has("accessories") && o.get("accessories").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("accessories")) acc.add(e.getAsInt());
        }
        return new Profile(str(o, "uuid"), str(o, "username"), bool(o, "online"), num(o, "lastSeen"),
                opt(o, "playing"), opt(o, "server"), o.has("cape") && !o.get("cape").isJsonNull() ? o.get("cape").getAsInt() : -1, acc);
    }

    // ---- chat -----------------------------------------------------------------

    /** The conversation since {@code afterId} (0: the newest page). Marks what it returns read. */
    public static List<Message> messages(String uuid, long afterId) throws IOException {
        List<Message> out = new ArrayList<>();
        for (JsonElement e : arr(DuskAccount.api("GET", "/v1/messages/" + undashed(uuid) + "?afterId=" + afterId, null))) {
            out.add(message(e.getAsJsonObject()));
        }
        return out;
    }

    public static Message send(String uuid, String body) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("body", body);
        return post(uuid, b);
    }

    public static Message invite(String uuid, String server, @Nullable String version) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("server", server);
        if (version != null) meta.addProperty("version", version);
        JsonObject b = new JsonObject();
        b.addProperty("kind", "invite");
        b.add("meta", meta);
        return post(uuid, b);
    }

    /** Uploads the screenshot, then sends it as an image message. */
    public static Message screenshot(String uuid, Path file) throws IOException {
        long size = Files.size(file);
        if (size > IMAGE_MAX) throw new IOException("That screenshot is over 8 MB.");
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String mime = name.endsWith(".png") ? "image/png" : "image/jpeg";
        Http.Response r = DuskAccount.raw("POST", "/v1/images", mime, Files.readAllBytes(file));
        if (!r.ok()) throw new IOException(r.error("Dusk service"));
        String id = str(r.json().getAsJsonObject(), "id");
        JsonObject meta = new JsonObject();
        meta.addProperty("image", id);
        JsonObject b = new JsonObject();
        b.addProperty("kind", "image");
        b.add("meta", meta);
        return post(uuid, b);
    }

    /** A chat image's bytes; null once it has expired. */
    public static byte @Nullable [] image(String id) throws IOException {
        Http.Response r = DuskAccount.raw("GET", "/v1/images/" + id, null, null);
        if (r.code() == 404) return null;
        if (!r.ok()) throw new IOException(r.error("Dusk service"));
        return r.body();
    }

    private static Message post(String uuid, JsonObject body) throws IOException {
        return message(DuskAccount.api("POST", "/v1/messages/" + undashed(uuid), body).getAsJsonObject());
    }

    /** This instance's screenshots, newest first. */
    public static List<Path> screenshots(int max) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                    })
                    .sorted(Comparator.comparingLong(Social::modified).reversed())
                    .limit(max)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- store, gifts, outfits --------------------------------------------------

    public static Store store() throws IOException {
        List<StoreItem> items = new ArrayList<>();
        JsonObject cat = DuskAccount.api("GET", "/v1/catalog", null).getAsJsonObject();
        for (JsonElement e : cat.getAsJsonArray("items")) {
            JsonObject o = e.getAsJsonObject();
            items.add(new StoreItem(o.get("id").getAsInt(), str(o, "name"), str(o, "kind"), num(o, "price")));
        }
        items.sort(Comparator.comparing((StoreItem i) -> !i.kind.equals("cape")).thenComparingLong(StoreItem::price));
        long coins = num(DuskAccount.api("GET", "/v1/me", null).getAsJsonObject(), "coins");
        return new Store(items, coins);
    }

    /** Buys {@code id} for this account; the coins left. */
    public static long buy(int id) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        return num(DuskAccount.api("POST", "/v1/me/buy", b).getAsJsonObject(), "coins");
    }

    /** Gives {@code to} the cosmetic {@code id}; the coins left. */
    public static long gift(String to, int id) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("to", to);
        b.addProperty("id", id);
        return num(DuskAccount.api("POST", "/v1/me/gift", b).getAsJsonObject(), "coins");
    }

    public static List<Outfit> outfits() throws IOException {
        return outfits(DuskAccount.api("GET", "/v1/me/outfits", null));
    }

    public static List<Outfit> saveOutfit(String name, Map<String, JsonElement> loadout) throws IOException {
        JsonObject l = new JsonObject();
        loadout.forEach(l::add);
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        b.add("loadout", l);
        return outfits(DuskAccount.api("POST", "/v1/me/outfits", b));
    }

    public static List<Outfit> deleteOutfit(long id) throws IOException {
        return outfits(DuskAccount.api("DELETE", "/v1/me/outfits/" + id, null));
    }

    // ---- parsing --------------------------------------------------------------

    public static String undashed(String uuid) {
        return uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    public static boolean same(String a, String b) {
        return undashed(a).equals(undashed(b));
    }

    private static List<Outfit> outfits(JsonElement e) {
        List<Outfit> out = new ArrayList<>();
        for (JsonElement x : arr(e)) {
            JsonObject o = x.getAsJsonObject();
            Map<String, JsonElement> l = new LinkedHashMap<>();
            if (o.has("loadout") && o.get("loadout").isJsonObject()) o.getAsJsonObject("loadout").entrySet().forEach(en -> l.put(en.getKey(), en.getValue()));
            out.add(new Outfit(num(o, "id"), str(o, "name"), l));
        }
        return out;
    }

    private static Friend friend(JsonObject o) {
        return new Friend(str(o, "uuid"), str(o, "username"), bool(o, "online"), opt(o, "playing"), opt(o, "server"),
                num(o, "lastSeen"), (int) num(o, "unread"));
    }

    private static Requests requests(JsonElement e) {
        JsonObject o = e.getAsJsonObject();
        return new Requests(requestList(o.get("incoming")), requestList(o.get("outgoing")));
    }

    private static List<Request> requestList(@Nullable JsonElement e) {
        List<Request> out = new ArrayList<>();
        for (JsonElement x : arr(e)) {
            JsonObject o = x.getAsJsonObject();
            out.add(new Request(num(o, "id"), str(o, "uuid"), str(o, "username"), num(o, "createdAt")));
        }
        return out;
    }

    private static Message message(JsonObject o) {
        JsonObject meta = o.has("meta") && o.get("meta").isJsonObject() ? o.getAsJsonObject("meta") : new JsonObject();
        String kind = opt(o, "kind");
        return new Message(num(o, "id"), str(o, "fromUuid"), str(o, "toUuid"), str(o, "body"), num(o, "sentAt"),
                kind == null ? "text" : kind, meta);
    }

    private static JsonArray arr(@Nullable JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        String s = opt(o, k);
        return s == null ? "" : s;
    }

    @Nullable
    private static String opt(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static long num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }
}
