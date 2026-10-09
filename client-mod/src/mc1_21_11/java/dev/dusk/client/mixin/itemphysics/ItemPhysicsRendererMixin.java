package dev.dusk.client.mixin.itemphysics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.render.ItemPhysics;
import dev.dusk.client.render.ItemPhysicsState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Physics. Vanilla's submit lifts the item off the ground and bobs it,
 * spins it, then draws the stack; for an item resting on the ground each of
 * those three calls is replaced: no lift or bob, a fixed turn, and a flat
 * item tipped onto its back. The vanilla body stays, so other mods' hooks in
 * it keep working.
 */
@Mixin(ItemEntityRenderer.class)
public class ItemPhysicsRendererMixin {
    /** Vanilla's FLAT_ITEM_DEPTH_THRESHOLD: thinner than this is drawn as a flat sprite. */
    @Unique private static final double FLAT_DEPTH = 0.0625;
    /** Just above the ground, so a flat item doesn't flicker into it. */
    @Unique private static final float CLEARANCE = 0.002f;

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL"))
    private void duskclient$resting(ItemEntity entity, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
        ((ItemPhysicsState) state).duskclient$setResting(
                ItemPhysics.on() && entity.onGround() && !entity.isInWater() && !entity.isInLava());
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void duskclient$lift(PoseStack pose, float x, float y, float z, Operation<Void> original,
                                 @Local(argsOnly = true) ItemEntityRenderState state) {
        if (((ItemPhysicsState) state).duskclient$resting()) {
            AABB box = state.item.getModelBoundingBox();
            // a flat item is tipped onto its back below, so its depth becomes its height
            y = (float) (box.getZsize() <= FLAT_DEPTH ? -box.minZ : -box.minY) + CLEARANCE;
        }
        original.call(pose, x, y, z);
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/ItemEntity;getSpin(FF)F"))
    private float duskclient$turn(float age, float bobOffset, Operation<Float> original,
                                  @Local(argsOnly = true) ItemEntityRenderState state) {
        // bobOffset is random per item, so a pile doesn't line up
        return ((ItemPhysicsState) state).duskclient$resting() ? bobOffset : original.call(age, bobOffset);
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
    private void duskclient$layFlat(PoseStack pose, SubmitNodeCollector collector, int light, ItemClusterRenderState cluster,
                                    RandomSource random, AABB box, Operation<Void> original) {
        if (cluster instanceof ItemPhysicsState s && s.duskclient$resting() && box.getZsize() <= FLAT_DEPTH) {
            // face up: the model's depth axis now points at the sky, so extra copies stack into a pile
            // on the pose itself: PoseStack's quaternion method changed name in 26.3
            Quaternionf faceUp = new Quaternionf().rotationX(-Mth.HALF_PI);
            PoseStack.Pose last = pose.last();
            last.pose().rotate(faceUp);
            last.normal().rotate(faceUp);
        }
        original.call(pose, collector, light, cluster, random, box);
    }
}
