package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerEarsModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.Deadmau5EarsLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MinecraftCapes ears are 14×7 images mapped 1:1 onto both ear cubes, not a
 * region of the 64×64 skin like deadmau5's own. Keep vanilla's model for
 * vanilla ears and draw ours with a second, differently-UV'd ears model so
 * both keep working.
 */
@Mixin(Deadmau5EarsLayer.class)
public abstract class Deadmau5EarsLayerMixin {
    @Unique
    private PlayerEarsModel duskclient$earsModel;

    @Unique
    private static LayerDefinition duskclient$createEarsLayer() {
        MeshDefinition mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        PartDefinition root = mesh.getRoot().clearRecursively();
        PartDefinition head = root.getChild("head");
        CubeListBuilder ear = CubeListBuilder.create().texOffs(0, 0)
                .addBox(-3.0F, -6.0F, -1.0F, 6.0F, 6.0F, 1.0F, new CubeDeformation(1.0F, 1.0F, 0.2F));
        head.addOrReplaceChild("left_ear", ear, PartPose.offset(-6.0F, -6.0F, 0.0F));
        head.addOrReplaceChild("right_ear", ear, PartPose.offset(6.0F, -6.0F, 0.0F));
        return LayerDefinition.create(mesh, 14, 7);
    }

    @WrapOperation(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private <S> void duskclient$submitEars(
            SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
            int light, int overlay, int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumbling,
            Operation<Void> original, @Local(argsOnly = true) AvatarRenderState avatarState) {
        PlayerCosmetics c = ((ExtendedAvatarRenderState) avatarState).duskclient$getCosmetics();
        Identifier ears = c == null ? null : c.earsTexture();
        if (ears == null) {
            original.call(collector, model, state, poseStack, renderType, light, overlay, outlineColor, crumbling);
            return;
        }
        if (duskclient$earsModel == null) {
            duskclient$earsModel = new PlayerEarsModel(duskclient$createEarsLayer().bakeRoot());
        }
        collector.submitModel(duskclient$earsModel, avatarState, poseStack, Compat.entityCutoutNoCull(ears), light, overlay, outlineColor, crumbling);
    }
}
