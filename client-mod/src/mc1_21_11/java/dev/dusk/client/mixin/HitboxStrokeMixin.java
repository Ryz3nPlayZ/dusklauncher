package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.Hitbox;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Repaints the hitbox strokes with the Hitboxes module's colour and width.
 * Every box in the vanilla pass (entity, integrated-server and vehicle
 * boxes) is drawn through {@code GizmoStyle.stroke(int)}, so one wrap
 * covers them all; with the module off the original colour is kept and
 * F3+B looks exactly like vanilla.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public class HitboxStrokeMixin {
    @WrapOperation(method = "showHitboxes",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/gizmos/GizmoStyle;stroke(I)Lnet/minecraft/gizmos/GizmoStyle;"))
    private GizmoStyle duskclient$hitboxStyle(int color, Operation<GizmoStyle> original) {
        if (!Hitbox.active()) return original.call(color);
        return GizmoStyle.stroke(Hitbox.boxColor(), Hitbox.lineWidth());
    }
}
