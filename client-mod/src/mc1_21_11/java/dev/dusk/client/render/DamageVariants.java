package dev.dusk.client.render;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * DamageTint's damage classifier: which column of the overlay sheet an entity
 * is currently flashing with. The last hit each entity took is remembered
 * weakly, so an entity that goes away takes its entry with it.
 */
public final class DamageVariants {
    private static final Map<LivingEntity, Integer> VARIANTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LivingEntity, Integer> HURT_TICKS = Collections.synchronizedMap(new WeakHashMap<>());

    /** A crit arrives as its own packet, a tick at most after the hit. */
    private static final int CRIT_WINDOW_TICKS = 1;

    private DamageVariants() {}

    public static void record(LivingEntity entity, DamageSource source) {
        VARIANTS.put(entity, classify(source));
        HURT_TICKS.put(entity, entity.tickCount);
    }

    public static void recordCrit(LivingEntity entity) {
        Integer hurtTick = HURT_TICKS.get(entity);
        if (hurtTick == null || entity.tickCount - hurtTick > CRIT_WINDOW_TICKS) return;
        if (entity.hurtTime > 0 && get(entity) != OverlayTint.MACE) {
            VARIANTS.put(entity, OverlayTint.CRIT);
        }
    }

    public static int get(LivingEntity entity) {
        return VARIANTS.getOrDefault(entity, OverlayTint.OTHER);
    }

    private static int classify(DamageSource source) {
        if (source.is(DamageTypeTags.IS_MACE_SMASH)) return OverlayTint.MACE;
        if (source.is(DamageTypeTags.IS_PROJECTILE)) return OverlayTint.RANGED;
        if (source.is(DamageTypeTags.IS_EXPLOSION)) return OverlayTint.EXPLOSION;

        if (source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.SONIC_BOOM)
                || source.is(DamageTypes.DRAGON_BREATH)
                || source.is(DamageTypes.WITHER)) {
            return OverlayTint.MAGIC;
        }

        if (source.is(DamageTypeTags.IS_PLAYER_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)) {
            return OverlayTint.MELEE;
        }

        return classifyByEntity(source);
    }

    /** Servers that send no damage type at all still say what hit you. */
    private static int classifyByEntity(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile) return OverlayTint.RANGED;
        if (direct instanceof LivingEntity && direct == source.getEntity()) return OverlayTint.MELEE;
        return OverlayTint.OTHER;
    }
}
