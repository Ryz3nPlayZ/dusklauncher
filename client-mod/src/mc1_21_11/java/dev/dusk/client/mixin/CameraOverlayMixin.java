package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.NoPumpkinBlur;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BactroMod's "Pumpkin Overlay": the camera overlay is driven by whatever is
 * equipped, so hiding the pumpkin means telling that one lookup the head
 * slot is empty. Every other equipment overlay still draws.
 */
@Mixin(Gui.class)
public class CameraOverlayMixin {
    @WrapOperation(
            method = {"renderCameraOverlays", "extractCameraOverlays"},
            require = 1,
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/player/LocalPlayer;getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack duskclient$hidePumpkin(LocalPlayer player, EquipmentSlot slot, Operation<ItemStack> original) {
        ItemStack stack = original.call(player, slot);
        if (slot.isArmor() && stack.is(Items.CARVED_PUMPKIN) && NoPumpkinBlur.active()) return ItemStack.EMPTY;
        return stack;
    }
}
