package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Custom capes are drawn translucent (so PNG alpha works, as in Cosmetica and
 * MinecraftCapes), with the animated frame resolved per draw, and, when the
 * profile asks for it, with the armour enchantment glint layered on top.
 * Vanilla capes are left alone. 1.21–1.21.1 flavour: the layer renders the
 * entity directly and draws the cloak through PlayerModel.renderCloak.
 */
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V";

    @Unique
    private static PlayerCosmetics duskclient$cosmetics(AbstractClientPlayer player) {
        return CosmeticsManager.get(player.getUUID(), player.getGameProfile().getName());
    }

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer duskclient$capeBuffer(
            MultiBufferSource buffer, RenderType renderType, Operation<VertexConsumer> original,
            @Local(argsOnly = true) AbstractClientPlayer player) {
        PlayerCosmetics c = duskclient$cosmetics(player);
        ResourceLocation cape = c == null ? null : c.capeTexture();
        if (cape == null) return original.call(buffer, renderType);
        return original.call(buffer, RenderType.entityTranslucent(cape));
    }

    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/PlayerModel;renderCloak(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
    private void duskclient$renderCape(
            PlayerModel<AbstractClientPlayer> model, PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
            Operation<Void> original,
            @Local(argsOnly = true) MultiBufferSource buffer, @Local(argsOnly = true) AbstractClientPlayer player) {
        original.call(model, poseStack, consumer, light, overlay);
        PlayerCosmetics c = duskclient$cosmetics(player);
        if (c != null && c.hasCape() && c.glint()) {
            model.renderCloak(poseStack, buffer.getBuffer(RenderType.armorEntityGlint()), light, overlay);
        }
    }
}
