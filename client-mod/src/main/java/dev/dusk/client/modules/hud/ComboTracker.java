package dev.dusk.client.modules.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The combo count behind {@link ComboDisplay}: a port of Eymistaken's HUD's
 * ComboTracker (MIT, Eymistaken — see NOTICE). Counts what the server
 * reports rather than what was clicked: an attack only records a pending
 * swing, which turns into a hit when the server's damage event for that
 * target arrives, so clicks into invulnerability frames or a raised shield
 * never count. Also counts the combo someone is landing on you.
 *
 * <p>Left out from the original: the heatmap, hit strip, best-combo record,
 * and the swing-based attacker guess for servers that don't name attackers.
 */
final class ComboTracker {
    /** How long our swing may wait for the server's damage event, on top of latency. */
    private static final long CONFIRM_WINDOW_MS = 200;
    private static final long MAX_CONFIRM_WINDOW_MS = 1000;
    private static final double TARGET_LOST_DISTANCE = 20.0;
    /** A target dying this soon after our last hit is our kill. */
    private static final long KILL_WINDOW_MS = 1000;
    private static final long DRAIN_INTERVAL_MS = 1000;
    private static final float FULL_STRENGTH = 0.9f;
    private static final byte DEATH_EVENT = 3;
    private static final int NO_ENTITY = -1;

    private record Swing(long time, boolean fullStrength) {}

    private static int combo;
    private static int targetId = NO_ENTITY;
    private static UUID targetUuid;
    private static long lastHitTime;
    private static boolean draining;
    private static long nextDrainTime;

    private static int received;
    private static int attackerId = NO_ENTITY;
    private static long lastReceivedTime;

    /** Entities we landed a counted hit on, by id, with the time of the latest one. */
    private static final Map<Integer, Long> opponents = new HashMap<>();
    /** Swings we sent that the server has not answered yet, by target id. */
    private static final Map<Integer, Swing> pendingSwings = new HashMap<>();
    /**
     * Set once a damage event names its cause. From then on, damage without a
     * cause is the environment's rather than a hit whose attacker was left out.
     */
    private static boolean serverNamesAttackers;
    private static ClientLevel trackedLevel;

    private ComboTracker() {}

    static int combo() {
        return combo;
    }

    /** Hits taken in a row from one attacker who has not been hit back; 0 when there is none. */
    static int received() {
        return received;
    }

    /** The local player attacked {@code target}; the packet has not gone out yet. */
    static void onAttack(Entity target, Player attacker, ComboDisplay config) {
        if (config.onlyPlayers() && !(target instanceof Player)) return;
        long now = now();
        boolean fullStrength = !config.modernCombat() || attacker.getAttackStrengthScale(0.5f) >= FULL_STRENGTH;
        if (!config.serverDetection()) {
            if (fullStrength) countHit(target, now, config);
            return;
        }
        // Spam-clicking after a full swing must not turn the hit the server is
        // about to confirm into a weak one.
        pendingSwings.merge(target.getId(), new Swing(now, fullStrength),
                (earlier, later) -> earlier.fullStrength() && !later.fullStrength() ? earlier : later);
    }

