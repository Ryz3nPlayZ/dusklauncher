package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.misc.BoatMap;
import dev.dusk.client.modules.render.ItemScale;
import dev.dusk.client.modules.render.LowShield;
import dev.dusk.client.modules.render.RiptideShieldFix;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import org.spongepowered.asm.mixin.injection.At;

/**
 * The BactroMod features that live in the first-person hand renderer: item
 * scale, shield height, the riptide shield fix and the boat map. The method
 * holding the item render was renamed renderArmWithItem -> submitArmWithItem
 * in 26.2, so both names are listed and only one has to match.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandMixin {
    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float offHandHeight;

    /**
     * Item Scale and Shield Height. Both are pose changes around the item
     * itself, so the arm keeps its own position and only what you are holding
     * moves.
     */
    @WrapOperation(
            method = {"renderArmWithItem", "submitArmWithItem"},
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"),
            require = 1)
    private void duskclient$transformHeldItem(ItemInHandRenderer renderer, LivingEntity entity, ItemStack stack,
                                              ItemDisplayContext context, PoseStack poseStack,
                                              SubmitNodeCollector collector, int light, Operation<Void> original) {
        float scale = ItemScale.scale();
        float drop = stack.is(Items.SHIELD) ? LowShield.offsetY() : 0;
        if (scale == 1f && drop == 0) {
            original.call(renderer, entity, stack, context, poseStack, collector, light);
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0, drop, 0);
        poseStack.scale(scale, scale, scale);
        original.call(renderer, entity, stack, context, poseStack, collector, light);
        poseStack.popPose();
    }

    /**
     * Riptide Shield Fix. A spinning player drags the whole hand through the
     * riptide transform, which throws a raised shield off screen. Answering
     * "not spinning" for the shield alone leaves the trident spinning.
     */
    @WrapOperation(
            method = {"renderArmWithItem", "submitArmWithItem"},
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/player/AbstractClientPlayer;isAutoSpinAttack()Z"),
            require = 1)
    private boolean duskclient$riptideShield(AbstractClientPlayer player, Operation<Boolean> original,
                                             @Local(argsOnly = true) ItemStack stack) {
        if (RiptideShieldFix.active() && stack.is(Items.SHIELD)) return false;
        return original.call(player);
    }

    /**
     * Boat Map, main hand. Rowing marks the hands busy, which drops whatever
     * you hold out of sight; a map is the one thing you want to keep reading,
     * so it follows the ordinary raise/lower curve instead.
     */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F", ordinal = 0),
            require = 1)
    private float duskclient$boatMapMainHand(float value, float min, float max, Operation<Float> original,
                                             @Local LocalPlayer player) {
        if (!duskclient$mapInBoat(player, this.mainHandItem)) return original.call(value, min, max);
        float swap = player.getItemSwapScale(1f);
        float target = this.mainHandItem == player.getMainHandItem() ? swap * swap * swap : 0;
        return this.mainHandHeight + Mth.clamp(target - this.mainHandHeight, -0.4f, 0.4f);
    }

    /** Boat Map, off hand. */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F", ordinal = 1),
            require = 1)
    private float duskclient$boatMapOffHand(float value, float min, float max, Operation<Float> original,
                                            @Local LocalPlayer player) {
        if (!duskclient$mapInBoat(player, this.offHandItem)) return original.call(value, min, max);
        float target = this.offHandItem == player.getOffhandItem() ? 1 : 0;
        return this.offHandHeight + Mth.clamp(target - this.offHandHeight, -0.4f, 0.4f);
    }

    @Unique
    private static boolean duskclient$mapInBoat(LocalPlayer player, ItemStack stack) {
        return BoatMap.active() && stack.is(Items.FILLED_MAP) && player.getVehicle() instanceof AbstractBoat;
    }
}
