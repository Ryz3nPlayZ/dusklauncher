package dev.dusk.client.mixin.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.compat.LegacyBlock;
import dev.dusk.client.modules.render.ItemScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Legacy Combat: draws the sword in the 1.8 block pose instead of vanilla's
 * held item. 26.3 draws the hands from extracted state, so the item comes
 * from there, already resolved for this hand.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class LegacyFirstPersonMixin {
    @Shadow
    private void applyItemArmTransform(PoseStack pose, HumanoidArm arm, float inverseArmHeight) {
        throw new AssertionError();
    }

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void duskclient$blockingSword(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands,
                                          float partialTick, float pitch, InteractionHand hand, float attack,
                                          ItemStack stack, float inverseArmHeight, PoseStack pose,
                                          SubmitNodeCollector collector, int light, CallbackInfo ci) {
        LocalPlayer local = Minecraft.getInstance().player;
        if (hand != InteractionHand.MAIN_HAND || stack.isEmpty() || hands.isScoping || local == null
                || !LegacyBlock.blocking()) return;
        HumanoidArm arm = local.getMainArm();
        pose.pushPose();
        applyItemArmTransform(pose, arm, inverseArmHeight);
        LegacyBlock.applyPose(pose, arm, attack);
        float scale = ItemScale.scale();
        pose.scale(scale, scale, scale);
        hands.mainHandRenderState.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
        ci.cancel();
    }
}
