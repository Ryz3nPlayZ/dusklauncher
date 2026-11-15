package dev.dusk.client.mixin.emote;

import dev.dusk.client.emote.EmoteAnimation.Bone;
import dev.dusk.client.emote.EmotePose;
import dev.dusk.client.emote.EmoteState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Emotes: after vanilla poses the model, the emote's bones replace the
 * rotations it animates and add their offsets. Armour models run the same
 * setupAnim with the same state, so worn armour follows. The hat, jacket
 * and sleeves are children of these parts, so they follow too.
 *
 * <p>Bedrock files use x and y the other way round from Java models
 * (Java's model space is flipped), hence the signs below.
 */
@Mixin(HumanoidModel.class)
public class EmotePoseMixin {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void duskclient$emote(HumanoidRenderState state, CallbackInfo ci) {
        EmotePose pose = ((EmoteState) state).duskclient$emote();
        if (pose == null) return;
        duskclient$apply(head, pose, Bone.HEAD);
        duskclient$apply(body, pose, Bone.BODY);
        duskclient$apply(rightArm, pose, Bone.RIGHT_ARM);
        duskclient$apply(leftArm, pose, Bone.LEFT_ARM);
        duskclient$apply(rightLeg, pose, Bone.RIGHT_LEG);
        duskclient$apply(leftLeg, pose, Bone.LEFT_LEG);
    }

    @Unique
    private static void duskclient$apply(ModelPart part, EmotePose pose, Bone bone) {
        float[] r = pose.rotation(bone);
        if (r != null) {
            part.xRot = (float) Math.toRadians(-r[0]);
            part.yRot = (float) Math.toRadians(-r[1]);
            part.zRot = (float) Math.toRadians(r[2]);
        }
        float[] p = pose.offset(bone);
        if (p != null) {
            part.x -= p[0];
            part.y -= p[1];
            part.z += p[2];
        }
    }
}
