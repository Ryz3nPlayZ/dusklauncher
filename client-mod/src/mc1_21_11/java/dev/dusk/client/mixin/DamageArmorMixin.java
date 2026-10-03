package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.dusk.client.render.ArmorTint;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DamageTint: armour flashes with the entity wearing it, trims included, as
 * DamageTintPlus does. The enchantment glint is left alone. Decal trims (a
 * datapack option; no vanilla pattern uses it) keep their depth-equal
 * pipeline and stay untinted.
 */
@Mixin(EquipmentLayerRenderer.class)
public class DamageArmorMixin {
    @WrapOperation(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType duskclient$hurtArmorType(Identifier texture, Operation<RenderType> original,
                                                @Local(argsOnly = true) Object state) {
        return ArmorTint.flashing(state) ? ArmorTint.renderType(texture) : original.call(texture);
    }

    @WrapOperation(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Sheets;armorTrimsSheet(Z)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType duskclient$hurtTrimType(boolean decal, Operation<RenderType> original,
                                               @Local(argsOnly = true) Object state) {
        return !decal && ArmorTint.flashing(state) ? ArmorTint.renderType(Sheets.ARMOR_TRIMS_SHEET) : original.call(decal);
    }

    /** Ordinal 0 is the armour layer's overlay, 1 the glint's, 2 the trim's. */
    @ModifyExpressionValue(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = {@At(value = "FIELD", target = "Lnet/minecraft/client/renderer/texture/OverlayTexture;NO_OVERLAY:I", ordinal = 0),
                  @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/texture/OverlayTexture;NO_OVERLAY:I", ordinal = 2)})
    private int duskclient$hurtArmorOverlay(int original, @Local(argsOnly = true) Object state) {
        return ArmorTint.flashing(state) ? LivingEntityRenderer.getOverlayCoords((LivingEntityRenderState) state, 0f) : original;
    }
}
