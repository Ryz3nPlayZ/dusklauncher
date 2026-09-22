package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Custom capes are drawn translucent (so PNG alpha works, as in Cosmetica and
 * MinecraftCapes) and, when the profile asks for it, with the armour
 * enchantment glint layered on top. Vanilla capes are left alone.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
    @WrapOperation(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
    private <S> void duskclient$submitCape(
            SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
            int light, int overlay, int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumbling,
            Operation<Void> original, @Local(argsOnly = true) AvatarRenderState avatarState) {
        PlayerCosmetics c = ((ExtendedAvatarRenderState) avatarState).duskclient$getCosmetics();
        if (c == null || !c.hasCape()) {
            original.call(collector, model, state, poseStack, renderType, light, overlay, outlineColor, crumbling);
            return;
        }
        Identifier texture = avatarState.skin.cape().texturePath();
        original.call(collector, model, state, poseStack, RenderTypes.entityTranslucent(texture), light, overlay, outlineColor, crumbling);
        if (c.glint()) {
            collector.order(1).submitModel(model, state, poseStack, RenderTypes.armorEntityGlint(), light, overlay, outlineColor, crumbling);
        }
    }
}
