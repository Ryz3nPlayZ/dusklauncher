package dev.dusk.client.mixin.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.compat.LegacyBlock;
import dev.dusk.client.modules.render.ItemScale;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Legacy Combat: draws the sword in the 1.8 block pose instead of vanilla's
 * held item. 26.2 renamed the method; 26.3 has its own copy of this mixin.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class LegacyFirstPersonMixin {
    @Shadow
    private void applyItemArmTransform(PoseStack pose, HumanoidArm arm, float inverseArmHeight) {
        throw new AssertionError();
    }

    @Shadow
    public abstract void renderItem(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                    PoseStack pose, SubmitNodeCollector collector, int light);

    @Inject(method = {"renderArmWithItem", "submitArmWithItem"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void duskclient$blockingSword(AbstractClientPlayer player, float partialTick, float pitch,
                                          InteractionHand hand, float attack, ItemStack stack, float inverseArmHeight,
                                          PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND || stack.isEmpty() || player.isScoping() || !LegacyBlock.blocking()) return;
        HumanoidArm arm = player.getMainArm();
        pose.pushPose();
        applyItemArmTransform(pose, arm, inverseArmHeight);
        LegacyBlock.applyPose(pose, arm, attack);
        float scale = ItemScale.scale();
        pose.scale(scale, scale, scale);
        renderItem(player, stack, arm == HumanoidArm.RIGHT ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                : ItemDisplayContext.FIRST_PERSON_LEFT_HAND, pose, collector, light);
        pose.popPose();
        ci.cancel();
    }
}
