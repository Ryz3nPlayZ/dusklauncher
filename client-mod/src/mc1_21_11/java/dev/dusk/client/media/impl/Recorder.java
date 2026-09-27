package dev.dusk.client.media.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.media.Mcpr;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records what the server sends, the way ReplayMod does: every clientbound
 * packet, re-encoded with the protocol it arrived under, plus the player's
 * own entity (which the server never sends to its owner, see
 * {@link SelfTracker}). In full mode the stream goes to a file under
 * {@code replays/}; in clips mode it goes to a {@link ClipBuffer} that
 * keeps the last N seconds, saved to {@code clips/} on the clip key.
 *
 * <p>Packets arrive on the network thread and the player's own on the main
 * thread; encoding happens on the thread that saw the packet and all file
 * work on one writer thread.
 */
public final class Recorder {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Recorder");
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Dusk Recorder");
        t.setDaemon(true);
        return t;
    });
    /** Wait this long in a world before taking a full replay's thumbnail. */
    private static final int THUMB_DELAY_TICKS = 100;

    private static volatile @Nullable Session session;

    private Recorder() {}

    // ---- hooks ------------------------------------------------------------------

    /** Every clientbound packet, from {@code Connection.channelRead0}. */
    public static void onPacket(Connection conn, @Nullable ProtocolInfo<?> protocol, Packet<?> packet) {
        if (protocol == null) return;
        if (packet instanceof ClientboundLoginFinishedPacket) start(conn);
        Session s = session;
        if (s != null && s.conn == conn) s.capture(protocol, packet);
    }

    /** Main thread, once per client tick. */
    public static void tick(Minecraft mc) {
        Session s = session;
        if (s == null) return;
        if (!s.conn.isConnected()) {
            finish(s);
            return;
        }
        if (mc.isPaused()) s.pausedMs += 50;
        s.tick(mc);
    }

    public static void onDisconnect(@Nullable Connection conn) {
        Session s = session;
        if (s != null && (conn == null || s.conn == conn)) finish(s);
    }

    /** The mode or clip length changed in the settings. */
    public static void settingsChanged() {
        Session s = session;
        if (s == null) return;
        String mode = DuskConfig.get().recordingMode;
        if (!mode.equals(s.clips ? "clips" : "full")) {
            finish(s);
            return;
        }
        if (s.buffer != null) {
            long ms = clipMs();
            WRITER.execute(() -> s.buffer.setClipMs(ms));
        }
    }

    public static boolean recording() {
        Session s = session;
        return s != null && !s.clips;
    }

    public static boolean buffering() {
        Session s = session;
        return s != null && s.clips;
    }

    public static long elapsedMs() {
        Session s = session;
        return s == null ? 0 : s.now();
    }

    // ---- sessions ---------------------------------------------------------------

    private static synchronized void start(Connection conn) {
        Session old = session;
        if (old != null && old.conn == conn) return;
        if (old != null) finish(old);
        String mode = DuskConfig.get().recordingMode;
        if (!mode.equals("clips") && !mode.equals("full")) return;
        try {
            session = new Session(conn, mode.equals("clips"));
        } catch (IOException e) {
            LOG.warn("Could not start recording", e);
        }
    }

    private static synchronized void finish(Session s) {
        if (session == s) session = null;
        if (s.finished) return;
        s.finished = true;
        long duration = s.now();
        WRITER.execute(() -> s.close(duration));
    }

    // ---- clips ------------------------------------------------------------------

    public static void saveClip() {
        Minecraft mc = Minecraft.getInstance();
        Session s = session;
        if (s == null || !s.clips) {
            String why = s != null ? "Clips are off: this world is being recorded in full."
                    : DuskConfig.get().recordingMode.equals("clips") ? "Nothing to clip yet: clips start buffering when you join a world."
                    : "Clips are off. Turn them on in Media → Settings.";
            tell(mc, why);
            return;
        }
        long at = s.now();
        grabThumb(mc, thumb -> WRITER.execute(() -> s.writeClip(at, thumb)));
    }

    private static void tell(Minecraft mc, String text) {
        mc.execute(() -> {
            if (mc.player != null) MediaCompat.message(mc.player, Component.literal(text), false);
        });
    }

    // ---- encoding ---------------------------------------------------------------

    /** The packet as it went over the wire, or null when it cannot be re-encoded. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static byte @Nullable [] encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ((StreamCodec) protocol.codec()).encode(buf, packet);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } catch (RuntimeException e) {
            LOG.debug("Skipping {} in the recording: {}", packet.getClass().getSimpleName(), e.toString());
            return null;
        } finally {
            buf.release();
        }
    }

    /** Only matters to the live connection. */
    private static boolean skip(Packet<?> p) {
        return p instanceof ClientboundCustomPayloadPacket || p instanceof ClientboundKeepAlivePacket
                || p instanceof ClientboundPingPacket || p instanceof ClientboundPongResponsePacket
                || p instanceof ClientboundChunkBatchStartPacket || p instanceof ClientboundChunkBatchFinishedPacket
                || p instanceof ClientboundCustomQueryPacket;
    }

    // ---- thumbnails -------------------------------------------------------------

    interface ThumbSink {
        void accept(byte @Nullable [] png);
    }

    /** A 480-wide PNG of the current frame, delivered off the main thread (or null when it fails). */
    private static void grabThumb(Minecraft mc, ThumbSink sink) {
        try {
            Screenshot.takeScreenshot(Compat.mainTarget(mc), image -> {
                try (image) {
                    int w = 480, h = Math.max(1, image.getHeight() * w / Math.max(1, image.getWidth()));
                    Path tmp = Files.createTempFile("dusk-thumb", ".png");
                    try (NativeImage small = new NativeImage(w, h, false)) {
                        image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
                        small.writeToFile(tmp);
                        sink.accept(Files.readAllBytes(tmp));
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                } catch (IOException | RuntimeException e) {
                    sink.accept(null);
                }
            });
        } catch (RuntimeException e) {
            sink.accept(null);
        }
    }

    // ---- one recording ----------------------------------------------------------

    static final class Session {
        final Connection conn;
        final boolean clips;
        final boolean singleplayer;
        final long start = System.nanoTime();
        volatile long pausedMs;
        volatile boolean finished;
        volatile @Nullable ProtocolInfo<?> play;
        volatile int selfId = -1;
        volatile String serverName = "";
        final Set<UUID> players = ConcurrentHashMap.newKeySet();
        final SelfTracker self = new SelfTracker();
        private int ticksInWorld;
        private boolean thumbRequested;
        private volatile byte @Nullable [] thumb;

        // writer thread
        final @Nullable ClipBuffer buffer;
        private final @Nullable Path tmp;
        private final @Nullable OutputStream out;
        private long lastT;

        Session(Connection conn, boolean clips) throws IOException {
            this.conn = conn;
            this.clips = clips;
            this.singleplayer = conn.isMemoryConnection();
            if (clips) {
                buffer = new ClipBuffer(clipMs());
                tmp = null;
                out = null;
            } else {
                buffer = null;
                Files.createDirectories(Mcpr.replaysDir());
                tmp = Files.createTempFile(Mcpr.replaysDir(), ".recording", ".tmcpr");
                out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
            }
        }

        long now() {
            return (System.nanoTime() - start) / 1_000_000 - pausedMs;
        }

        void capture(ProtocolInfo<?> protocol, Packet<?> packet) {
            if (packet instanceof BundlePacket<?> bundle) {
                for (Packet<?> sub : bundle.subPackets()) capture(protocol, sub);
                return;
            }
            if (finished || skip(packet)) return;
            byte[] bytes = encode(protocol, packet);
            if (bytes == null) return;
            if (protocol.id() == ConnectionProtocol.PLAY) play = protocol;
            if (packet instanceof ClientboundLoginPacket login) selfId = login.playerId();
            if (packet instanceof ClientboundAddEntityPacket add && MediaCompat.isPlayer(add.getType())) players.add(add.getUUID());
            ClipBuffer.Tag tag = clips ? ClipBuffer.classify(protocol, packet) : null;
            long t = now();
            WRITER.execute(() -> write(t, bytes, tag, protocol));
        }

        /** A packet about the player's own entity, made on the main thread. */
        void captureSelf(Packet<?> packet) {
            ProtocolInfo<?> protocol = play;
            if (protocol != null) capture(protocol, packet);
        }

        void tick(Minecraft mc) {
            if (serverName.isEmpty()) {
                if (mc.getSingleplayerServer() != null) {
                    serverName = mc.getSingleplayerServer().getWorldData().getLevelName();
                } else {
                    ServerData data = mc.getCurrentServer();
                    if (data != null) serverName = data.ip;
                }
            }
            if (mc.getConnection() == null || mc.getConnection().getConnection() != conn || mc.player == null || mc.level == null) return;
            self.tick(this, mc.player);
            if (!clips && !thumbRequested && Compat.currentScreen(mc) == null && ++ticksInWorld >= THUMB_DELAY_TICKS) {
                thumbRequested = true;
                grabThumb(mc, png -> thumb = png);
            }
        }

        // writer thread from here on

        private void write(long t, byte[] bytes, ClipBuffer.@Nullable Tag tag, ProtocolInfo<?> protocol) {
            t = Math.max(t, lastT);
            lastT = t;
            try {
                if (buffer != null && tag != null) {
                    buffer.accept(t, bytes, tag, protocol);
                } else if (out != null) {
                    Mcpr.writeInt(out, (int) t);
                    Mcpr.writeInt(out, bytes.length);
                    out.write(bytes);
                }
            } catch (IOException e) {
                LOG.warn("Recording write failed", e);
            }
        }

        private void writeClip(long at, byte @Nullable [] png) {
            if (buffer == null) return;
            List<ClipBuffer.Out> packets = buffer.snapshot(Math.max(at, lastT));
            Minecraft mc = Minecraft.getInstance();
            if (packets.isEmpty()) {
                tell(mc, "Nothing to clip yet.");
                return;
            }
            Path file = Mcpr.newFile(Mcpr.clipsDir(), "clip_");
            Path stream = null;
            try {
                Files.createDirectories(Mcpr.clipsDir());
                stream = Files.createTempFile(Mcpr.clipsDir(), ".clip", ".tmcpr");
                long duration = 0;
                try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(stream), 1 << 16)) {
                    for (ClipBuffer.Out p : packets) {
                        Mcpr.writeInt(o, (int) p.t());
                        Mcpr.writeInt(o, p.bytes().length);
                        o.write(p.bytes());
                        duration = Math.max(duration, p.t());
                    }
                }
                Mcpr.write(file, meta(duration), stream, png);
                tell(mc, "Saved a " + Mcpr.duration(duration) + " clip. Watch it in Media → Clips.");
            } catch (IOException e) {
                LOG.warn("Could not save clip", e);
                tell(mc, "Could not save the clip: " + e.getMessage());
            } finally {
                if (stream != null) {
                    try {
                        Files.deleteIfExists(stream);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private void close(long duration) {
            if (out == null || tmp == null) return;
            try {
                out.close();
                if (lastT >= 1000) {
                    Path file = Mcpr.newFile(Mcpr.replaysDir(), "");
                    Mcpr.write(file, meta(lastT), tmp, thumb);
                    LOG.info("Saved replay {} ({})", file.getFileName(), Mcpr.duration(lastT));
                }
            } catch (IOException e) {
                LOG.warn("Could not save replay", e);
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }

        private JsonObject meta(long duration) {
            JsonObject m = new JsonObject();
            m.addProperty("singleplayer", singleplayer);
            m.addProperty("serverName", serverName);
            m.addProperty("duration", (int) duration);
            m.addProperty("date", System.currentTimeMillis());
            m.addProperty("mcversion", SharedConstants.getCurrentVersion().name());
            m.addProperty("fileFormat", "MCPR");
            m.addProperty("fileFormatVersion", 14);
            m.addProperty("protocol", SharedConstants.getProtocolVersion());
            m.addProperty("generator", "DuskClient");
            m.addProperty("selfId", selfId);
            JsonArray list = new JsonArray();
            for (UUID id : players) list.add(id.toString());
            m.add("players", list);
            return m;
        }
    }

    // ---- status -----------------------------------------------------------------

    public static long clipMs() {
        return Math.max(5, DuskConfig.get().clipSeconds) * 1000L;
    }

    /** A line for the settings page and the menu footer. */
    public static String status() {
        Session s = session;
        String mode = DuskConfig.get().recordingMode;
        if (s != null && !s.clips) return "Recording this world · " + Mcpr.duration(s.now());
        if (s != null) return "Keeping the last " + DuskConfig.get().clipSeconds + "s · clip key saves it";
        return switch (mode) {
            case "clips" -> "Clips start buffering when you join a world";
            case "full" -> "Replays record when you join a world";
            default -> "Recording is off";
        };
    }
}
