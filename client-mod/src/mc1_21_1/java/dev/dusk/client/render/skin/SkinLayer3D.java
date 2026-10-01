package dev.dusk.client.render.skin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.modules.render.SkinLayers3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.jetbrains.annotations.Nullable;

import static dev.dusk.client.modules.render.SkinLayers3D.*;

/**
 * Draws the voxel outer layer on the parts {@link #parts} picks; the model
 * hides its flat ones for the same parts. 1.21.1 flavour: no render states,
 * so everything is read from the player.
 */
public final class SkinLayer3D extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public SkinLayer3D(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    /** Which outer-layer parts are voxels for this player this frame (0 = vanilla). */
    public static int parts(AbstractClientPlayer p) {
        int shown = (p.isModelPartShown(PlayerModelPart.HAT) ? HAT : 0)
                | (p.isModelPartShown(PlayerModelPart.JACKET) ? JACKET : 0)
                | (p.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE) ? RIGHT_SLEEVE : 0)
                | (p.isModelPartShown(PlayerModelPart.LEFT_SLEEVE) ? LEFT_SLEEVE : 0)
                | (p.isModelPartShown(PlayerModelPart.RIGHT_PANTS_LEG) ? RIGHT_PANTS : 0)
                | (p.isModelPartShown(PlayerModelPart.LEFT_PANTS_LEG) ? LEFT_PANTS : 0);
        double distanceSq = Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(p);
        int parts = SkinLayers3D.parts(distanceSq, p.isInvisible() || p.isSpectator(), shown,
                worn(p, EquipmentSlot.HEAD), worn(p, EquipmentSlot.CHEST),
                worn(p, EquipmentSlot.LEGS), worn(p, EquipmentSlot.FEET));
        return parts != 0 && mesh(p) != null ? parts : 0;
    }

    @Nullable
    private static SkinVoxels mesh(AbstractClientPlayer p) {
        PlayerSkin skin = p.getSkin();
        return SkinVoxelCache.get(skin.texture(), skin.model() == PlayerSkin.Model.SLIM, SkinPixels::alpha);
    }

    private static boolean worn(AbstractClientPlayer p, EquipmentSlot slot) {
        return !p.getItemBySlot(slot).isEmpty();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        int parts = parts(player);
        if (parts == 0) return;
        SkinVoxels mesh = mesh(player);
        PlayerModel<AbstractClientPlayer> model = getParentModel();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(player.getSkin().texture()));
        int overlay = LivingEntityRenderer.getOverlayCoords(player, 0);
        Part d = (part, id, bit) -> {
            if ((parts & bit) == 0 || !part.visible || mesh.isEmpty(id)) return;
            poseStack.pushPose();
            part.translateAndRotate(poseStack);
            mesh.emit(id, poseStack.last(), vc, light, overlay);
            poseStack.popPose();
        };
        d.draw(model.head, SkinVoxels.HEAD, HAT);
        d.draw(model.body, SkinVoxels.BODY, JACKET);
        d.draw(model.rightArm, SkinVoxels.RIGHT_ARM, RIGHT_SLEEVE);
        d.draw(model.leftArm, SkinVoxels.LEFT_ARM, LEFT_SLEEVE);
        d.draw(model.rightLeg, SkinVoxels.RIGHT_LEG, RIGHT_PANTS);
        d.draw(model.leftLeg, SkinVoxels.LEFT_LEG, LEFT_PANTS);
    }

    private interface Part {
        void draw(ModelPart part, int id, int bit);
    }
}
