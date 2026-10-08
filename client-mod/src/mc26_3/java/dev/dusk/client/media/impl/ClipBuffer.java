package dev.dusk.client.media.impl;

import dev.dusk.client.mixin.media.MoveEntityAccessor;
import dev.dusk.client.mixin.media.RotateHeadAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The clip buffer: the last {@code clipMs} of packets as recorded, plus
 * everything older squashed into the smallest packet list that rebuilds
 * the world as it stood when the window starts (ReplayMod's approach to
 * starting a replay mid-way). Squashing keeps the latest of anything that
 * is state (chunks, entities and where they are, the tab list, teams,
 * boss bars, scores, time, border) and drops what only happened once
 * (sounds, particles, chat, animations).
 *
 * <p>Writer thread only, apart from {@link #classify}, which runs where the
 * packet is captured.
 */
final class ClipBuffer {
    record Entry(long t, byte[] bytes, Tag tag, ProtocolInfo<?> protocol) {}

    /** One output packet: its time in the clip and its bytes. */
    record Out(long t, byte[] bytes) {}

    private final ArrayDeque<Entry> window = new ArrayDeque<>();
    private final State state = new State();
    private long clipMs;
    private long bytes;

    ClipBuffer(long clipMs) {
        this.clipMs = clipMs;
    }

    void setClipMs(long ms) {
        clipMs = ms;
    }

    long bufferedBytes() {
        return bytes;
    }

    void accept(long t, byte[] b, Tag tag, ProtocolInfo<?> protocol) {
        window.add(new Entry(t, b, tag, protocol));
        bytes += b.length;
        trim(t);
    }

    private void trim(long now) {
        while (!window.isEmpty() && window.peekFirst().t < now - clipMs) {
            Entry e = window.pollFirst();
            bytes -= e.bytes.length;
            state.squash(e);
        }
    }

    /** The clip ending {@code now}: the squashed world at time 0, then the window. */
    List<Out> snapshot(long now) {
        trim(now);
        List<Out> out = new ArrayList<>();
        long base;
        if (state.touched) {
            for (byte[] b : state.serialize()) out.add(new Out(0, b));
            base = now - clipMs;
        } else {
            base = window.isEmpty() ? now : window.peekFirst().t;
        }
        for (Entry e : window) out.add(new Out(Math.max(0, e.t - base), e.bytes));
        return out;
    }

    // ---- classification (capture thread) ----------------------------------------

    sealed interface Tag permits Simple, Login, Respawn, Latest, Clear, ChunkTag, EntAdd, EntMove, EntSync, EntTeleport,
            EntMotion, EntHead, EntCover, EntUncover, EntLink, EntRemove {}

    enum Simple implements Tag {
        /** Only matters when it happens. */
        DROP,
        /** LoginFinished: a fresh session. */
        PRELUDE_START,
        /** StartConfiguration: the server is about to resend the whole configuration. */
        PRELUDE_RESET,
        /** A configuration packet: part of the prelude. */
        PRELUDE,
        KEEP_SESSION,
        KEEP_LEVEL,
    }

    record Login(int selfId) implements Tag {}

    record Respawn(ResourceKey<Level> dimension) implements Tag {}

    /** Replaces the last packet with the same key; {@code reset} clears its group first. */
    record Latest(boolean level, Object key, @Nullable String group, boolean reset) implements Tag {}

    /** Forgets a group (a removed team, boss bar or objective). */
    record Clear(boolean level, String group) implements Tag {}

    /** kind: 0 chunk data, 1 forget, 2 appended extra, 3 keyed extra. */
    record ChunkTag(int kind, long pos, @Nullable Object key) implements Tag {}

    record EntAdd(int id, UUID uuid, EntityType<?> type, int data, Vec3 pos, Vec3 motion, float yRot, float xRot, float head) implements Tag {}

    record EntMove(int id, VecDelta delta, boolean hasPos, float yRot, float xRot, boolean hasRot, boolean onGround) implements Tag {}

    record EntSync(int id, Vec3 pos, boolean hasPos, float yRot, float xRot, boolean hasRot, boolean onGround) implements Tag {}

    record EntTeleport(int id, PositionMoveRotation change, Set<Relative> relatives, boolean onGround) implements Tag {}

    record EntMotion(int id, Vec3 motion) implements Tag {}

    record EntHead(int id, float head) implements Tag {}

    /** Entity state sent in parts: 0 data, 1 equipment, 2 attributes, 3 effects. The newest packet for each key wins. */
    record EntCover(int id, int group, Set<Object> keys) implements Tag {}

    record EntUncover(int id, int group, Object key) implements Tag {}

    record EntLink(Object key) implements Tag {}

    record EntRemove(int[] ids) implements Tag {}

    private record BlockKey(boolean entity, long pos) {}

    private record Key(String kind, @Nullable Object a, @Nullable Object b) {}

    static Tag classify(ProtocolInfo<?> protocol, Packet<?> p) {
        if (p instanceof ClientboundLoginFinishedPacket) return Simple.PRELUDE_START;
        if (protocol.id() == ConnectionProtocol.CONFIGURATION) return Simple.PRELUDE;
        if (protocol.id() != ConnectionProtocol.PLAY) return Simple.DROP;
        return switch (p) {
            case ClientboundStartConfigurationPacket x -> Simple.PRELUDE_RESET;
            case ClientboundLoginPacket l -> new Login(l.playerId());
            case ClientboundRespawnPacket r -> new Respawn(r.commonPlayerSpawnInfo().dimension());

            // the world
            case ClientboundLevelChunkWithLightPacket c -> new ChunkTag(0, MediaCompat.chunk(c.x(), c.z()), null);
            case ClientboundForgetLevelChunkPacket f -> new ChunkTag(1, MediaCompat.chunk(f.pos()), null);
            case ClientboundLightUpdatePacket l -> new ChunkTag(2, MediaCompat.chunk(l.x(), l.z()), null);
            case ClientboundSectionBlocksUpdatePacket s -> {
                long[] pos = {Long.MIN_VALUE};
                s.runUpdates((bp, st) -> {
                    if (pos[0] == Long.MIN_VALUE) pos[0] = MediaCompat.chunk(bp);
                });
                yield pos[0] == Long.MIN_VALUE ? Simple.DROP : new ChunkTag(2, pos[0], null);
            }
            case ClientboundBlockUpdatePacket b -> blockTag(b.getPos(), false);
            case ClientboundBlockEntityDataPacket b -> blockTag(b.getPos(), true);
            case ClientboundChunksBiomesPacket x -> Simple.KEEP_LEVEL;
            case ClientboundSetChunkCacheCenterPacket x -> latest(true, x);
            case ClientboundSetDefaultSpawnPositionPacket x -> latest(true, x);
            case ClientboundInitializeBorderPacket x -> latest(true, x);
            case ClientboundSetBorderCenterPacket x -> latest(true, x);
            case ClientboundSetBorderLerpSizePacket x -> latest(true, x);
            case ClientboundSetBorderSizePacket x -> latest(true, x);
            case ClientboundSetBorderWarningDelayPacket x -> latest(true, x);
            case ClientboundSetBorderWarningDistancePacket x -> latest(true, x);
            case ClientboundGameEventPacket g -> gameEvent(g);
            case ClientboundMapItemDataPacket x -> Simple.KEEP_SESSION;

            // the session
            case ClientboundSetChunkCacheRadiusPacket x -> latest(false, x);
            case ClientboundSetSimulationDistancePacket x -> latest(false, x);
            case ClientboundSetTimePacket x -> latest(false, x);
            case ClientboundChangeDifficultyPacket x -> latest(false, x);
            case ClientboundServerDataPacket x -> latest(false, x);
            case ClientboundCommandsPacket x -> latest(false, x);
            case ClientboundUpdateRecipesPacket x -> latest(false, x);
            case ClientboundUpdateTagsPacket x -> latest(false, x);
            case ClientboundServerLinksPacket x -> latest(false, x);
            case ClientboundTabListPacket x -> latest(false, x);
            case ClientboundPlayerInfoUpdatePacket u -> u.actions().size() == 1
                    && u.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY) ? Simple.DROP : Simple.KEEP_SESSION;
            case ClientboundPlayerInfoRemovePacket x -> Simple.KEEP_SESSION;
            case ClientboundSetPlayerTeamPacket t -> teamTag(t);
            case ClientboundBossEventPacket b -> bossTag(b);
            case ClientboundSetObjectivePacket o -> switch (o.getMethod()) {
                case ClientboundSetObjectivePacket.METHOD_ADD -> new Latest(false, new Key("obj", o.getObjectiveName(), null), "obj:" + o.getObjectiveName(), true);
                case ClientboundSetObjectivePacket.METHOD_REMOVE -> new Clear(false, "obj:" + o.getObjectiveName());
                default -> new Latest(false, new Key("objchg", o.getObjectiveName(), null), "obj:" + o.getObjectiveName(), false);
            };
            case ClientboundSetDisplayObjectivePacket d -> new Latest(false, new Key("display", d.getSlot(), null), null, false);
            case ClientboundSetScorePacket s -> new Latest(false, new Key("score", s.owner(), s.objectiveName()), "obj:" + s.objectiveName(), false);
            case ClientboundResetScorePacket s -> new Latest(false, new Key("score", s.owner(), s.objectiveName()),
                    s.objectiveName() == null ? null : "obj:" + s.objectiveName(), false);

            // entities
            case ClientboundAddEntityPacket a -> new EntAdd(a.getId(), a.getUUID(), a.getType(), a.getData(),
                    new Vec3(a.getX(), a.getY(), a.getZ()), a.getMovement(), a.getYRot(), a.getXRot(), a.getYHeadRot());
            case ClientboundMoveEntityPacket m -> new EntMove(((MoveEntityAccessor) m).duskclient$entityId(), m.getPositionDelta(),
                    m.hasPosition(), m.getYRot(), m.getXRot(), m.hasRotation(), m.isOnGround());
            case ClientboundEntityPositionSyncPacket s -> new EntSync(s.id(), s.position().endPosition(), s.hasPosition(),
                    s.yRot(), s.xRot(), s.hasRotation(), s.onGround());
            case ClientboundTeleportEntityPacket t -> new EntTeleport(t.id(), t.change(), t.relatives(), t.onGround());
            case ClientboundSetEntityMotionPacket m -> new EntMotion(MediaCompat.motionId(m), MediaCompat.motion(m));
            case ClientboundRotateHeadPacket h -> new EntHead(((RotateHeadAccessor) h).duskclient$entityId(), h.getYHeadRot());
            case ClientboundSetEntityDataPacket d -> {
                Set<Object> keys = new HashSet<>();
                for (SynchedEntityData.DataValue<?> v : d.packedItems()) keys.add(v.id());
                yield new EntCover(d.id(), 0, keys);
            }
            case ClientboundSetEquipmentPacket e -> {
                Set<Object> keys = new HashSet<>();
                for (var slot : e.getSlots()) keys.add(slot.getFirst());
                yield new EntCover(e.getEntity(), 1, keys);
            }
            case ClientboundUpdateAttributesPacket a -> {
                Set<Object> keys = new HashSet<>();
                for (var v : a.getValues()) keys.add(v.attribute());
                yield new EntCover(a.getEntityId(), 2, keys);
            }
            case ClientboundUpdateMobEffectPacket e -> new EntCover(e.getEntityId(), 3, Set.of(e.getEffect()));
            case ClientboundRemoveMobEffectPacket e -> new EntUncover(e.entityId(), 3, e.effect());
            case ClientboundSetPassengersPacket s -> new EntLink(new Key("pass", s.getVehicle(), null));
            case ClientboundSetEntityLinkPacket l -> new EntLink(new Key("link", l.getSourceId(), null));
            case ClientboundRemoveEntitiesPacket r -> new EntRemove(r.entityIds().toIntArray());

            // sounds, particles, chat, titles, animations, the recorder's own inventory...
            default -> Simple.DROP;
        };
    }

    private static Tag latest(boolean level, Packet<?> p) {
        return new Latest(level, p.getClass(), null, false);
    }

    private static Tag blockTag(BlockPos pos, boolean entity) {
        return new ChunkTag(3, MediaCompat.chunk(pos), new BlockKey(entity, pos.asLong()));
    }

    private static Tag gameEvent(ClientboundGameEventPacket g) {
        ClientboundGameEventPacket.Type type = g.getEvent();
        if (type == ClientboundGameEventPacket.START_RAINING || type == ClientboundGameEventPacket.STOP_RAINING) {
            return new Latest(true, new Key("rain", null, null), null, false);
        }
        if (type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) return new Latest(true, new Key("rainLevel", null, null), null, false);
        if (type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) return new Latest(true, new Key("thunderLevel", null, null), null, false);
        if (type == ClientboundGameEventPacket.IMMEDIATE_RESPAWN) return new Latest(false, new Key("respawnScreen", null, null), null, false);
        if (type == ClientboundGameEventPacket.LIMITED_CRAFTING) return new Latest(false, new Key("limitedCrafting", null, null), null, false);
        return Simple.DROP;
    }

    private static Tag teamTag(ClientboundSetPlayerTeamPacket t) {
        String group = "team:" + t.getName();
        ClientboundSetPlayerTeamPacket.Action team = t.getTeamAction();
        if (team == ClientboundSetPlayerTeamPacket.Action.REMOVE) return new Clear(false, group);
        if (team == ClientboundSetPlayerTeamPacket.Action.ADD) return new Latest(false, new Key("team", t.getName(), "add"), group, true);
        if (t.getPlayerAction() != null) return new Latest(false, new Object(), group, false);
        return new Latest(false, new Key("team", t.getName(), "chg"), group, false);
    }

    private static Tag bossTag(ClientboundBossEventPacket b) {
        Tag[] out = {Simple.DROP};
        b.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(UUID id, net.minecraft.network.chat.Component name, float progress,
                            net.minecraft.world.BossEvent.BossBarColor color, net.minecraft.world.BossEvent.BossBarOverlay overlay,
                            boolean darken, boolean music, boolean fog) {
                out[0] = new Latest(false, new Key("boss", id, "add"), "boss:" + id, true);
            }

            @Override
            public void remove(UUID id) {
                out[0] = new Clear(false, "boss:" + id);
            }

            @Override
            public void updateProgress(UUID id, float progress) {
                out[0] = new Latest(false, new Key("boss", id, "progress"), "boss:" + id, false);
            }

            @Override
            public void updateName(UUID id, net.minecraft.network.chat.Component name) {
                out[0] = new Latest(false, new Key("boss", id, "name"), "boss:" + id, false);
            }

            @Override
            public void updateStyle(UUID id, net.minecraft.world.BossEvent.BossBarColor color, net.minecraft.world.BossEvent.BossBarOverlay overlay) {
                out[0] = new Latest(false, new Key("boss", id, "style"), "boss:" + id, false);
            }

            @Override
            public void updateProperties(UUID id, boolean darken, boolean music, boolean fog) {
                out[0] = new Latest(false, new Key("boss", id, "props"), "boss:" + id, false);
            }
        });
        return out[0];
    }

    // ---- the squashed state (writer thread) ---------------------------------------

    private record Stored(Entry entry, @Nullable String group) {}

    private static final class Chunk {
        Entry data;
        /** Light, section and block updates since the chunk arrived; block updates keyed by position. */
        final LinkedHashMap<Object, Entry> extras = new LinkedHashMap<>();
    }

    private record Cover(Entry entry, int group, Set<Object> keys) {}

    private static final class Ent {
        final EntAdd add;
        final VecDeltaCodec codec = new VecDeltaCodec();
        Vec3 pos, motion;
        float yRot, xRot, head;
        final List<Cover> covers = new ArrayList<>();

        Ent(EntAdd add) {
            this.add = add;
            pos = add.pos;
            motion = add.motion;
            yRot = add.yRot;
            xRot = add.xRot;
            head = add.head;
            codec.setBase(pos);
        }
    }

    private static final class State {
        boolean touched;
        final List<Entry> prelude = new ArrayList<>();
        @Nullable Entry loginFinished, login, respawn;
        @Nullable ResourceKey<Level> dimension;
        @Nullable ProtocolInfo<?> play;
        int selfId = Integer.MIN_VALUE;
        final LinkedHashMap<Object, Stored> session = new LinkedHashMap<>(), level = new LinkedHashMap<>();
        final LinkedHashMap<Long, Chunk> chunks = new LinkedHashMap<>();
        final LinkedHashMap<Integer, Ent> entities = new LinkedHashMap<>();
        final LinkedHashMap<Object, Entry> links = new LinkedHashMap<>();

        void resetLevel() {
            level.clear();
            chunks.clear();
            entities.clear();
            links.clear();
        }

        void resetPlay() {
            login = null;
            respawn = null;
            dimension = null;
            play = null;
            session.clear();
            resetLevel();
        }

        void squash(Entry e) {
            touched = true;
            Tag tag = e.tag;
            switch (tag) {
                case Simple s -> {
                    switch (s) {
                        case PRELUDE_START -> {
                            resetPlay();
                            prelude.clear();
                            loginFinished = e;
                            prelude.add(e);
                        }
                        case PRELUDE_RESET -> {
                            resetPlay();
                            prelude.clear();
                            if (loginFinished != null) prelude.add(loginFinished);
                        }
                        case PRELUDE -> prelude.add(e);
                        case KEEP_SESSION -> session.put(new Object(), new Stored(e, null));
                        case KEEP_LEVEL -> level.put(new Object(), new Stored(e, null));
                        case DROP -> {}
                    }
                }
                case Login l -> {
                    resetPlay();
                    login = e;
                    play = e.protocol;
                    selfId = l.selfId;
                }
                case Respawn r -> {
                    if (dimension != null && !dimension.equals(r.dimension)) resetLevel();
                    dimension = r.dimension;
                    respawn = e;
                }
                case Latest l -> {
                    Map<Object, Stored> map = l.level ? level : session;
                    if (l.reset && l.group != null) clearGroup(map, l.group);
                    map.remove(l.key);
                    map.put(l.key, new Stored(e, l.group));
                }
                case Clear c -> clearGroup(c.level ? level : session, c.group);
                case ChunkTag c -> {
                    switch (c.kind) {
                        case 0 -> {
                            Chunk ch = new Chunk();
                            ch.data = e;
                            chunks.remove(c.pos);
                            chunks.put(c.pos, ch);
                        }
                        case 1 -> chunks.remove(c.pos);
                        default -> {
                            Chunk ch = chunks.get(c.pos);
                            if (ch == null) break;
                            Object key = c.key != null ? c.key : new Object();
                            ch.extras.remove(key);
                            ch.extras.put(key, e);
                        }
                    }
                }
                case EntAdd a -> {
                    entities.remove(a.id);
                    entities.put(a.id, new Ent(a));
                }
                case EntMove m -> {
                    Ent ent = entities.get(m.id);
                    if (ent == null) break;
                    if (m.hasPos) {
                        ent.pos = m.delta.decode(ent.codec).endPosition();
                        ent.codec.setBase(ent.pos);
                    }
                    if (m.hasRot) {
                        ent.yRot = m.yRot;
                        ent.xRot = m.xRot;
                    }
                }
                case EntSync s -> {
                    Ent ent = entities.get(s.id);
                    if (ent == null) break;
                    // 26.3's sync carries no motion; what the entity had stands
                    if (s.hasPos) {
                        ent.pos = s.pos;
                        ent.codec.setBase(ent.pos);
                    }
                    if (s.hasRot) {
                        ent.yRot = s.yRot;
                        ent.xRot = s.xRot;
                    }
                }
                case EntTeleport t -> {
                    Ent ent = entities.get(t.id);
                    if (ent == null) break;
                    PositionMoveRotation now = PositionMoveRotation.calculateAbsolute(
                            new PositionMoveRotation(ent.pos, ent.motion, ent.yRot, ent.xRot), t.change, t.relatives);
                    ent.pos = now.position();
                    ent.codec.setBase(ent.pos);
                    ent.motion = now.deltaMovement();
                    ent.yRot = now.yRot();
                    ent.xRot = now.xRot();
                }
                case EntMotion m -> {
                    Ent ent = entities.get(m.id);
                    if (ent != null) ent.motion = m.motion;
                }
                case EntHead h -> {
                    Ent ent = entities.get(h.id);
                    if (ent != null) ent.head = h.head;
                }
                case EntCover c -> {
                    Ent ent = entities.get(c.id);
                    if (ent == null) break;
                    uncover(ent, c.group, c.keys::contains);
                    ent.covers.add(new Cover(e, c.group, new HashSet<>(c.keys)));
                }
                case EntUncover u -> {
                    Ent ent = entities.get(u.id);
                    if (ent != null) uncover(ent, u.group, k -> Objects.equals(k, u.key));
                }
                case EntLink l -> {
                    links.remove(l.key);
                    links.put(l.key, e);
                }
                case EntRemove r -> {
                    for (int id : r.ids) {
                        entities.remove(id);
                        links.remove(new Key("pass", id, null));
                        links.remove(new Key("link", id, null));
                    }
                }
            }
        }

        private static void clearGroup(Map<Object, Stored> map, String group) {
            map.values().removeIf(s -> group.equals(s.group));
        }

        private static void uncover(Ent ent, int group, Predicate<Object> superseded) {
            for (Iterator<Cover> it = ent.covers.iterator(); it.hasNext(); ) {
                Cover c = it.next();
                if (c.group != group) continue;
                c.keys.removeIf(superseded);
                if (c.keys.isEmpty()) it.remove();
            }
        }

        List<byte[]> serialize() {
            List<byte[]> out = new ArrayList<>();
            for (Entry e : prelude) out.add(e.bytes);
            if (login == null || play == null) return out;
            out.add(login.bytes);
            if (respawn != null) out.add(respawn.bytes);
            add(out, session.values());
            add(out, level.values());
            for (Chunk ch : chunks.values()) {
                out.add(ch.data.bytes);
                for (Entry e : ch.extras.values()) out.add(e.bytes);
            }
            for (Ent ent : entities.values()) {
                EntAdd a = ent.add;
                byte[] add = Recorder.encode(play, new ClientboundAddEntityPacket(a.id, a.uuid, ent.pos.x, ent.pos.y, ent.pos.z,
                        ent.xRot, ent.yRot, a.type, a.data, ent.motion, ent.head));
                if (add == null) continue;
                out.add(add);
                for (Cover c : ent.covers) out.add(c.entry.bytes);
            }
            for (Entry e : links.values()) out.add(e.bytes);
            Ent self = entities.get(selfId);
            if (self != null) {
                byte[] pos = Recorder.encode(play, new ClientboundPlayerPositionPacket(0,
                        new PositionMoveRotation(self.pos, Vec3.ZERO, self.yRot, self.xRot), Set.of()));
                if (pos != null) out.add(pos);
            }
            byte[] load = Recorder.encode(play, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0));
            if (load != null) out.add(load);
            return out;
        }

        private static void add(List<byte[]> out, Collection<Stored> stored) {
            for (Stored s : stored) out.add(s.entry.bytes);
        }
    }
}
