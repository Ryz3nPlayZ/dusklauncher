package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.Hitbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat Hitboxes' box pass (sootysplash/combat-hitboxes,
 * {@code HitBoxRenderMixin}), driven by the Hitboxes module. Replaces
 * vanilla's client-entity pass; the integrated server's pass stays vanilla.
 * With the module off F3+B is untouched.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public class HitboxRenderMixin {
    @Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
    private void duskclient$combatHitbox(Entity entity, float tickProgress, boolean inLocalServer, CallbackInfo ci) {
        if (inLocalServer || !Hitbox.active()) return;
        ci.cancel();
        Hitbox m = Hitbox.instance();
        if (m.hideFireworks.get() && entity instanceof FireworkRocketEntity) return;
        if (m.hideItems.get() && entity instanceof ItemEntity) return;

        Minecraft mc = Minecraft.getInstance();
        float lineWidth = m.lineWidth(mc.player != null ? mc.player.distanceTo(entity) : 0);

        Vec3 pos = entity.position();
        Vec3 lerped = entity.getPosition(tickProgress);
        Vec3 offset = lerped.subtract(pos);
        int color = entity instanceof LivingEntity le && le.hurtTime != 0 && m.hurtColor.get()
                ? m.hurtBoxColor.argb()
                : m.changeTargetColor.get() && mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == entity
                        ? m.targetColor.argb()
                        : m.boxColor.argb();
        AABB box = entity.getBoundingBox().move(offset);
        if (m.outline.get()) {
            Gizmos.cuboid(box, GizmoStyle.stroke(m.outlineColor.argb(), lineWidth * m.outlineScale.get() / 100f));
        }
        Gizmos.cuboid(box, GizmoStyle.stroke(color, lineWidth));
        Gizmos.point(lerped, color, 2.0F);

        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            float half = Math.min(vehicle.getBbWidth(), entity.getBbWidth()) / 2.0F;
            float height = 0.0625F;
            Vec3 seat = vehicle.getPassengerRidingPosition(entity).add(offset);
            Gizmos.cuboid(new AABB(seat.x - half, seat.y, seat.z - half, seat.x + half, seat.y + height, seat.z + half),
                    GizmoStyle.stroke(-256, lineWidth));
        }

        if (entity instanceof LivingEntity && m.eyeHeight.get()) {
            float slab = 0.01F;
            Gizmos.cuboid(new AABB(box.minX, box.minY + entity.getEyeHeight() - slab, box.minZ,
                            box.maxX, box.minY + entity.getEyeHeight() + slab, box.maxZ),
                    GizmoStyle.stroke(m.eyeColor.argb(), lineWidth));
        }

        if (entity instanceof EnderDragon dragon) {
            for (EnderDragonPart part : dragon.getSubEntities()) {
                Vec3 partOffset = part.getPosition(tickProgress).subtract(part.position());
                Gizmos.cuboid(part.getBoundingBox().move(partOffset),
                        GizmoStyle.stroke(ARGB.colorFromFloat(1.0F, 0.25F, 1.0F, 0.0F)));
            }
        }

        if (m.lookDir.get()) {
            Vec3 eye = lerped.add(0.0, entity.getEyeHeight(), 0.0);
            Vec3 end = eye.add(entity.getViewVector(tickProgress).scale(2.0));
            int look = m.lookColor.argb();
            if (m.lookLine.get()) Gizmos.line(eye, end, look, lineWidth);
            else Gizmos.arrow(eye, end, look, lineWidth);
        }
    }
}
