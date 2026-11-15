package dev.dusk.client.modules.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The combo count behind {@link ComboDisplay}, after Eymistaken's HUD's
 * ComboTracker (MIT, Eymistaken — see NOTICE). An attack only records a
 * pending swing, which becomes a hit when the server's "hurt" status for that
 * target arrives, so clicks into invulnerability frames never count.
 *
 * <p>1.8 servers don't say who dealt damage, so a hurt on the target within
 * the confirm window is taken as ours, and whoever hurt us is the player who
 * swung at us last, nearby.
 */
final class ComboTracker {
    private static final long CONFIRM_WINDOW_MS = 200;
    private static final long MAX_CONFIRM_WINDOW_MS = 1000;
    private static final double TARGET_LOST_DISTANCE = 20.0;
    private static final long KILL_WINDOW_MS = 1000;
    private static final long DRAIN_INTERVAL_MS = 1000;
    /** A player's swing this recent, this close, is who hurt us. */
    private static final long ATTACKER_SWING_MS = 400;
    private static final double ATTACKER_RANGE = 6.0;
    static final byte HURT_EVENT = 2, DEATH_EVENT = 3;
    private static final int NO_ENTITY = -1;

    private static int combo;
    private static int targetId = NO_ENTITY;
    private static UUID targetUuid;
    private static long lastHitTime;
    private static boolean draining;
    private static long nextDrainTime;

    private static int received;
    private static int attackerId = NO_ENTITY;
    private static long lastReceivedTime;

    private static final Map<Integer, Long> opponents = new HashMap<Integer, Long>();
    private static final Map<Integer, Long> pendingSwings = new HashMap<Integer, Long>();
    /** Other players' arm swings, by entity id. */
    private static final Map<Integer, Long> swings = new HashMap<Integer, Long>();
    private static WorldClient trackedLevel;

    private ComboTracker() {}

    static int combo() { return combo; }

    static int received() { return received; }

    static void onAttack(Entity target, ComboDisplay config) {
        if (config.onlyPlayers() && !(target instanceof EntityPlayer)) return;
        long now = now();
        if (!config.serverDetection()) {
            countHit(target, now, config);
            return;
        }
        pendingSwings.put(target.getEntityId(), now);
    }

    static void onSwing(int entityId) {
        swings.put(entityId, now());
    }

    static void onEntityStatus(Entity entity, byte status, ComboDisplay config) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || entity == null) return;
        long now = now();
        if (status == HURT_EVENT) {
            if (entity == player) onLocalPlayerHurt(player, now, config);
            else if (config.serverDetection()) confirmHit(entity, player, now, config);
        } else if (status == DEATH_EVENT) {
            opponents.remove(entity.getEntityId());
            if (entity.getEntityId() == attackerId) endReceived();
            boolean ourKill = combo > 0 && entity.getUniqueID().equals(targetUuid) && now - lastHitTime <= KILL_WINDOW_MS;
            if (ourKill && !config.continueOnSwitch()) endCombo();
        }
    }

    static void onTick(Minecraft mc, ComboDisplay config) {
        EntityPlayerSP player = mc.thePlayer;
        WorldClient level = mc.theWorld;
        if (player == null || level == null) {
            forgetSession();
            return;
        }
        if (level != trackedLevel) {
            forgetSession();
            trackedLevel = level;
        }
        long now = now();
        long timeout = config.timeoutMs();
        expire(pendingSwings, now, MAX_CONFIRM_WINDOW_MS);
        expire(swings, now, ATTACKER_SWING_MS);
        expire(opponents, now, timeout);
        if (received > 0 && now - lastReceivedTime > timeout) endReceived();
        if (combo == 0) return;
        Entity target = level.getEntityByID(targetId);
        boolean targetFled = target != null && target.getUniqueID().equals(targetUuid)
                && player.getDistanceToEntity(target) > TARGET_LOST_DISTANCE;
        if (targetFled || now - lastHitTime > timeout) loseCombo(now, config);
        if (draining && now >= nextDrainTime) {
            combo--;
            nextDrainTime = now + DRAIN_INTERVAL_MS;
            if (combo <= 0) endCombo();
        }
    }

    private static void expire(Map<Integer, Long> times, long now, long maxAge) {
        for (Iterator<Long> it = times.values().iterator(); it.hasNext(); ) {
            if (now - it.next() > maxAge) it.remove();
        }
    }

    static void forgetSession() {
        endCombo();
        endReceived();
        opponents.clear();
        pendingSwings.clear();
        swings.clear();
        trackedLevel = null;
    }

    private static void confirmHit(Entity target, EntityPlayerSP player, long now, ComboDisplay config) {
        Long swing = pendingSwings.get(target.getEntityId());
        if (swing == null || now - swing > confirmWindow(player)) return;
        pendingSwings.remove(target.getEntityId());
        countHit(target, now, config);
    }

    private static void onLocalPlayerHurt(EntityPlayerSP player, long now, ComboDisplay config) {
        int attacker = guessAttacker(player, now);
        countReceived(attacker, player.worldObj, now, config);
        if (combo == 0) return;
        if (config.breakOnAnyDamage()) {
            loseCombo(now, config);
            return;
        }
        Long lastHitOnAttacker = opponents.get(attacker);
        if (lastHitOnAttacker != null && now - lastHitOnAttacker <= config.timeoutMs()) loseCombo(now, config);
    }

    /** The nearby player whose swing came last, or {@link #NO_ENTITY}. */
    private static int guessAttacker(EntityPlayerSP player, long now) {
        int best = NO_ENTITY;
        long bestTime = Long.MIN_VALUE;
        for (Map.Entry<Integer, Long> e : swings.entrySet()) {
            if (now - e.getValue() > ATTACKER_SWING_MS || e.getValue() <= bestTime) continue;
            Entity entity = player.worldObj.getEntityByID(e.getKey());
            if (entity == null || entity == player || player.getDistanceToEntity(entity) > ATTACKER_RANGE) continue;
            best = e.getKey();
            bestTime = e.getValue();
        }
        return best;
    }

    private static void countReceived(int attacker, net.minecraft.world.World level, long now, ComboDisplay config) {
        Entity entity = attacker == NO_ENTITY ? null : level.getEntityByID(attacker);
        boolean counts = entity instanceof EntityPlayer || !config.onlyPlayers() && entity instanceof EntityLivingBase;
        if (!counts) return;
        boolean sameStreak = attacker == attackerId && now - lastReceivedTime <= config.timeoutMs();
        received = sameStreak ? received + 1 : 1;
        attackerId = attacker;
        lastReceivedTime = now;
    }

    private static void countHit(Entity target, long now, ComboDisplay config) {
        if (combo > 0) {
            boolean switched = !target.getUniqueID().equals(targetUuid);
            boolean expired = !config.decay() && now - lastHitTime > config.timeoutMs();
            if (expired || switched && !config.continueOnSwitch()) endCombo();
        }
        targetId = target.getEntityId();
        targetUuid = target.getUniqueID();
        lastHitTime = now;
        draining = false;
        opponents.put(target.getEntityId(), now);
        if (target.getEntityId() == attackerId) endReceived();
        combo++;
    }

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

    private static long confirmWindow(EntityPlayerSP player) {
        NetHandlerPlayClient connection = Minecraft.getMinecraft().getNetHandler();
        NetworkPlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUniqueID());
        int latency = info == null ? 0 : Math.max(0, info.getResponseTime());
        return Math.min(MAX_CONFIRM_WINDOW_MS, CONFIRM_WINDOW_MS + latency);
    }

    private static long now() { return System.nanoTime() / 1000000L; }
}
