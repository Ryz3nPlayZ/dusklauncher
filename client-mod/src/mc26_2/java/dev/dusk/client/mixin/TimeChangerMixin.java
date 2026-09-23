package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.TimeChanger;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyTime's time override from 26.1 on: the day time comes off the clock
 * manager rather than the level data.
 */
@Mixin(ClientClockManager.class)
public class TimeChangerMixin {
    @ModifyReturnValue(method = "getTotalTicks", at = @At("RETURN"))
    private long duskclient$overrideTicks(long original) {
        return TimeChanger.dayTime(original);
    }
}
