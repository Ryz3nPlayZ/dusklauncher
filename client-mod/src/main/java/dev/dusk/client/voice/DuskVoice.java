package dev.dusk.client.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dusk.client.DuskClient;
import dev.dusk.client.account.DuskAccount;
import dev.dusk.client.cosmetics.DuskProvider;
import dev.dusk.client.gui.Icons;
import dev.dusk.client.social.SocialNotifier;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Voice between Dusk players, relayed by the Dusk service over a WebSocket,
 * so it works on any server and between servers. Nearby voice reaches Dusk
 * players on the same server within {@link #RANGE} blocks (friends only, or
 * everyone, as each side allows); calls are rooms friends invite each other
 * to. The service only sends voice to people who can hear it, and the
 * microphone only opens while someone could.
 */
public final class DuskVoice {
    static final double RANGE = 48;
    private static final int NEARBY = 1, CALL = 2;

    public record Ring(String room, UUID from, String name, long at) {}

    @Nullable private static volatile WebSocket socket;
    private static volatile boolean opening;
    private static volatile boolean refresh;
    private static volatile long retryAt;
    private static volatile int failures;
    @Nullable private static volatile String problem;
    private static volatile int near;

    @Nullable private static volatile String room;
    private static volatile Map<UUID, String> members = Map.of();
    @Nullable private static volatile Ring ring;
    private static String lastWhere = "";
    private static long lastWhereAt;

    private DuskVoice() {}

    /** Whether voice is flowing: in a call, or with a Dusk player near enough to hear. */
    static boolean active() {
        if (socket == null) return false;
        if (room != null) return true;
        VoiceChat m = Voice.settings();
        return near > 0 && m != null && m.nearbyOn() && !SvcClient.connected();
    }

    @Nullable
    static String status() {
        VoiceChat m = Voice.settings();
        if (m == null || !m.duskWanted()) return null;
        if (socket == null) return problem != null ? problem : "Connecting to Dusk voice...";
        if (room != null) return "In a call with " + Math.max(0, members.size() - 1);
        if (m.nearbyOn() && !SvcClient.connected()) return near > 0 ? near + " Dusk player" + (near == 1 ? "" : "s") + " in range" : "Dusk voice ready";
        return null;
    }

    // ---- the connection ---------------------------------------------------------

    static void tick(Minecraft mc, boolean wanted) {
        if (!wanted) {
            close();
            return;
        }
        WebSocket ws = socket;
        if (ws == null) {
            if (!opening && System.currentTimeMillis() >= retryAt && DuskAccount.canSignIn()) open();
            return;
        }
        long now = System.currentTimeMillis();
        String where = where(mc);
        if (!where.equals(lastWhere) || now - lastWhereAt > 5000) {
            if (now - lastWhereAt >= 500) {
                lastWhere = where;
                lastWhereAt = now;
                text(where);
            }
        }
        Ring r = ring;
        if (r != null && now - r.at() > 45_000) ring = null;
    }

