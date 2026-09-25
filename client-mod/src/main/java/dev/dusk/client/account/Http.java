package dev.dusk.client.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Small blocking HTTP client for the menu's web calls (Mojang profile, the
 * Dusk service, Modrinth), through the game's proxy. Worker threads only.
 */
public final class Http {
    private static final int TIMEOUT_MS = 15_000;
    public static final String USER_AGENT = "Dusk-Client/" + FabricLoader.getInstance().getModContainer("duskclient")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev") + " (dusk launcher)";

    public record Response(int code, byte[] body) {
        public boolean ok() {
            return code / 100 == 2;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonElement json() throws IOException {
            try {
                return JsonParser.parseString(text());
            } catch (RuntimeException e) {
                throw new IOException("bad response (HTTP " + code + ")");
            }
        }

        /** The {@code error}/{@code errorMessage} of a JSON error body, else the status. */
        public String error(String service) {
            try {
                JsonElement e = JsonParser.parseString(text());
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    for (String k : new String[] {"errorMessage", "error", "description"}) {
                        if (o.has(k) && o.get(k).isJsonPrimitive()) return service + ": " + o.get(k).getAsString();
                    }
                }
            } catch (RuntimeException ignored) {
            }
            return service + " returned HTTP " + code;
        }
    }

    private Http() {}

    public static Response get(String url, @Nullable String bearer) throws IOException {
        return send("GET", url, bearer, null, null);
    }

    public static Response json(String method, String url, @Nullable String bearer, @Nullable JsonElement body) throws IOException {
        return send(method, url, bearer, body == null ? null : "application/json",
                body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A multipart/form-data body of text fields and one file part. */
    public static Response multipart(String url, @Nullable String bearer, String[][] fields,
                                     String fileField, String fileName, byte[] file) throws IOException {
        String boundary = "----dusk" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder head = new StringBuilder();
        for (String[] f : fields) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n")
                    .append(f[1]).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(fileField).append("\"; filename=\"").append(fileName).append("\"\r\n")
                .append("Content-Type: image/png\r\n\r\n");
        byte[] a = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] b = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[a.length + file.length + b.length];
        System.arraycopy(a, 0, body, 0, a.length);
        System.arraycopy(file, 0, body, a.length, file.length);
        System.arraycopy(b, 0, body, a.length + file.length, b.length);
        return send("POST", url, bearer, "multipart/form-data; boundary=" + boundary, body);
    }

    public static Response send(String method, String url, @Nullable String bearer,
                                @Nullable String contentType, byte @Nullable [] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection(Minecraft.getInstance().getProxy());
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
            if (in != null) in.close();
            return new Response(code, bytes);
        } finally {
            conn.disconnect();
        }
    }
}
