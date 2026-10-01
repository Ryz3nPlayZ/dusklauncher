package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import dev.dusk.client.render.cape.CapeMesh;
import dev.dusk.client.render.cape.CapeShapeHolder;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Custom capes are drawn translucent (so PNG alpha works, as in Cosmetica and
 * MinecraftCapes) and, when the profile asks for it, with the armour
 * enchantment glint layered on top. With Cape Physics on, every cape is
 * drawn bent along its simulated shape instead of as vanilla's flat board.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
    @WrapOperation(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private <S> void duskclient$submitCape(
            SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
            int light, int overlay, int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumbling,
            Operation<Void> original, @Local(argsOnly = true) AvatarRenderState avatarState) {
        PlayerCosmetics c = ((ExtendedAvatarRenderState) avatarState).duskclient$getCosmetics();
        boolean custom = c != null && c.hasCape();
        if (custom) {
            ResourceLocation texture = avatarState.skin.cape().texturePath();
            renderType = RenderType.entityTranslucent(texture);
        }
        boolean glint = custom && c.glint();
        float[] shape = ((CapeShapeHolder) avatarState).duskclient$capeShape();
        if (shape != null) {
            PlayerModel parent = (PlayerModel) ((RenderLayer<?, ?>) (Object) this).getParentModel();
            poseStack.pushPose();
            parent.body.translateAndRotate(poseStack);
            poseStack.translate(0.0F, 0.0F, 0.125F);
            collector.submitCustomGeometry(poseStack, renderType, (pose, vc) -> CapeMesh.emit(shape, pose, vc, light, overlay));
            if (glint) {
                collector.order(1).submitCustomGeometry(poseStack, RenderType.armorEntityGlint(),
                        (pose, vc) -> CapeMesh.emit(shape, pose, vc, light, overlay));
            }
            poseStack.popPose();
            return;
        }
        original.call(collector, model, state, poseStack, renderType, light, overlay, outlineColor, crumbling);
        if (glint) {
            collector.order(1).submitModel(model, state, poseStack, RenderType.armorEntityGlint(), light, overlay, outlineColor, crumbling);
        }
    }
}
