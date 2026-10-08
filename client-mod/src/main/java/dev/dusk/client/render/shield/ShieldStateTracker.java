package dev.dusk.client.render.shield;

import dev.dusk.client.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Who has a raised or disabled shield. A port of WalksyLib's
 * WalksyLibShieldStateManager (MIT, Walksy — see NOTICE), which Shield
 * Statuses reads its colours from. The server only tells a client about its
 * own shield cooldown, so other players' disables are inferred: entity event
 * 30, or a shield-break sound matched to a player you just hit with an axe.
 */
public final class ShieldStateTracker {
    private static final long ATTACK_ENTRY_TTL_MS = 1000;
    private static final byte SHIELD_DISABLE_STATUS = 30;
    private static final int DISABLE_TICKS = 100;
    private static final ItemStack SHIELD = new ItemStack(Items.SHIELD);

    private static final Map<Player, Integer> shieldUseTicks = new HashMap<>();
    private static final Map<Player, AttackEntry> attackedPlayerEntries = new HashMap<>();
    private static final Map<UUID, Integer> cooldowns = new HashMap<>();
    private static int localShieldCooldownTicks;
    private static int currentTick;
    private static PendingBreak pendingBreak;
    private static Level lastLevel;

    private ShieldStateTracker() {}

    private record AttackEntry(Vec3 attackPos, Vec3 targetPos, long time, boolean wasBlocking) {}

    private record PendingBreak(Player target, int createdTick) {}

    public static void reset() {
        shieldUseTicks.clear();
        attackedPlayerEntries.clear();
        cooldowns.clear();
        localShieldCooldownTicks = 0;
        pendingBreak = null;
    }

    public static void disable(Player player) {
        if (player == null) return;
        cooldowns.put(player.getUUID(), DISABLE_TICKS);
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            // the maps hold Player objects, which a new level replaces
            reset();
            lastLevel = client.level;
        }
        if (client.level == null) return;
        currentTick++;
        for (Player player : client.level.players()) {
            if (isHoldingUsableShield(player) && player.isUsingItem()) {
                shieldUseTicks.merge(player, 1, Integer::sum);
            } else {
                shieldUseTicks.put(player, 0);
            }
        }
        shieldUseTicks.keySet().removeIf(Player::isRemoved);
        if (client.player != null && Compat.isOnCooldown(client.player, SHIELD)) {
            localShieldCooldownTicks++;
        } else {
            localShieldCooldownTicks = 0;
        }
        if (pendingBreak != null && currentTick - pendingBreak.createdTick() >= 1) {
            if (client.player == null || !Compat.isOnCooldown(client.player, SHIELD)) {
                disable(pendingBreak.target());
            }
            pendingBreak = null;
        }
        attackedPlayerEntries.entrySet().removeIf(e -> now - e.getValue().time() > ATTACK_ENTRY_TTL_MS);
        Iterator<Map.Entry<UUID, Integer>> it = cooldowns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0) it.remove();
            else e.setValue(left);
        }
    }

    /** A shield.break sound at x/y/z: work out whose shield it was. */
    public static void handleSoundPacket(double x, double y, double z) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        LocalPlayer local = client.player;
        long now = System.currentTimeMillis();
        if (localShieldCooldownTicks > 2) {
            disable(nearestPlayerToSound(x, y, z, local));
            return;
        }
        double matchRadiusSq = 36.0;
        Player best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (Map.Entry<Player, AttackEntry> e : attackedPlayerEntries.entrySet()) {
            Player candidate = e.getKey();
            AttackEntry ae = e.getValue();
            if (candidate == null || candidate == local) continue;
            if (now - ae.time() > ATTACK_ENTRY_TTL_MS) continue;
            if (!ae.wasBlocking()) continue;
            double currentDistSq = candidate.distanceToSqr(x, y, z);
            double storedDistSq = ae.targetPos().distanceToSqr(x, y, z);
            double effectiveDistSq = Math.min(currentDistSq, storedDistSq);
            if (effectiveDistSq > matchRadiusSq) continue;
            if (effectiveDistSq < bestDistSq) {
                bestDistSq = effectiveDistSq;
                best = candidate;
            }
        }
        if (best == null) return;
        attackedPlayerEntries.remove(best);
        if (bestDistSq < local.distanceToSqr(x, y, z)) {
            disable(best);
        } else {
            // closer to us: it is ours unless our own cooldown fails to show up next tick
            pendingBreak = new PendingBreak(best, currentTick);
        }
    }

    private static Player nearestPlayerToSound(double x, double y, double z, LocalPlayer local) {
        Player nearest = null;
        double nearestDistSq = Double.POSITIVE_INFINITY;
        for (Player player : Minecraft.getInstance().level.players()) {
            if (player == local) continue;
            double distSq = player.distanceToSqr(x, y, z);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    public static void handleEntityStatus(Player player, byte status) {
        if (status == SHIELD_DISABLE_STATUS && player != Minecraft.getInstance().player) {
            disable(player);
        }
    }

    public static void handlePlayerAttack(Player target) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        boolean estBlocking = shieldUseTicks.getOrDefault(target, 0) >= 3;
        if (disablesShield(client.player)) {
            attackedPlayerEntries.put(target,
                    new AttackEntry(client.player.position(), target.position(), System.currentTimeMillis(), estBlocking));
        }
    }

    public static boolean isCoolingDown(Player player) {
        Minecraft client = Minecraft.getInstance();
        if (player == client.player) return Compat.isOnCooldown(player, SHIELD);
        return cooldowns.getOrDefault(player.getUUID(), 0) > 0;
    }

    public static float getCooldownProgress(Player player) {
        if (player == null) return 0f;
        Minecraft client = Minecraft.getInstance();
        if (player == client.player) return Compat.cooldownPercent(player, SHIELD, 0);
        int remaining = cooldowns.getOrDefault(player.getUUID(), 0);
        if (remaining <= 0) return 0f;
        return Math.max(0f, Math.min(1f, remaining / (float) DISABLE_TICKS));
    }

    public static boolean isUsingShield(Player player) {
        return shieldUseTicks.getOrDefault(player, 0) >= 5;
    }

    public static boolean isHoldingUsableShield(Player entity) {
        return (entity.getMainHandItem().is(Items.SHIELD) || entity.getOffhandItem().is(Items.SHIELD))
                && !isHoldingAnimationItemMainHand(entity);
    }

    private static boolean isHoldingAnimationItemMainHand(Player entity) {
        return entity.getMainHandItem().getUseDuration(entity) != 0 && !entity.getMainHandItem().is(Items.SHIELD);
    }

    private static boolean disablesShield(Player player) {
        return player.getWeaponItem().is(ItemTags.AXES);
    }
}
