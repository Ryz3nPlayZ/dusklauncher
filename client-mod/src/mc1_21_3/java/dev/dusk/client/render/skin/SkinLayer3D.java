package dev.dusk.client.render.skin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.modules.render.SkinLayers3D;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import static dev.dusk.client.modules.render.SkinLayers3D.*;

/** Draws the voxel outer layer on the parts {@link #parts} picks; the model hides its flat ones for the same parts. */
public final class SkinLayer3D extends RenderLayer<PlayerRenderState, PlayerModel> {
    public SkinLayer3D(RenderLayerParent<PlayerRenderState, PlayerModel> parent) {
        super(parent);
    }

    /** Which outer-layer parts are voxels for this player this frame (0 = vanilla). */
    public static int parts(PlayerRenderState s) {
        if (s.skin == null) return 0;
        int shown = (s.showHat ? HAT : 0) | (s.showJacket ? JACKET : 0)
                | (s.showRightSleeve ? RIGHT_SLEEVE : 0) | (s.showLeftSleeve ? LEFT_SLEEVE : 0)
                | (s.showRightPants ? RIGHT_PANTS : 0) | (s.showLeftPants ? LEFT_PANTS : 0);
        int parts = SkinLayers3D.parts(s.distanceToCameraSq, s.isInvisible || s.isSpectator, shown, worn(s.headItem),
                worn(s.chestItem), worn(s.legsItem), worn(s.feetItem));
        return parts != 0 && mesh(s) != null ? parts : 0;
    }

    @Nullable
    private static SkinVoxels mesh(PlayerRenderState s) {
        return SkinVoxelCache.get(s.skin.texture(), s.skin.model() == PlayerSkin.Model.SLIM, SkinPixels::alpha);
    }

    private static boolean worn(ItemStack stack) {
        return stack != null && !stack.isEmpty();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, PlayerRenderState state,
                       float yRot, float xRot) {
        int parts = parts(state);
        if (parts == 0) return;
        SkinVoxels mesh = mesh(state);
        PlayerModel model = getParentModel();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(state.skin.texture()));
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0);
        Part p = (part, id, bit) -> {
            if ((parts & bit) == 0 || !part.visible || mesh.isEmpty(id)) return;
            poseStack.pushPose();
            part.translateAndRotate(poseStack);
            mesh.emit(id, poseStack.last(), vc, light, overlay);
            poseStack.popPose();
        };
        p.draw(model.head, SkinVoxels.HEAD, HAT);
        p.draw(model.body, SkinVoxels.BODY, JACKET);
        p.draw(model.rightArm, SkinVoxels.RIGHT_ARM, RIGHT_SLEEVE);
        p.draw(model.leftArm, SkinVoxels.LEFT_ARM, LEFT_SLEEVE);
        p.draw(model.rightLeg, SkinVoxels.RIGHT_LEG, RIGHT_PANTS);
        p.draw(model.leftLeg, SkinVoxels.LEFT_LEG, LEFT_PANTS);
    }

    private interface Part {
        void draw(ModelPart part, int id, int bit);
    }
}
