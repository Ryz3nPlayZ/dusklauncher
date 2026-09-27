package dev.dusk.client.media.impl;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.DuskTitleScreen;
import dev.dusk.client.media.Mcpr;
import dev.dusk.client.media.MediaScreen;
import dev.dusk.client.mixin.media.MinecraftAccessor;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.Connection;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Plays an {@code .mcpr} back the way ReplayMod does: a connection with no
 * server behind it, fed the recorded packets on the client thread as the
 * replay clock reaches them, so the game rebuilds the world exactly as it
 * was received. The viewer is a spectator with a fresh identity (the
 * recorded player is an ordinary entity in the world, and V looks through
 * their eyes); anything the recording would do to the viewer is filtered
 * out by {@link ReplayFilter}.
 *
 * <p>Seeking forward feeds ahead; seeking back reconnects and feeds from
 * the start, since a world can't be un-received.
 */
public final class ReplayPlayer {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Replay");
    /** The viewer's entity id, well clear of anything a server hands out, so the recorded player keeps theirs. */
    public static final int VIEWER_ID = Integer.MIN_VALUE + 42;
    public static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f};
    private static final int SEEK_STEP_MS = 5000;
    private static final int[] KEYS = {GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_P,
            GLFW.GLFW_KEY_V};

    private static @Nullable ReplayPlayer current;

    public final Path file;
    public final String title;
    public final long duration;
    public final boolean clip;
    private final int metaSelfId;

    private @Nullable ZipFile zip;
    private @Nullable DataInputStream in;
    private long nextT = -1;
    private byte @Nullable [] next;
    private boolean eof;
    private @Nullable Connection conn;
    private @Nullable EmbeddedChannel channel;

    private long clock;
    private long seekTo = -1;
    private int speed = 2;
    private boolean paused;
    private boolean feeding;
    private boolean restarting;
    private boolean closed;
    private int realSelfId = -1;
    private boolean pov;
    private final boolean[] held = new boolean[KEYS.length];

    private ReplayPlayer(Path file, JsonObject meta, boolean clip) {
        this.file = file;
        this.clip = clip;
        this.duration = meta.has("duration") ? meta.get("duration").getAsLong() : 0;
        this.metaSelfId = meta.has("selfId") ? meta.get("selfId").getAsInt() : -1;
        String name = file.getFileName().toString();
        this.title = name.endsWith(".mcpr") ? name.substring(0, name.length() - 5) : name;
    }

    public static @Nullable ReplayPlayer current() {
        return current;
    }

    public static boolean active() {
        return current != null;
    }

    public static boolean isReplayConnection(Connection conn) {
        ReplayPlayer p = current;
        return p != null && p.conn == conn;
    }

    /** @return why it could not start, or null once the replay is loading */
    public static @Nullable String start(Path file, boolean clip) {
        if (current != null) return "A replay is already playing.";
        JsonObject meta = Mcpr.readMeta(file);
        if (meta == null) return "That file isn't a replay.";
        int protocol = meta.has("protocol") ? meta.get("protocol").getAsInt() : -1;
        if (protocol != SharedConstants.getProtocolVersion()) {
            String version = meta.has("mcversion") ? meta.get("mcversion").getAsString() : "another version";
            return "Recorded on Minecraft " + version + ". Open it from a " + version + " instance to watch it.";
        }
        ReplayPlayer p = new ReplayPlayer(file, meta, clip);
        current = p;
        try {
            p.connect(Component.literal("Loading replay…"));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open replay {}", file, e);
            p.close();
            return "Could not open the replay: " + e.getMessage();
        }
        return null;
    }

    // ---- the connection -----------------------------------------------------------

    /** Writes go nowhere: there is no server to answer. */
    private static final class DropOutbound extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }
    }

    private void connect(Component loading) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        zip = Mcpr.open(file);
        ZipEntry entry = zip.getEntry(Mcpr.RECORDING);
        if (entry == null) throw new IOException("no recording inside");
        in = new DataInputStream(new BufferedInputStream(zip.getInputStream(entry), 1 << 16));
        eof = false;
        next = null;
        nextT = -1;
        clock = 0;
        realSelfId = metaSelfId;
        pov = false;

        Connection c = new Connection(PacketFlow.CLIENTBOUND) {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
                LOG.warn("Replay packet failed, skipping it", t);
            }
        };
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addFirst("dusk_replay_head", new DropOutbound());
        ch.pipeline().addLast("inbound_config", new UnconfiguredPipelineHandler.Inbound());
        ch.pipeline().addLast("outbound_config", new UnconfiguredPipelineHandler.Outbound());
        ch.pipeline().addLast("dusk_replay_filter", new ReplayFilter());
        ch.pipeline().addLast("packet_handler", c);
        conn = c;
        channel = ch;
        ch.pipeline().fireChannelActive();
        c.setupInboundProtocol(LoginProtocols.CLIENTBOUND, new ClientHandshakePacketListenerImpl(c, mc, null, null, false, null,
                status -> {}, new LevelLoadTracker(), null));
        c.setupOutboundProtocol(LoginProtocols.SERVERBOUND);
        ((MinecraftAccessor) mc).duskclient$setPendingConnection(c);
        Compat.setScreen(mc, new GenericMessageScreen(loading));
    }

    private boolean readNext() {
        if (next != null) return true;
        if (eof || in == null) return false;
        try {
            int t = in.readInt();
            int len = in.readInt();
            if (len < 0 || len > 1 << 25) throw new IOException("bad packet length " + len);
            byte[] b = new byte[len];
            in.readFully(b);
            nextT = t;
            next = b;
            return true;
        } catch (EOFException e) {
            eof = true;
        } catch (IOException e) {
            LOG.warn("Replay stream ended early", e);
            eof = true;
        }
        return false;
    }

    private boolean canFeed() {
        return channel != null && channel.isOpen() && channel.config().isAutoRead() && readNext();
    }

    private void feedOne() {
        byte[] b = next;
        next = null;
        if (b != null && channel != null) channel.pipeline().fireChannelRead(Unpooled.wrappedBuffer(b));
    }

    // ---- ticking ------------------------------------------------------------------

    /** Start of every client tick. */
    public static void tick(Minecraft mc) {
        ReplayPlayer p = current;
        if (p != null && !p.closed) p.step(mc);
    }

    private void step(Minecraft mc) {
        if (feeding) return;
        feeding = true;
        try {
            keys(mc);
            boolean inPlay = conn != null && conn.getPacketListener() instanceof ClientPacketListener;
            if (mc.level != null && inPlay) {
                TickRateManager rate = mc.level.tickRateManager();
                float want = 20 * Math.min(1f, speed());
                if (rate.tickrate() != want) rate.setTickRate(want);
                if (rate.isFrozen() != paused) rate.setFrozen(paused);
                if (seekTo >= 0) {
                    clock = Math.max(clock, seekTo);
                    seekTo = -1;
                } else if (!paused && clock < duration) {
                    clock = Math.min(duration, clock + (long) (50 * Math.max(1f, speed())));
                }
            }
            // until the world exists everything is setup: take it as fast as it comes
            while (canFeed() && (mc.level == null || nextT <= clock)) {
                if (mc.level == null) clock = Math.max(clock, nextT);
                feedOne();
                if (closed) return;
            }
            if (pov) keepPov(mc);
        } finally {
            feeding = false;
        }
    }

    private void keys(Minecraft mc) {
        for (int i = 0; i < KEYS.length; i++) {
            boolean down = Compat.currentScreen(mc) == null && InputConstants.isKeyDown(mc.getWindow(), KEYS[i]);
            if (down && !held[i]) {
                switch (KEYS[i]) {
                    case GLFW.GLFW_KEY_LEFT -> seek(clock - SEEK_STEP_MS);
                    case GLFW.GLFW_KEY_RIGHT -> seek(clock + SEEK_STEP_MS);
                    case GLFW.GLFW_KEY_UP -> setSpeed(speed + 1);
                    case GLFW.GLFW_KEY_DOWN -> setSpeed(speed - 1);
                    case GLFW.GLFW_KEY_P -> paused = !paused;
                    case GLFW.GLFW_KEY_V -> togglePov();
                    default -> {}
                }
            }
            held[i] = down;
        }
    }

    // ---- controls -----------------------------------------------------------------

    public long clock() {
        return clock;
    }

    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public float speed() {
        return SPEEDS[speed];
    }

    public int speedIndex() {
        return speed;
    }

    public void setSpeed(int index) {
        speed = Math.max(0, Math.min(SPEEDS.length - 1, index));
    }

    public boolean pov() {
        return pov;
    }

    public void seek(long to) {
        to = Math.max(0, Math.min(duration, to));
        if (to >= clock) {
            seekTo = to;
            return;
        }
        restart(to);
    }

    /** A world can't be un-received, so going back means loading it again and feeding up to {@code to}. */
    private void restart(long to) {
        Minecraft mc = Minecraft.getInstance();
        restarting = true;
        try {
            if (mc.level != null) mc.level.disconnect(Component.literal("Seeking"));
            mc.disconnect(new GenericMessageScreen(Component.literal("Seeking…")), false);
            closeStream();
            connect(Component.literal("Seeking…"));
            seekTo = to;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Replay seek failed", e);
            close();
            Compat.setScreen(mc, back(mc));
        } finally {
            restarting = false;
        }
    }

    public void togglePov() {
        Minecraft mc = Minecraft.getInstance();
        if (pov) {
            pov = false;
            if (mc.player != null) mc.setCameraEntity(mc.player);
            return;
        }
        Entity target = mc.level == null || realSelfId == -1 ? null : mc.level.getEntity(realSelfId);
        if (target == null) {
            if (mc.player != null) MediaCompat.message(mc.player, Component.literal("The recording player isn't in view right now."), true);
            return;
        }
        pov = true;
        mc.setCameraEntity(target);
    }

    private void keepPov(Minecraft mc) {
        Entity cam = mc.getCameraEntity();
        if (cam != null && cam != mc.player && !cam.isRemoved()) return;
        Entity target = mc.level == null ? null : mc.level.getEntity(realSelfId);
        if (target != null) {
            mc.setCameraEntity(target);
        } else if (mc.player != null && cam != mc.player) {
            mc.setCameraEntity(mc.player);
        }
    }

    /** Leaves the replay for the media page. */
    public void exit() {
        Minecraft mc = Minecraft.getInstance();
        Screen back = back(mc);
        if (mc.level != null) {
            mc.level.disconnect(Component.literal("Replay closed"));
        } else if (conn != null) {
            conn.disconnect(Component.literal("Replay closed"));
        }
        close();
        mc.disconnect(back, false);
    }

    private Screen back(Minecraft mc) {
        return new MediaScreen(new DuskTitleScreen(), clip ? MediaScreen.CLIPS : MediaScreen.REPLAYS);
    }

    // ---- hooks --------------------------------------------------------------------

    /** The recorded player's id, from the login packet, before the viewer takes {@link #VIEWER_ID}. */
    public static int onLogin(int recordedId) {
        ReplayPlayer p = current;
        if (p == null) return recordedId;
        p.realSelfId = recordedId;
        return VIEWER_ID;
    }

    /** The world went away without us asking (a crash in a packet, the window closing). */
    public static void onDisconnect(@Nullable Connection c) {
        ReplayPlayer p = current;
        if (p == null || p.restarting) return;
        if (c == null || c == p.conn) p.close();
    }

    private void closeStream() {
        try {
            if (zip != null) zip.close();
        } catch (IOException ignored) {
        }
        zip = null;
        in = null;
        next = null;
    }

    private void close() {
        if (closed) return;
        closed = true;
        closeStream();
        if (channel != null) channel.close();
        channel = null;
        conn = null;
        if (current == this) current = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.tickRateManager().setTickRate(20);
            mc.level.tickRateManager().setFrozen(false);
        }
    }
}
