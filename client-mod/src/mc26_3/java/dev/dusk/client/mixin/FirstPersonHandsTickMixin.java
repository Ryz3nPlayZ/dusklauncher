package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.dusk.client.modules.misc.BoatMap;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Boat Map, from BactroMod. Rowing marks the hands busy, which drops
 * whatever you hold out of sight; a map is the one thing you want to keep
 * reading, so it follows the ordinary raise/lower curve instead. 26.3 keeps
 * the hand heights here, apart from the renderer ({@link ItemInHandMixin}).
 */
@Mixin(FirstPersonHandsAndItems.class)
public class FirstPersonHandsTickMixin {
    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float offHandHeight;

    /** Main hand: the first clamp is the busy hands' drop. */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F", ordinal = 0),
            require = 1)
    private float duskclient$boatMapMainHand(float value, float min, float max, Operation<Float> original,
                                             @Local(argsOnly = true) LocalPlayer player) {
        if (!duskclient$mapInBoat(player, this.mainHandItem)) return original.call(value, min, max);
        float swap = player.getItemSwapScale(1f);
        float target = this.mainHandItem == player.getMainHandItem() ? swap * swap * swap : 0;
        return this.mainHandHeight + Mth.clamp(target - this.mainHandHeight, -0.4f, 0.4f);
    }

    /** Off hand. */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F", ordinal = 1),
            require = 1)
    private float duskclient$boatMapOffHand(float value, float min, float max, Operation<Float> original,
                                            @Local(argsOnly = true) LocalPlayer player) {
        if (!duskclient$mapInBoat(player, this.offHandItem)) return original.call(value, min, max);
        float target = this.offHandItem == player.getOffhandItem() ? 1 : 0;
        return this.offHandHeight + Mth.clamp(target - this.offHandHeight, -0.4f, 0.4f);
    }

    @Unique
    private static boolean duskclient$mapInBoat(LocalPlayer player, ItemStack stack) {
        return BoatMap.active() && stack.is(Items.FILLED_MAP) && player.getVehicle() instanceof AbstractBoat;
    }
}