    static void onDamageEvent(ClientboundDamageEventPacket packet, ComboDisplay config) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) return;
        long now = now();
        if (packet.sourceCauseId() != NO_ENTITY) serverNamesAttackers = true;
        if (packet.entityId() == player.getId()) {
            onLocalPlayerHurt(packet, player, level, now, config);
        } else if (config.serverDetection()) {
            confirmHit(packet, player, level, now, config);
        }
    }

    static void onEntityEvent(ClientboundEntityEventPacket packet, ComboDisplay config) {
        if (packet.getEventId() != DEATH_EVENT) return;
        ClientLevel level = Minecraft.getInstance().level;
        Entity entity = level == null ? null : packet.getEntity(level);
        if (entity == null) return;
        opponents.remove(entity.getId());
        if (entity.getId() == attackerId) endReceived();
        boolean ourKill = combo > 0 && entity.getUUID().equals(targetUuid) && now() - lastHitTime <= KILL_WINDOW_MS;
        // A kill finishes the combo, unless it is meant to carry over to the next target.
        if (ourKill && !config.continueOnSwitch()) endCombo();
    }

    static void onTick(Minecraft client, ComboDisplay config) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            forgetSession();
            return;
        }
        // Entity ids only mean something within one level of one connection.
        if (level != trackedLevel) {
            forgetSession();
            trackedLevel = level;
        }
        long now = now();
        long timeout = config.timeoutMs();
        pendingSwings.values().removeIf(swing -> now - swing.time() > MAX_CONFIRM_WINDOW_MS);
        opponents.values().removeIf(time -> now - time > timeout);
        if (received > 0 && now - lastReceivedTime > timeout) endReceived();
        if (combo == 0) return;

        Entity target = level.getEntity(targetId);
        boolean targetFled = target != null && target.getUUID().equals(targetUuid)
                && player.distanceTo(target) > TARGET_LOST_DISTANCE;
        if (targetFled || now - lastHitTime > timeout) loseCombo(now, config);

        if (draining && now >= nextDrainTime) {
            combo--;
            nextDrainTime = now + DRAIN_INTERVAL_MS;
            if (combo <= 0) endCombo();
        }
    }

    static void forgetSession() {
        endCombo();
        endReceived();
        opponents.clear();
        pendingSwings.clear();
        serverNamesAttackers = false;
        trackedLevel = null;
    }

    private static void confirmHit(ClientboundDamageEventPacket packet, LocalPlayer player, ClientLevel level,
                                   long now, ComboDisplay config) {
        Swing swing = pendingSwings.get(packet.entityId());
        if (swing == null || now - swing.time() > confirmWindow(player)) return;
        // Our melee names us as both cause and direct source; our arrow, or a
        // teammate's hit landing in the same moment, does not. Without a cause
        // it is ours only on a server that never names one.
        int cause = packet.sourceCauseId();
        boolean ours = cause == player.getId() && packet.sourceDirectId() == player.getId();
        if (!ours && (cause != NO_ENTITY || serverNamesAttackers)) return;
        pendingSwings.remove(packet.entityId());
        Entity target = level.getEntity(packet.entityId());
        if (swing.fullStrength() && target != null) countHit(target, now, config);
    }

    private static void onLocalPlayerHurt(ClientboundDamageEventPacket packet, LocalPlayer player, ClientLevel level,
                                          long now, ComboDisplay config) {
        // Thorns is the echo of our own hit, not something done to us.
        if (packet.sourceType().is(DamageTypes.THORNS)) return;
        int cause = packet.sourceCauseId();
        int attacker = cause == player.getId() ? NO_ENTITY : cause;
        countReceived(attacker, level, now, config);

        if (combo == 0) return;
        if (config.breakOnAnyDamage()) {
            loseCombo(now, config);
            return;
        }
        Long lastHitOnAttacker = opponents.get(attacker);
        if (lastHitOnAttacker != null && now - lastHitOnAttacker <= config.timeoutMs()) loseCombo(now, config);
    }

    private static void countReceived(int attacker, ClientLevel level, long now, ComboDisplay config) {
        Entity entity = attacker == NO_ENTITY ? null : level.getEntity(attacker);
        boolean counts = entity instanceof Player || (!config.onlyPlayers() && entity instanceof LivingEntity);
        if (!counts) return;
        boolean sameStreak = attacker == attackerId && now - lastReceivedTime <= config.timeoutMs();
        received = sameStreak ? received + 1 : 1;
        attackerId = attacker;
        lastReceivedTime = now;
    }

    private static void countHit(Entity target, long now, ComboDisplay config) {
        if (combo > 0) {
            boolean switched = !target.getUUID().equals(targetUuid);
            // Ran out of time since the last tick looked; with Decay the drain covers that.
            boolean expired = !config.decay() && now - lastHitTime > config.timeoutMs();
            if (expired || (switched && !config.continueOnSwitch())) endCombo();
        }
        targetId = target.getId();
        targetUuid = target.getUUID();
        lastHitTime = now;
        draining = false;
        opponents.put(target.getId(), now);
        // Hitting back ends the combo that attacker was landing on us.
        if (target.getId() == attackerId) endReceived();
        combo++;
    }

    /** The combo broke or ran out of time: it ends, or with Decay starts draining. */
    private static void loseCombo(long now, ComboDisplay config) {
        if (!config.decay()) {
            endCombo();
        } else if (!draining) {
            draining = true;
            nextDrainTime = now + DRAIN_INTERVAL_MS;
        }
    }

    private static void endCombo() {
        combo = 0;
        targetId = NO_ENTITY;
        targetUuid = null;
        draining = false;
    }

    private static void endReceived() {
        received = 0;
        attackerId = NO_ENTITY;
    }

    /** Our swing and the server's answer are a round trip apart. */
    private static long confirmWindow(LocalPlayer player) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
        int latency = info == null ? 0 : Math.max(0, info.getLatency());
        return Math.min(MAX_CONFIRM_WINDOW_MS, CONFIRM_WINDOW_MS + latency);
    }

    /** Monotonic, unlike the wall clock, so a clock change cannot end or stall a combo. */
    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
