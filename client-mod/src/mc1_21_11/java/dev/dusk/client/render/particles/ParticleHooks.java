package dev.dusk.client.render.particles;

import dev.dusk.client.modules.render.Particles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Glue between the Particles module (keyed by registry path) and the particle
 * mixins. Mirrors OverflowParticles' PerParticleConfigManager, ParticleSpawner
 * and OverflowParticlesEventHandler.
 */
public final class ParticleHooks {
    private static final Map<ParticleType<?>, String> KEYS = new IdentityHashMap<>();

    /** Set just before vanilla spawns an already-multiplied particle, so the spawner skips it once. */
    public static boolean multiplied;

    private static Player lastAttacker;
    private static int targetId = -1;

    private ParticleHooks() {}

    private static String key(ParticleType<?> type) {
        return KEYS.computeIfAbsent(type, t -> {
            var id = BuiltInRegistries.PARTICLE_TYPE.getKey(t);
            return id != null && id.getNamespace().equals("minecraft") ? id.getPath() : "";
        });
    }

    // Both check active() first: the quad mixins call these five or six times
    // per particle per frame, and with the module off that must cost nothing.
    public static Particles.Entry of(ParticleType<?> type) {
        return type == null || !Particles.active() ? null : Particles.entry(key(type));
    }

    public static Particles.Entry of(Particle particle) {
        return Particles.active() && particle instanceof ParticleTypeHolder h ? of(h.duskclient$type()) : null;
    }

    public static void tag(Particle particle, ParticleType<?> type) {
        if (particle instanceof ParticleTypeHolder h) h.duskclient$setType(type);
    }

    /** ParticleSpawner.spawn: ceil(multiplier) copies jittered by up to half a block. */
    public static void spawn(ParticleOptions options, Particles.Entry entry, Level level, boolean ignoreRange,
                             double x, double y, double z, double xo, double yo, double zo) {
        int copies = (int) Math.ceil(entry.multiplierValue());
        for (int i = 0; i < copies; i++) {
            multiplied = true;
            level.addParticle(options, ignoreRange, true,
                    x - 0.5 + Math.random(), y - 0.5 + Math.random(), z - 0.5 + Math.random(), xo, yo, zo);
        }
    }

    /** Scales a vanilla particle-count constant by the entry's multiplier. */
    public static int scaleCount(Particles.Entry entry, int constant) {
        if (entry == null || entry.multiplierValue() == 1f) return constant;
        return (int) (constant * entry.multiplierValue());
    }

    /** Mixin_ParticleFading.applyFade. */
    public static float fadedAlpha(Particles.Entry entry, int age, int lifetime, float alpha) {
        if (entry == null) return alpha;
        boolean fade = entry.fading();
        float fadeStart = entry.fadeStartFraction();
        if (!fade) return alpha;
        if (!(fadeStart >= 0f) || fadeStart >= 1f) fadeStart = 0f;
        if (lifetime <= 0 || age < 0) return alpha;
        float life = (float) age / lifetime;
        if (life <= fadeStart) return alpha;
        float t = Math.max(0f, Math.min(1f, (life - fadeStart) / Math.max(1e-6f, 1f - fadeStart)));
        float faded = alpha * (1f - t);
        return Float.isNaN(faded) || faded < 0f ? alpha : faded;
    }

    /** Whether running/falling block particles from this entity should be hidden. */
    public static boolean hideRunning(Entity entity) {
        Particles.Entry blocks = Particles.active() ? Particles.blocks() : null;
        if (blocks == null) return false;
        if (!blocks.enabled.get()) return true;
        if (!blocks.hideRunning.get()) return false;
        return blocks.hideMode.is("ALL") || entity == null || !entity.isInvisible();
    }

    public static void onAttack(Player player, Entity target) {
        Particles p = Particles.instance();
        if (!Particles.active() || !target.level().isClientSide()) return;
        if (p.checkInvulnerable.get()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && player.getId() == mc.player.getId()) {
                lastAttacker = player;
                targetId = target.getId();
            }
        } else {
            doSharpness(player, target);
            doCritical(player, target);
        }
    }

    /** The server confirmed the hit landed (entity event or damage event for the target). */
    public static void onHitConfirmed(Entity target) {
        if (!Particles.active() || !Particles.instance().checkInvulnerable.get()) return;
        if (target == null || lastAttacker == null || targetId != target.getId()) return;
        doCritical(lastAttacker, target);
        doSharpness(lastAttacker, target);
        lastAttacker = null;
        targetId = -1;
    }

    private static void doCritical(Player attacker, Entity target) {
        if (!Particles.instance().alwaysCritical.get()) return;
        boolean critical = attacker.fallDistance > 0.0F
                && !attacker.onGround()
                && !attacker.onClimbable()
                && !attacker.isInWater()
                && !attacker.hasEffect(MobEffects.BLINDNESS)
                && attacker.getVehicle() == null
                && target instanceof LivingEntity;
        if (!critical) Minecraft.getInstance().particleEngine.createTrackingEmitter(target, ParticleTypes.CRIT);
    }

    private static void doSharpness(Player attacker, Entity target) {
        if (!Particles.instance().alwaysSharp.get()) return;
        if (target instanceof LivingEntity) {
            if (attacker.getMainHandItem().isEnchanted()) return;
            Minecraft.getInstance().particleEngine.createTrackingEmitter(target, ParticleTypes.ENCHANTED_HIT);
        }
    }
}
