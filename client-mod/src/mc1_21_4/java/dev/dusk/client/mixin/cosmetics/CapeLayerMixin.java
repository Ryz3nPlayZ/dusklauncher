package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.cosmetics.ExtendedAvatarRenderState;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Custom capes are drawn translucent (so PNG alpha works, as in Cosmetica and
 * MinecraftCapes), with the animated frame resolved per draw, and, when the
 * profile asks for it, with the armour enchantment glint layered on top.
 * Vanilla capes are left alone. 1.21.2–1.21.8 flavour: MultiBufferSource.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V";

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer duskclient$capeBuffer(
            MultiBufferSource buffer, RenderType renderType, Operation<VertexConsumer> original,
            @Local(argsOnly = true) PlayerRenderState state) {
        PlayerCosmetics c = ((ExtendedAvatarRenderState) state).duskclient$getCosmetics();
        ResourceLocation cape = c == null ? null : c.capeTexture();
        if (cape == null) return original.call(buffer, renderType);
        return original.call(buffer, RenderType.entityTranslucent(cape));
    }

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/HumanoidModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
    private void duskclient$renderCape(
            HumanoidModel<PlayerRenderState> model, PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
            Operation<Void> original,
            @Local(argsOnly = true) MultiBufferSource buffer, @Local(argsOnly = true) PlayerRenderState state) {
        original.call(model, poseStack, consumer, light, overlay);
        PlayerCosmetics c = ((ExtendedAvatarRenderState) state).duskclient$getCosmetics();
        if (c != null && c.hasCape() && c.glint()) {
            model.renderToBuffer(poseStack, buffer.getBuffer(RenderType.armorEntityGlint()), light, overlay);
        }
    }
}
