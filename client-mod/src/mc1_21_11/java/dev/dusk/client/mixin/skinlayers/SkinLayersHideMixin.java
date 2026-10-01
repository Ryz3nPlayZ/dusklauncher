package dev.dusk.client.mixin.skinlayers;

import dev.dusk.client.render.skin.SkinLayer3D;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.dusk.client.modules.render.SkinLayers3D.*;

/** Hides the flat outer layer on the parts drawn as voxels. First-person arms don't go through here. */
@Mixin(PlayerModel.class)
public class SkinLayersHideMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void duskclient$hideFlatLayers(AvatarRenderState state, CallbackInfo ci) {
        int parts = SkinLayer3D.parts(state);
        if (parts == 0) return;
        PlayerModel m = (PlayerModel) (Object) this;
        if ((parts & HAT) != 0) m.hat.visible = false;
        if ((parts & JACKET) != 0) m.jacket.visible = false;
        if ((parts & RIGHT_SLEEVE) != 0) m.rightSleeve.visible = false;
        if ((parts & LEFT_SLEEVE) != 0) m.leftSleeve.visible = false;
        if ((parts & RIGHT_PANTS) != 0) m.rightPants.visible = false;
        if ((parts & LEFT_PANTS) != 0) m.leftPants.visible = false;
    }
}
