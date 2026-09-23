package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.Deadmau5EarsLayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * MinecraftCapes ears are 14×7 images mapped 1:1 onto both ear cubes, not a
 * region of the 64×64 skin like deadmau5's own. Keep vanilla's model for
 * vanilla ears and draw ours with a second, differently-UV'd ear cube so
 * both keep working. 1.21–1.21.1 flavour: the layer only runs for the name
 * "deadmau5", so that check is widened to players with custom ears; it
 * positions each ear itself and then calls PlayerModel.renderEars, which
 * copies the head's pose onto the single "ear" part — mirrored here.
 */
@Mixin(Deadmau5EarsLayer.class)
public abstract class Deadmau5EarsLayerMixin {
    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V";

    @Unique
    private ModelPart duskclient$ear;

    @Unique
    private static ModelPart duskclient$createEar() {
        // Same cube as PlayerModel's "ear" (texOffs 24,0 on the skin), but UV'd from 0,0 on a 14×7 sheet.
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("ear",
                CubeListBuilder.create().texOffs(0, 0).addBox(-3.0F, -6.0F, -1.0F, 6.0F, 6.0F, 1.0F, CubeDeformation.NONE),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 14, 7).bakeRoot().getChild("ear");
    }

    @Unique
    private static ResourceLocation duskclient$ears(AbstractClientPlayer player) {
        PlayerCosmetics c = CosmeticsManager.get(player.getUUID(), player.getGameProfile().getName());
        return c == null ? null : c.earsTexture();
    }

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private boolean duskclient$isEared(String name, Object other, Operation<Boolean> original,
                                       @Local(argsOnly = true) AbstractClientPlayer player) {
        return original.call(name, other) || duskclient$ears(player) != null;
    }

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/PlayerModel;renderEars(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
    private void duskclient$renderEars(
            PlayerModel<AbstractClientPlayer> model, PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
            Operation<Void> original,
            @Local(argsOnly = true) MultiBufferSource buffer, @Local(argsOnly = true) AbstractClientPlayer player) {
        ResourceLocation ears = duskclient$ears(player);
        if (ears == null) {
            original.call(model, poseStack, consumer, light, overlay);
            return;
        }
        if (duskclient$ear == null) duskclient$ear = duskclient$createEar();
        duskclient$ear.copyFrom(model.head);
        duskclient$ear.x = 0.0F;
        duskclient$ear.y = 0.0F;
        duskclient$ear.render(poseStack, buffer.getBuffer(Compat.entityCutoutNoCull(ears)), light, overlay);
    }
}
