package dev.dusk.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.dusk.client.cosmetics.CapeRegistry.AccessoryEntry;
import dev.dusk.client.cosmetics.model.AccessoryModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a player's model accessories on their body parts, placing them the
 * way Cosmetica does so an accessory imported from there sits in the same
 * spot (docs/COSMETICS.md §5):
 *
 * <pre>
 * part.translateAndRotate → scale(1,−1,−1) → [mirror: scale(−1,1,1)] →
 * rotate Y 180° → translate(offset) → translate(0, ¼, 0) → translate(−½,−½,−½)
 * → quads at from/to ÷ 16
 * </pre>
 *
 * where {@code offset} is the registry's pixel offset after Cosmetica's
 * per-attachment shift (head +4 y; arms −6 y, ±1 x; body/legs −8 y), ÷ 16,
 * plus ±½ px on the arms for slim skins.
 */
public final class AccessoriesLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public AccessoriesLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
                       float yRot, float xRot) {
        if (state.isInvisible) return;
        PlayerCosmetics c = ((ExtendedAvatarRenderState) state).duskclient$getCosmetics();
        if (c == null || c.accessories().isEmpty()) return;

        boolean cloak = state.showCape && state.skin != null && state.skin.cape() != null && !isElytra(state.chestEquipment);
        boolean slim = state.skin != null && state.skin.model() == PlayerModelType.SLIM;
        PlayerModel model = getParentModel();

        for (Accessory a : c.accessories()) {
            if (!a.isReady() || !visible(a.entry(), state, cloak)) continue;
            AccessoryEntry e = a.entry();
            boolean mirror = e.mirrored();
            ModelPart part;
            float dx = 0, dy;
            switch (e.attachment()) {
                case HEAD -> {
                    part = model.head;
                    dy = 4;
                }
                case LEFT_ARM -> {
                    part = mirror ? model.rightArm : model.leftArm;
                    dx = -1 + (slim ? 0.5f : 0);
                    dy = -6;
                }
                case RIGHT_ARM -> {
                    part = mirror ? model.leftArm : model.rightArm;
                    dx = 1 - (slim ? 0.5f : 0);
                    dy = -6;
                }
                case LEFT_LEG -> {
                    part = mirror ? model.rightLeg : model.leftLeg;
                    dy = -8;
                }
                case RIGHT_LEG -> {
                    part = mirror ? model.leftLeg : model.rightLeg;
                    dy = -8;
                }
                default -> {
                    part = model.body;
                    dy = -8;
                }
            }
            if (!part.visible) continue;

            poseStack.pushPose();
            part.translateAndRotate(poseStack);
            poseStack.scale(1, -1, -1);
            if (mirror) poseStack.scale(-1, 1, 1);
            poseStack.mulPose(Axis.YP.rotationDegrees(180));
            float[] o = e.offset();
            poseStack.translate((o[0] + dx) / 16f, (o[1] + dy) / 16f + 0.25f, o[2] / 16f);
            poseStack.translate(-0.5f, -0.5f, -0.5f);

            final AccessoryModel m = a.model();
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(a.texture().current()), (pose, vc) -> {
                for (AccessoryModel.Quad q : m.quads()) {
                    float[] p = q.pos();
                    float[] uv = q.uv();
                    for (int i = 0; i < 4; i++) {
                        vc.addVertex(pose, p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
                                .setColor(-1)
                                .setUv(uv[i * 2], uv[i * 2 + 1])
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(light)
                                .setNormal(pose, q.nx(), q.ny(), q.nz());
                    }
                }
            });
            poseStack.popPose();
        }
    }

    /** Cosmetica's hide-with flags against what the player is wearing. */
    private static boolean visible(AccessoryEntry e, AvatarRenderState s, boolean cloak) {
        if (e.flags() == 0) return true;
        if (e.has(AccessoryEntry.HIDE_WITH_HELMET) && !isEmpty(s.headEquipment)) return false;
        if (!isEmpty(s.chestEquipment)) {
            if (isElytra(s.chestEquipment)) {
                if (e.has(AccessoryEntry.HIDE_WITH_ELYTRA)) return false;
            } else if (e.has(AccessoryEntry.HIDE_WITH_CHESTPLATE)) {
                return false;
            }
        }
        if (e.has(AccessoryEntry.HIDE_WITH_LEGGINGS) && !isEmpty(s.legsEquipment)) return false;
        if (e.has(AccessoryEntry.HIDE_WITH_BOOTS) && !isEmpty(s.feetEquipment)) return false;
        if (e.has(AccessoryEntry.HIDE_WITH_CLOAK) && cloak) return false;
        if (e.has(AccessoryEntry.HIDE_WITH_PARROT)
                && (s.parrotOnLeftShoulder != null || s.parrotOnRightShoulder != null)) return false;
        return true;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    private static boolean isElytra(ItemStack stack) {
        return !isEmpty(stack) && stack.is(net.minecraft.world.item.Items.ELYTRA);
    }
}
