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
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Plays an {@code .mcpr} back the way ReplayMod does: a connection with no
 * server behind it, fed the recorded packets on the client thread as the
 * replay clock reaches them, so the game rebuilds the world exactly as it
 * was received. The viewer is a spectator with a fresh identity (the
 * recorded player is an ordinary entity in the world, and V looks through
 * their eyes, or any other player's); anything the recording would do to
 * the viewer is filtered out by {@link ReplayFilter}.
 *
 * <p>Packets meant for "you" (inventory, held slot, experience) still land
 * on the viewer, so while watching the recorded player the HUD shows their
 * hotbar, hearts and food rather than the spectator bar.
 *
 * <p>Seeking forward feeds ahead; seeking back reconnects and feeds from
 * the start, since a world can't be un-received, then puts the camera
 * back where it was.
 */
public final class ReplayPlayer {
    private static final Logger LOG = LoggerFactory.getLogger("DuskClient/Replay");
    /** The viewer's entity id, well clear of anything a server hands out, so the recorded player keeps theirs. */
    public static final int VIEWER_ID = Integer.MIN_VALUE + 42;
    public static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f};
    private static final int SEEK_STEP_MS = 5000;
    private static final int[] KEYS = {InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN, InputConstants.KEY_P,
            InputConstants.KEY_V, InputConstants.KEY_COMMA, InputConstants.KEY_PERIOD, InputConstants.KEY_I, InputConstants.KEY_O};

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
    /** Whose eyes the camera is in, or -1 for the free camera. */
    private int povId = -1;
    /** The stretch EXPORT VIDEO covers, in replay ms, or -1 for the start / the end. */
    private long markIn = -1, markOut = -1;
    private final boolean[] held = new boolean[KEYS.length];
    private boolean sneakHeld;

    // the recorded player's hunger, which only ever reaches "you"
    private int food = 20;
    private float saturation = 5f;

    // where the camera was before seeking back, put back once the world is rebuilt
    private boolean resumePending;
    private @Nullable Vec3 resumePos;
    private float resumeYRot, resumeXRot;
    private @Nullable ResourceKey<Level> resumeDim;
    private int resumePovId = -1;

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
        povId = -1;

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
            if (resumePending && inPlay && seekTo < 0 && mc.level != null && mc.player != null) resume(mc);
            if (povId != -1) keepPov(mc);
        } finally {
            feeding = false;
        }
    }

    private void keys(Minecraft mc) {
        for (int i = 0; i < KEYS.length; i++) {
            boolean down = Compat.currentScreen(mc) == null && InputConstants.isKeyDown(mc.getWindow(), KEYS[i]);
            // an export owns the clock: going back would reload the world mid-video
            boolean locked = VideoExporter.active() && KEYS[i] != InputConstants.KEY_V && KEYS[i] != InputConstants.KEY_COMMA
                    && KEYS[i] != InputConstants.KEY_PERIOD;
            if (down && !held[i] && !locked) {
                switch (KEYS[i]) {
                    case InputConstants.KEY_LEFT -> seek(clock - SEEK_STEP_MS);
                    case InputConstants.KEY_RIGHT -> seek(clock + SEEK_STEP_MS);
                    case InputConstants.KEY_UP -> setSpeed(speed + 1);
                    case InputConstants.KEY_DOWN -> setSpeed(speed - 1);
                    case InputConstants.KEY_P -> paused = !paused;
                    case InputConstants.KEY_V -> togglePov();
                    case InputConstants.KEY_COMMA -> cyclePov(-1);
                    case InputConstants.KEY_PERIOD -> cyclePov(1);
                    case InputConstants.KEY_I -> setMarkIn(clock);
                    case InputConstants.KEY_O -> setMarkOut(clock);
                    default -> {}
                }
            }
            held[i] = down;
        }
        boolean free = Compat.currentScreen(mc) == null;
        // like vanilla spectating: sneak to let go, attack a player to look through their eyes
        boolean sneak = free && mc.options.keyShift.isDown();
        if (sneak && !sneakHeld && povId != -1) leavePov();
        sneakHeld = sneak;
        if (free) {
            while (mc.options.keyAttack.consumeClick()) {
                if (povId == -1 && mc.crosshairPickEntity instanceof Player target && target != mc.player) watch(target);
            }
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
        return povId != -1;
    }

    public long markIn() {
        return markIn;
    }

    public long markOut() {
        return markOut;
    }

    /** Marks where an export starts; -1 clears it. An out mark before it is dropped. */
    public void setMarkIn(long t) {
        markIn = t < 0 ? -1 : Math.min(t, duration);
        if (markIn >= 0 && markOut >= 0 && markOut <= markIn) markOut = -1;
    }

    /** Marks where an export ends; -1 clears it. An in mark after it is dropped. */
    public void setMarkOut(long t) {
        markOut = t < 0 ? -1 : Math.min(t, duration);
        if (markIn >= 0 && markOut >= 0 && markIn >= markOut) markIn = -1;
    }

    /** The world is up, every packet up to the clock is in, and no seek is still landing. */
    boolean settled() {
        Minecraft mc = Minecraft.getInstance();
        return !closed && conn != null && conn.getPacketListener() instanceof ClientPacketListener && mc.level != null
                && mc.player != null && seekTo < 0 && !resumePending
                && (Compat.currentScreen(mc) == null || Compat.currentScreen(mc) instanceof ReplayMenuScreen);
    }

    boolean closed() {
        return closed;
    }

    /** The name of the player being watched, or null on the free camera. */
    public @Nullable String povName() {
        Minecraft mc = Minecraft.getInstance();
        if (povId == -1 || mc.level == null) return null;
        Entity e = mc.level.getEntity(povId);
        return e instanceof Player pl ? pl.getGameProfile().name() : null;
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
        rememberCamera(mc);
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

    private void rememberCamera(Minecraft mc) {
        LocalPlayer viewer = mc.player;
        resumePending = true;
        resumePovId = povId;
        resumePos = viewer == null ? null : viewer.position();
        resumeDim = mc.level == null ? null : mc.level.dimension();
        if (viewer != null) {
            resumeYRot = viewer.getYRot();
            resumeXRot = viewer.getXRot();
        }
    }

    /** After a seek back has rebuilt the world: the camera goes back where it was, not to the spawn point. */
    private void resume(Minecraft mc) {
        resumePending = false;
        if (resumePos != null && mc.level.dimension() == resumeDim) {
            mc.player.snapTo(resumePos.x, resumePos.y, resumePos.z, resumeYRot, resumeXRot);
        }
        povId = resumePovId;
    }

    /** V: into the recorded player's eyes, or back out to the free camera. */
    public void togglePov() {
        Minecraft mc = Minecraft.getInstance();
        if (povId != -1) {
            leavePov();
            return;
        }
        Entity target = mc.level == null || realSelfId == -1 ? null : mc.level.getEntity(realSelfId);
        if (target == null) {
            List<Player> players = watchable(mc);
            if (!players.isEmpty()) {
                watch(players.get(0));
                return;
            }
            if (mc.player != null) MediaCompat.message(mc.player, Component.literal("The recording player isn't in view right now."), true);
            return;
        }
        watch(target);
    }

    /** , and . : the previous or next player in the world, the recorded player first. */
    public void cyclePov(int dir) {
        Minecraft mc = Minecraft.getInstance();
        List<Player> players = watchable(mc);
        if (players.isEmpty()) {
            if (mc.player != null) MediaCompat.message(mc.player, Component.literal("No players in view right now."), true);
            return;
        }
        int at = -1;
        for (int i = 0; i < players.size(); i++) if (players.get(i).getId() == povId) at = i;
        int next = at == -1 ? (dir > 0 ? 0 : players.size() - 1) : Math.floorMod(at + dir, players.size());
        watch(players.get(next));
    }

    private List<Player> watchable(Minecraft mc) {
        List<Player> out = new ArrayList<>();
        if (mc.level == null) return out;
        for (Player pl : mc.level.players()) if (pl != mc.player && !pl.isRemoved()) out.add(pl);
        out.sort(Comparator.<Player>comparingInt(pl -> pl.getId() == realSelfId ? 0 : 1)
                .thenComparing(pl -> pl.getGameProfile().name(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void watch(Entity target) {
        povId = target.getId();
        Minecraft.getInstance().setCameraEntity(target);
    }

    /** Back to the free camera, left where the watched player was standing so the shot carries on from there. */
    public void leavePov() {
        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.getCameraEntity();
        povId = -1;
        if (mc.player == null) return;
        if (cam != null && cam != mc.player && !cam.isRemoved()) {
            mc.player.snapTo(cam.getX(), cam.getY(), cam.getZ(), cam.getYRot(), cam.getXRot());
        }
        mc.setCameraEntity(mc.player);
    }

    private void keepPov(Minecraft mc) {
        Entity cam = mc.getCameraEntity();
        if (cam == null || cam == mc.player || cam.isRemoved() || cam.getId() != povId) {
            Entity target = mc.level == null ? null : mc.level.getEntity(povId);
            if (target != null) {
                mc.setCameraEntity(target);
                cam = target;
            } else if (mc.player != null && cam != mc.player) {
                mc.setCameraEntity(mc.player);
                return;
            } else {
                return;
            }
        }
        if (mc.player == null) return;
        follow(mc.player, cam);
        if (cam.getId() == realSelfId && cam instanceof Player self) mirror(mc.player, self);
    }

    /**
     * The viewer rides along inside the watched player: same place (so the
     * hand is lit by their surroundings) and same look (so the hand's sway
     * follows their head, not the mouse).
     */
    private static void follow(LocalPlayer viewer, Entity cam) {
        viewer.snapTo(cam.getX(), cam.getY(), cam.getZ(), cam.getYRot(), cam.getXRot());
        viewer.xRotO = cam.xRotO;
        viewer.yRotO = cam.yRotO;
        viewer.setDeltaMovement(Vec3.ZERO);
        if (cam instanceof Player pl) viewer.swingingArm = pl.swingingArm;
    }

    /**
     * Who lends the viewer their hands for the first-person view: the
     * watched player, while the camera is in their eyes. Called from
     * entity getters, so it bails out cheaply when no replay is playing.
     */
    public static @Nullable Player handDonor(Object entity) {
        if (current == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player) return null;
        return watched();
    }

    /**
     * The recorded player's inventory, held slot and hunger arrived as
     * packets to "you", so they live on the viewer; copy them onto the
     * player being watched so the HUD can draw them.
     */
    private void mirror(LocalPlayer viewer, Player self) {
        self.getInventory().replaceWith(viewer.getInventory());
        self.getFoodData().setFoodLevel(food);
        self.getFoodData().setSaturation(saturation);
    }

    // ---- what the HUD shows while watching someone --------------------------------

    /** The player whose eyes the camera is in, or null on the free camera. */
    public static @Nullable Player watched() {
        ReplayPlayer p = current;
        if (p == null || p.povId == -1) return null;
        Minecraft mc = Minecraft.getInstance();
        return mc.getCameraEntity() instanceof Player pl && pl != mc.player && pl.getId() == p.povId ? pl : null;
    }

    /** Whether the camera is in the recorded player's eyes, whose whole HUD is known. */
    public static boolean watchingRecorder() {
        Player pl = watched();
        ReplayPlayer p = current;
        return pl != null && p != null && pl.getId() == p.realSelfId;
    }

    /** The game mode the HUD should draw for, or null to leave it alone (the free camera is a spectator). */
    public static @Nullable GameType hudMode() {
        Player pl = watched();
        if (pl == null) return null;
        GameType mode = pl instanceof AbstractClientPlayer a ? a.gameMode() : null;
        return mode == null ? GameType.SURVIVAL : mode;
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
    /** Health and hunger meant for the recorded player; the viewer must not take them (it would die with them). */
    static void onHealth(int food, float saturation) {
        ReplayPlayer p = current;
        if (p == null) return;
        p.food = food;
        p.saturation = saturation;
    }

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
        VideoExporter.replayClosed();
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
