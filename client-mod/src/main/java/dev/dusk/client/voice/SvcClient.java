package dev.dusk.client.voice;

import dev.dusk.client.DuskClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to servers running Simple Voice Chat, so Dusk players hear and are
 * heard by everyone there with the mod. This is a fresh implementation of
 * its network protocol for compatibility, written from how the protocol
 * works, not its code (see NOTICE): the server hands out a secret over a
 * plugin channel, then voice goes over UDP, AES-GCM sealed with it.
 * Stays out of the way when Simple Voice Chat itself is installed.
 */
final class SvcClient {
    /** The protocol revision servers check before handing out a secret. */
    private static final int COMPATIBILITY = 20;
    private static final byte MAGIC = (byte) 0xFF;
    private static final int MIC = 1, PLAYER_SOUND = 2, GROUP_SOUND = 3, LOCATION_SOUND = 4,
            AUTHENTICATE = 5, AUTHENTICATE_ACK = 6, PING = 7, KEEP_ALIVE = 8, CHECK = 9, CHECK_ACK = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    record Peer(String name, boolean disabled, boolean disconnected) {}

    private static boolean registered;
    private static volatile boolean supported;
    @Nullable private static volatile Session session;
    /** Who on the server has voice chat, from its state packets. */
    static final Map<UUID, Peer> peers = new ConcurrentHashMap<>();

    private SvcClient() {}

    static boolean available() {
        return !FabricLoader.getInstance().isModLoaded("voicechat");
    }

    static void init() {
        if (!available() || registered) return;
        registered = true;
        VoiceChannel.register(new String[] {"secret", "state", "states"}, new String[] {"request_secret", "update_state"},
                SvcClient::received, () -> {
                    supported = true;
                    if (Voice.serverWanted()) requestSecret();
                });
    }

    /** Whether this server runs Simple Voice Chat. */
    static boolean supported() { return supported; }

    static boolean connected() {
        Session s = session;
        return s != null && s.connected;
    }

    @Nullable
    static String status() {
        Session s = session;
        if (!supported) return null;
        if (s == null) return Voice.serverWanted() ? "Joining voice chat..." : "Server voice chat is off";
        if (s.connected) return "Connected to server voice chat";
        return s.problem != null ? s.problem : "Connecting to the voice server...";
    }

    /** Server voice chat was switched on or off in the settings. */
    static void wantedChanged() {
        if (!supported) return;
        if (Voice.serverWanted()) {
            if (session == null) requestSecret();
        } else close();
    }

    static void requestSecret() {
        if (!VoiceChannel.canSend("request_secret")) return;
        VoiceChannel.send("request_secret", new Wire.Out().i(COMPATIBILITY).done());
    }

    /** Tells the server whether to send us voices at all (off while deafened). */
    static void sendState() {
        if (!connected() || !VoiceChannel.canSend("update_state")) return;
        VoiceChannel.send("update_state", new Wire.Out().bool(Voice.deafened()).done());
    }

    static void leave() {
        close();
        supported = false;
        peers.clear();
    }

    private static void close() {
        Session s = session;
        session = null;
        if (s != null) s.close();
    }

    static void sendMic(byte[] opus, long seq, boolean whisper) {
        Session s = session;
        if (s != null && s.connected) s.send(MIC, new Wire.Out().bytes(opus).l(seq).bool(whisper).done());
    }

    // ---- plugin channels (network thread) -------------------------------------

    private static void received(String path, byte[] data) {
        try {
            Wire.In in = new Wire.In(data);
            switch (path) {
                case "secret" -> secret(in);
                case "state" -> state(in);
                case "states" -> {
                    int n = in.i();
                    peers.clear();
                    for (int i = 0; i < n && in.remaining() > 0; i++) state(in);
                }
                default -> {}
            }
        } catch (RuntimeException e) {
            DuskClient.LOGGER.debug("[voice] bad {} packet", path, e);
        }
    }

    private static void state(Wire.In in) {
        boolean disabled = in.bool(), disconnected = in.bool();
        UUID uuid = in.uuid();
        String name = in.utf(32767);
        if (in.bool()) in.uuid(); // group
        peers.put(uuid, new Peer(name, disabled, disconnected));
    }

