package dev.dusk.client.mixin.cosmetics;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import dev.dusk.client.render.cape.CapeMesh;
import dev.dusk.client.render.cape.CapeSim;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    /**
     * With Cape Physics on, the cape is drawn bent along its simulated shape
     * from the body instead of vanilla's tilted board; same conditions,
     * texture and glint as below.
     */
    @Inject(method = RENDER, at = @At("HEAD"), cancellable = true)
    private void duskclient$physicsCape(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                                        float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                                        float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (player.isInvisible() || !player.isModelPartShown(PlayerModelPart.CAPE)) return;
        ResourceLocation vanilla = player.getSkin().capeTexture();
        if (vanilla == null || player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) return;
        float[] shape = CapeSim.shape(player, partialTick);
        if (shape == null) return;
        PlayerCosmetics c = duskclient$cosmetics(player);
        ResourceLocation custom = c == null ? null : c.capeTexture();
        VertexConsumer vc = buffer.getBuffer(custom != null ? RenderType.entityTranslucent(custom) : RenderType.entitySolid(vanilla));
        @SuppressWarnings("unchecked")
        PlayerModel<AbstractClientPlayer> model =
                ((RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>) (Object) this).getParentModel();
        poseStack.pushPose();
        model.body.translateAndRotate(poseStack);
        poseStack.translate(0.0F, 0.0F, 0.125F);
        CapeMesh.emit(shape, poseStack.last(), vc, light, OverlayTexture.NO_OVERLAY);
        if (c != null && c.hasCape() && c.glint()) {
            CapeMesh.emit(shape, poseStack.last(), buffer.getBuffer(RenderType.armorEntityGlint()), light, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
        ci.cancel();
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
