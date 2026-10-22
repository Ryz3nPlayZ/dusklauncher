package dev.dusk.client.mixin.thirdperson;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.FancyThirdPerson;
import net.minecraft.client.CameraType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fancy Third Person: F5 can go from the back view straight to first person. */
@Mixin(CameraType.class)
public class FancyCycleMixin {
    @ModifyReturnValue(method = "cycle", at = @At("RETURN"))
    private CameraType dusk$skipFront(CameraType next) {
        return next == CameraType.THIRD_PERSON_FRONT && FancyThirdPerson.skipsFrontView() ? CameraType.FIRST_PERSON : next;
    }
}
