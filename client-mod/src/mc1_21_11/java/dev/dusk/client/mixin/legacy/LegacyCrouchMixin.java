package dev.dusk.client.mixin.legacy;

import dev.dusk.client.modules.render.LegacyCombat;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Legacy Combat: the upright 1.8 crouch instead of the deep modern one, on
 * the model and on armour worn over it. The offsets are the gap between the
 * two poses, so they stay right on scaled models. From old-animations (MIT,
 * see NOTICE).
 */
@Mixin(HumanoidModel.class)
public class LegacyCrouchMixin {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void duskclient$oldCrouch(HumanoidRenderState state, CallbackInfo ci) {
        if (!state.isCrouching || !LegacyCombat.oldSneak()) return;
        head.y -= 3.2f;
        body.y -= 3.2f;
        rightArm.y -= 3.2f;
        leftArm.y -= 3.2f;
        rightLeg.y -= 3f;
        leftLeg.y -= 3f;
    }
}
