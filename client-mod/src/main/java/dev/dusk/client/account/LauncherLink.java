package dev.dusk.client.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The launcher's private line for this game ({@code -Ddusk.link}, a file with
 * a loopback port and a secret): the saved accounts, and a fresh login for one
 * of them, so the game can switch accounts without a restart. Blocking;
 * worker threads only.
 */
public final class LauncherLink {
    public record Account(String uuid, String username, boolean active) {}

    public record Login(String uuid, String username, String accessToken, Optional<String> xuid) {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private LauncherLink() {}

    /** Whether the launcher started this game with a link. */
    public static boolean available() {
        String p = System.getProperty("dusk.link");
        return p != null && !p.isBlank();
    }

    public static List<Account> accounts() throws IOException {
        JsonObject o = call("GET", "/accounts", null);
        List<Account> out = new ArrayList<>();
        if (o.has("accounts") && o.get("accounts").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("accounts")) {
                if (!e.isJsonObject()) continue;
                JsonObject a = e.getAsJsonObject();
                out.add(new Account(str(a, "uuid"), str(a, "username"), a.has("active") && a.get("active").getAsBoolean()));
            }
        }
        return out;
    }

    /** Makes {@code uuid} the launcher's account and returns its login (refreshed if it was stale). */
    public static Login switchTo(String uuid) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        JsonObject o = call("POST", "/switch", body);
        String xuid = str(o, "xuid");
        return new Login(str(o, "uuid"), str(o, "username"), str(o, "accessToken"),
                xuid.isEmpty() ? Optional.empty() : Optional.of(xuid));
    }

    private static JsonObject call(String method, String path, @Nullable JsonObject body) throws IOException {
        String file = System.getProperty("dusk.link");
        if (file == null || file.isBlank()) throw new IOException("Open the game from the Dusk launcher to switch accounts.");
        JsonObject link;
        try {
            link = JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Couldn't reach the launcher — is it still open?");
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + link.get("port").getAsInt() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + link.get("secret").getAsString());
        if (body != null) {
            req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r;
        try {
            r = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IOException e) {
            throw new IOException("Couldn't reach the launcher — is it still open?");
        }
        JsonObject o;
        try {
            o = JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("The launcher sent a bad answer (HTTP " + r.statusCode() + ")");
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException(o.has("error") ? o.get("error").getAsString() : "The launcher returned HTTP " + r.statusCode());
        }
        return o;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
