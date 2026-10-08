package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.TimeChanger;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyTime's time override, 26.3 flavour: each clock answers for its own
 * ticks there rather than the manager answering for all of them.
 */
@Mixin(ClientClockManager.ClientClockInstance.class)
public class TimeChangerMixin {
    @ModifyReturnValue(method = "totalTicks", at = @At("RETURN"))
    private long duskclient$overrideTicks(long original) {
        return TimeChanger.dayTime(original);
    }
}