    private static void secret(Wire.In in) {
        byte[] key = in.raw(16);
        int port = in.i();
        UUID self = in.uuid();
        in.b(); // codec; any Opus decodes the same
        in.i(); // mtu
        double distance = in.d();
        int keepAlive = in.i();
        in.bool(); // groups
        String voiceHost = in.utf(32767);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!Voice.serverWanted()) return;
            var listener = mc.getConnection();
            if (listener == null) return;
            String host;
            try {
                host = host(listener.getConnection().getRemoteAddress());
            } catch (Exception e) {
                DuskClient.LOGGER.warn("[voice] no server address", e);
                return;
            }
            int p = port;
            if (!voiceHost.isEmpty()) {
                try {
                    int parsed = Integer.parseInt(voiceHost);
                    if (parsed > 0 && parsed <= 65535) p = parsed;
                } catch (NumberFormatException notPort) {
                    try {
                        URI uri = new URI("voicechat://" + voiceHost);
                        if (uri.getHost() != null) host = uri.getHost();
                        if (uri.getPort() > 0) p = uri.getPort();
                    } catch (Exception ignored) {
                        // keep the game server's address
                    }
                }
            }
            close();
            Session s = new Session(host, p, self, key, distance, keepAlive);
            session = s;
            s.start();
        });
    }

    private static String host(SocketAddress address) {
        if (address instanceof InetSocketAddress a) {
            InetAddress ip = a.getAddress();
            return ip != null ? ip.getHostAddress() : a.getHostString();
        }
        return "127.0.0.1"; // the integrated server
    }

    // ---- the UDP side -----------------------------------------------------------

    private static final class Session {
        private final String host;
        private final int port;
        private final UUID self;
        private final byte[] secret;
        private final SecretKeySpec key;
        final double distance;
        private final int keepAlive;
        private DatagramSocket socket;
        private InetSocketAddress server;
        private volatile boolean running = true;
        volatile boolean authenticated, connected;
        volatile long lastKeepAlive = -1;
        @Nullable volatile String problem;

        Session(String host, int port, UUID self, byte[] secret, double distance, int keepAlive) {
            this.host = host;
            this.port = port;
            this.self = self;
            this.secret = secret;
            this.key = new SecretKeySpec(secret, "AES");
            this.distance = distance;
            this.keepAlive = keepAlive;
        }

        void start() {
            Thread t = new Thread(this::run, "Dusk voice server");
            t.setDaemon(true);
            t.start();
        }

        private void run() {
            try {
                server = new InetSocketAddress(InetAddress.getByName(host), port);
                socket = new DatagramSocket();
                socket.setSoTimeout(1000);
            } catch (Exception e) {
                problem = "Can't reach the voice server (" + host + ":" + port + ")";
                DuskClient.LOGGER.warn("[voice] {}", problem, e);
                return;
            }
            Voice.distance = distance;
            DuskClient.LOGGER.info("[voice] connecting to Simple Voice Chat at {}:{}", host, port);
            long started = System.currentTimeMillis(), lastHello = 0;
            byte[] buf = new byte[4096];
            while (running) {
                long now = System.currentTimeMillis();
                if (!connected && now - lastHello >= 1000) {
                    lastHello = now;
                    if (!authenticated) send(AUTHENTICATE, new Wire.Out().uuid(self).raw(secret).done());
                    else send(CHECK, new byte[0]);
                    if (now - started > 15_000) problem = "The voice server isn't answering (UDP port " + port + ")";
                }
                if (connected && now - lastKeepAlive > keepAlive * 10L) {
                    DuskClient.LOGGER.info("[voice] voice server timed out");
                    connected = false;
                    authenticated = false;
                    started = now;
                    problem = "Lost the voice server, reconnecting...";
                }
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException quiet) {
                    continue;
                } catch (Exception e) {
                    if (running) DuskClient.LOGGER.debug("[voice] receive failed", e);
                    continue;
                }
                try {
                    handle(new Wire.In(packet.getData(), packet.getOffset(), packet.getLength()));
                } catch (Exception e) {
                    DuskClient.LOGGER.debug("[voice] bad voice packet", e);
                }
            }
        }

        private void handle(Wire.In in) throws Exception {
            if (in.b() != MAGIC) return;
            byte[] sealed = in.bytes(4096);
            Wire.In p = new Wire.In(open(sealed));
            int id = p.b();
            switch (id) {
                case AUTHENTICATE_ACK -> authenticated = true;
                case CHECK_ACK -> {
                    if (authenticated && !connected) {
                        connected = true;
                        problem = null;
                        lastKeepAlive = System.currentTimeMillis();
                        DuskClient.LOGGER.info("[voice] connected to the voice server");
                        Minecraft.getInstance().execute(SvcClient::sendState);
                    }
                }
                case KEEP_ALIVE -> {
                    lastKeepAlive = System.currentTimeMillis();
                    send(KEEP_ALIVE, new byte[0]);
                }
                case PING -> send(PING, p.rest()); // echoed as it came
                case PLAYER_SOUND -> {
                    UUID channel = p.uuid(), sender = p.uuid();
                    byte[] data = p.bytes(Opus.MAX_PACKET);
                    long seq = p.l();
                    float dist = p.f();
                    boolean whisper = (p.b() & 1) != 0;
                    Voice.heard(Voice.Source.SERVER, channel, sender, seq, data, null, false, dist, whisper);
                }
                case GROUP_SOUND -> {
                    UUID channel = p.uuid(), sender = p.uuid();
                    byte[] data = p.bytes(Opus.MAX_PACKET);
                    long seq = p.l();
                    Voice.heard(Voice.Source.SERVER, channel, sender, seq, data, null, true, 0, false);
                }
                case LOCATION_SOUND -> {
                    UUID channel = p.uuid(), sender = p.uuid();
                    double[] at = {p.d(), p.d(), p.d()};
                    byte[] data = p.bytes(Opus.MAX_PACKET);
                    long seq = p.l();
                    float dist = p.f();
                    Voice.heard(Voice.Source.SERVER, channel, sender, seq, data, at, false, dist, false);
                }
                default -> {}
            }
        }

        void send(int id, byte[] body) {
            DatagramSocket s = socket;
            if (s == null || !running) return;
            try {
                byte[] plain = new byte[body.length + 1];
                plain[0] = (byte) id;
                System.arraycopy(body, 0, plain, 1, body.length);
                byte[] packet = new Wire.Out().b(MAGIC).uuid(self).bytes(seal(plain)).done();
                s.send(new DatagramPacket(packet, packet.length, server));
            } catch (Exception e) {
                DuskClient.LOGGER.debug("[voice] send failed", e);
            }
        }

        private byte[] seal(byte[] plain) throws Exception {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain);
            byte[] out = new byte[12 + enc.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(enc, 0, out, 12, enc.length);
            return out;
        }

        private byte[] open(byte[] sealed) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
            return c.doFinal(sealed, 12, sealed.length - 12);
        }

        void close() {
            running = false;
            connected = false;
            DatagramSocket s = socket;
            if (s != null) s.close();
        }
    }
}
