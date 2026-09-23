package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.TimeChanger;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyTime's time override for 1.21.11, where the sky still reads the day
 * time off the client's copy of the level data. 26.1 moved the clock into
 * ClientClockManager, so those layers carry their own copy of this file.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public class TimeChangerMixin {
    @ModifyReturnValue(method = "getDayTime", at = @At("RETURN"))
    private long duskclient$overrideDayTime(long original) {
        return TimeChanger.dayTime(original);
    }
}