    private static void open() {
        opening = true;
        Thread t = new Thread(() -> {
            try {
                String token = DuskAccount.bearer(refresh);
                refresh = false;
                String base = DuskProvider.apiBase().replaceFirst("^http", "ws");
                WebSocket ws = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                        .newWebSocketBuilder()
                        .header("Authorization", "Bearer " + token)
                        .buildAsync(URI.create(base + "/v1/voice"), new Listener())
                        .get(15, TimeUnit.SECONDS);
                socket = ws;
                failures = 0;
                problem = null;
                lastWhere = "";
                sendMode();
                DuskClient.LOGGER.info("[voice] connected to Dusk voice");
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof WebSocketHandshakeException h && h.getResponse().statusCode() == 401) refresh = true;
                failed("Can't reach Dusk voice");
                DuskClient.LOGGER.debug("[voice] Dusk voice connect failed", e);
            } finally {
                opening = false;
            }
        }, "Dusk voice connect");
        t.setDaemon(true);
        t.start();
    }

    private static void failed(String why) {
        socket = null;
        room = null;
        members = Map.of();
        near = 0;
        problem = why;
        int f = Math.min(++failures, 5);
        retryAt = System.currentTimeMillis() + Math.min(60_000, 3000L << f);
    }

    private static void close() {
        WebSocket ws = socket;
        socket = null;
        room = null;
        members = Map.of();
        ring = null;
        near = 0;
        problem = null;
        failures = 0;
        retryAt = 0;
        if (ws != null) SENDER.execute(() -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null));
    }

    /** Sends happen here, one at a time, so a slow link never holds up the game or the microphone. */
    private static final java.util.concurrent.ThreadPoolExecutor SENDER = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "Dusk voice send");
                t.setDaemon(true);
                return t;
            });

    private static void text(String json) {
        send(ws -> ws.sendText(json, true), 3);
    }

    private static void binary(byte[] data) {
        // a backlog means the link can't keep up; old voice is worth less than none
        if (SENDER.getQueue().size() > 25) return;
        send(ws -> ws.sendBinary(ByteBuffer.wrap(data), true), 1);
    }

    private static void send(java.util.function.Function<WebSocket, CompletionStage<WebSocket>> op, int timeoutSeconds) {
        WebSocket ws = socket;
        if (ws == null) return;
        SENDER.execute(() -> {
            if (socket != ws) return;
            try {
                op.apply(ws).toCompletableFuture().get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                DuskClient.LOGGER.debug("[voice] send failed", e);
            }
        });
    }

    /** Who hears you nearby, and whose calls ring, as the settings say. */
    static void sendMode() {
        VoiceChat m = Voice.settings();
        if (m == null || socket == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "mode");
        o.addProperty("nearby", m.nearbyMode());
        o.addProperty("calls", m.callsMode());
        text(o.toString());
    }

    /** Where you are, so the service knows who is near: a hash of the server, the dimension and position. */
    private static String where(Minecraft mc) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "where");
        var conn = mc.getConnection();
        if (conn != null && mc.player != null && mc.level != null && !mc.hasSingleplayerServer()
                && conn.getConnection().getRemoteAddress() instanceof InetSocketAddress a) {
            String host = a.getAddress() != null ? a.getAddress().getHostAddress() : a.getHostString();
            o.addProperty("server", hash(host + ":" + a.getPort()));
            o.addProperty("dim", mc.level.dimension().toString());
            o.addProperty("x", Math.round(mc.player.getX()));
            o.addProperty("y", Math.round(mc.player.getEyeY()));
            o.addProperty("z", Math.round(mc.player.getZ()));
        }
        return o.toString();
    }

    /** Sends where you are on the next tick, after joining or changing server. */
    static void refresh() {
        lastWhereAt = 0;
        lastWhere = "";
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (Exception e) {
            return s;
        }
    }

    static void sendMic(byte[] opus, long seq, boolean whisper) {
        if (socket == null) return;
        VoiceChat m = Voice.settings();
        int flags = 0;
        if (m != null && m.nearbyOn() && near > 0 && !SvcClient.connected()) flags |= NEARBY;
        if (room != null) flags |= CALL;
        if (whisper) flags |= 4;
        if ((flags & (NEARBY | CALL)) == 0) return;
        binary(new Wire.Out().b(flags).l(seq).raw(opus).done());
    }

    // ---- calls ------------------------------------------------------------------

    public static boolean connected() { return socket != null; }

    public static boolean inCall() { return room != null; }

    /** The people in your call, you included, by uuid. */
    public static Map<UUID, String> members() { return members; }

    public static boolean inCall(UUID uuid) { return room != null && members.containsKey(uuid); }

    @Nullable public static Ring ringing() { return ring; }

    /** Rings a friend; starts a call if you aren't in one, or adds them to yours. */
    public static boolean call(UUID friend) {
        if (socket == null) return false;
        JsonObject o = new JsonObject();
        o.addProperty("t", "call");
        o.addProperty("to", undashed(friend));
        text(o.toString());
        return true;
    }

    public static void accept() {
        Ring r = ring;
        if (r == null || socket == null) return;
        ring = null;
        JsonObject o = new JsonObject();
        o.addProperty("t", "accept");
        o.addProperty("room", r.room());
        text(o.toString());
    }

    public static void decline() {
        Ring r = ring;
        if (r == null) return;
        ring = null;
        JsonObject o = new JsonObject();
        o.addProperty("t", "decline");
        o.addProperty("room", r.room());
        text(o.toString());
    }

    public static void hangUp() {
        if (room == null) return;
        room = null;
        members = Map.of();
        text("{\"t\":\"leave\"}");
    }

    @Nullable
    static String name(UUID uuid) {
        return members.get(uuid);
    }

    static String undashed(UUID u) {
        return u.toString().replace("-", "");
    }

    public static UUID parse(String s) {
        if (s.length() == 32) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        return UUID.fromString(s);
    }

    // ---- incoming -----------------------------------------------------------------

    private static void onText(String text) {
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        switch (o.get("t").getAsString()) {
            case "near" -> near = o.get("n").getAsInt();
            case "ring" -> {
                Ring r = new Ring(o.get("room").getAsString(), parse(o.get("from").getAsString()),
                        o.get("name").getAsString(), System.currentTimeMillis());
                ring = r;
                SocialNotifier.toast(Icons.MIC, r.name() + " is calling", "Answer from Friends, or /duskvoice accept");
            }
            case "room" -> {
                JsonElement id = o.get("room");
                if (id == null || id.isJsonNull()) {
                    room = null;
                    members = Map.of();
                    return;
                }
                Map<UUID, String> m = new LinkedHashMap<>();
                JsonArray list = o.getAsJsonArray("members");
                for (JsonElement e : list) {
                    JsonObject p = e.getAsJsonObject();
                    m.put(parse(p.get("uuid").getAsString()), p.get("name").getAsString());
                }
                room = id.getAsString();
                members = Map.copyOf(m);
            }
            case "declined" -> SocialNotifier.toast(Icons.MIC, o.get("name").getAsString(), "didn't pick up");
            case "error" -> SocialNotifier.toast(Icons.MIC, "Voice", o.get("msg").getAsString());
            default -> {}
        }
    }

    private static void onBinary(byte[] data) {
        if (data.length < 25) return;
        Wire.In in = new Wire.In(data);
        int kind = in.b();
        UUID sender = in.uuid();
        long seq = in.l();
        byte[] opus = in.rest();
        if (kind == NEARBY) Voice.heard(Voice.Source.NEARBY, sender, sender, seq, opus, null, false, 0, false);
        else if (kind == CALL) Voice.heard(Voice.Source.CALL, sender, sender, seq, opus, null, true, 0, false);
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String t = text.toString();
                text.setLength(0);
                try {
                    DuskVoice.onText(t);
                } catch (RuntimeException e) {
                    DuskClient.LOGGER.debug("[voice] bad message {}", t, e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            bytes.write(chunk, 0, chunk.length);
            if (last) {
                byte[] all = bytes.toByteArray();
                bytes = new java.io.ByteArrayOutputStream();
                try {
                    DuskVoice.onBinary(all);
                } catch (RuntimeException e) {
                    DuskClient.LOGGER.debug("[voice] bad voice frame", e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (socket == ws) failed(status == 4001 ? "Dusk voice signed you out" : "Lost Dusk voice, reconnecting...");
            if (status == 4001) refresh = true;
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (socket == ws) failed("Lost Dusk voice, reconnecting...");
            DuskClient.LOGGER.debug("[voice] Dusk voice error", error);
        }
    }
}
