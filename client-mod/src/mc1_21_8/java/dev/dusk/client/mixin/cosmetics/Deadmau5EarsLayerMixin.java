package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerEarsModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.Deadmau5EarsLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MinecraftCapes ears are 14×7 images mapped 1:1 onto both ear cubes, not a
 * region of the 64×64 skin like deadmau5's own. Keep vanilla's model for
 * vanilla ears and draw ours with a second, differently-UV'd ears model so
 * both keep working. 1.21.2–1.21.8 flavour: the layer only runs for the
 * name "deadmau5", so that check is widened to players with custom ears.
 */
@Mixin(Deadmau5EarsLayer.class)
public abstract class Deadmau5EarsLayerMixin {
    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V";

    @Unique
    private PlayerEarsModel duskclient$earsModel;

    @Unique
    private static LayerDefinition duskclient$createEarsLayer() {
        // HumanoidModel.createMesh + clearChild, as vanilla's PlayerEarsModel.createEarsLayer does here
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.clearChild("head");
        head.clearChild("hat");
        for (String part : new String[] {"body", "left_arm", "right_arm", "left_leg", "right_leg"}) root.clearChild(part);
        CubeListBuilder ear = CubeListBuilder.create().texOffs(0, 0)
                .addBox(-3.0F, -6.0F, -1.0F, 6.0F, 6.0F, 1.0F, new CubeDeformation(1.0F, 1.0F, 0.2F));
        head.addOrReplaceChild("left_ear", ear, PartPose.offset(-6.0F, -6.0F, 0.0F));
        head.addOrReplaceChild("right_ear", ear, PartPose.offset(6.0F, -6.0F, 0.0F));
        return LayerDefinition.create(mesh, 14, 7);
    }

    @Unique
    private static ResourceLocation duskclient$ears(PlayerRenderState state) {
        PlayerCosmetics c = ((ExtendedAvatarRenderState) state).duskclient$getCosmetics();
        return c == null ? null : c.earsTexture();
    }

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private boolean duskclient$isEared(String name, Object other, Operation<Boolean> original,
                                       @Local(argsOnly = true) PlayerRenderState state) {
        return original.call(name, other) || duskclient$ears(state) != null;
    }

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/HumanoidModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
    private void duskclient$renderEars(
            HumanoidModel<PlayerRenderState> model, PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
            Operation<Void> original,
            @Local(argsOnly = true) MultiBufferSource buffer, @Local(argsOnly = true) PlayerRenderState state) {
        ResourceLocation ears = duskclient$ears(state);
        if (ears == null) {
            original.call(model, poseStack, consumer, light, overlay);
            return;
        }
        if (duskclient$earsModel == null) {
            duskclient$earsModel = new PlayerEarsModel(duskclient$createEarsLayer().bakeRoot());
        }
        model.copyPropertiesTo(duskclient$earsModel);
        duskclient$earsModel.setupAnim(state);
        duskclient$earsModel.renderToBuffer(poseStack, buffer.getBuffer(Compat.entityCutoutNoCull(ears)), light, overlay);
    }
}
