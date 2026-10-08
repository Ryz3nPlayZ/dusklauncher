package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.modules.render.ItemScale;
import dev.dusk.client.modules.render.LowShield;
import dev.dusk.client.modules.render.RiptideShieldFix;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The BactroMod features that live in the first-person hand renderer: item
 * scale, shield height and the riptide shield fix. 26.3 split the hands into
 * a renderer drawing from extracted state and the ticking part, where the
 * boat map is ({@link FirstPersonHandsTickMixin}).
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class ItemInHandMixin {
    /**
     * Item Scale and Shield Height. Both are pose changes around the item
     * itself, so the arm keeps its own position and only what you are holding
     * moves.
     */
    @WrapOperation(
            method = "submitArmWithItem",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
            require = 1)
    private void duskclient$transformHeldItem(ItemStackRenderState item, PoseStack poseStack, SubmitNodeCollector collector,
                                              int light, int overlay, int outlineColor, Operation<Void> original,
                                              @Local(argsOnly = true) ItemStack stack) {
        float scale = ItemScale.scale();
        float drop = stack.is(Items.SHIELD) ? LowShield.offsetY() : 0;
        if (scale == 1f && drop == 0) {
            original.call(item, poseStack, collector, light, overlay, outlineColor);
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0, drop, 0);
        poseStack.scale(scale, scale, scale);
        original.call(item, poseStack, collector, light, overlay, outlineColor);
        poseStack.popPose();
    }

    /**
     * Riptide Shield Fix. A spinning player drags the whole hand through the
     * riptide transform, which throws a raised shield off screen. Answering
     * "not spinning" for the shield alone leaves the trident spinning.
     */
    @WrapOperation(
            method = "submitArmWithItem",
            at = @At(value = "FIELD",
                     target = "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;isAutoSpinAttack:Z"),
            require = 1)
    private boolean duskclient$riptideShield(AvatarRenderState state, Operation<Boolean> original,
                                             @Local(argsOnly = true) ItemStack stack) {
        if (RiptideShieldFix.active() && stack.is(Items.SHIELD)) return false;
        return original.call(state);
    }
}
