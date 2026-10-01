package dev.dusk.client.mixin.skinlayers;

import dev.dusk.client.render.skin.SkinLayer3D;
import dev.dusk.client.modules.render.SkinLayers3D;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the flat outer layer where {@link SkinLayer3D} draws voxels. Hooked
 * after the model properties are set in {@code render} only, so the
 * first-person hand (which sets them itself) keeps its flat sleeve.
 */
@Mixin(PlayerRenderer.class)
public abstract class SkinLayersHideMixin {
    @Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;setModelProperties(Lnet/minecraft/client/player/AbstractClientPlayer;)V",
                    shift = At.Shift.AFTER))
    private void duskclient$hideFlatLayers(AbstractClientPlayer player, float yaw, float partialTick,
                                           com.mojang.blaze3d.vertex.PoseStack poseStack,
                                           net.minecraft.client.renderer.MultiBufferSource buffer, int light,
                                           CallbackInfo ci) {
        int parts = SkinLayer3D.parts(player);
        if (parts == 0) return;
        PlayerModel<AbstractClientPlayer> model = ((PlayerRenderer) (Object) this).getModel();
        if ((parts & SkinLayers3D.HAT) != 0) model.hat.visible = false;
        if ((parts & SkinLayers3D.JACKET) != 0) model.jacket.visible = false;
        if ((parts & SkinLayers3D.RIGHT_SLEEVE) != 0) model.rightSleeve.visible = false;
        if ((parts & SkinLayers3D.LEFT_SLEEVE) != 0) model.leftSleeve.visible = false;
        if ((parts & SkinLayers3D.RIGHT_PANTS) != 0) model.rightPants.visible = false;
        if ((parts & SkinLayers3D.LEFT_PANTS) != 0) model.leftPants.visible = false;
    }
}
