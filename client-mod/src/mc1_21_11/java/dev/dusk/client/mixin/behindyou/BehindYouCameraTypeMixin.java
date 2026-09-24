package dev.dusk.client.mixin.behindyou;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.BehindYou;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the camera rendering in third person while it eases back in, and
 * mirrored while it returns from the front view.
 */
@Mixin(CameraType.class)
public class BehindYouCameraTypeMixin {
    @ModifyReturnValue(method = "isFirstPerson", at = @At("RETURN"))
    private boolean dusk$keepThirdPerson(boolean firstPerson) {
        BehindYou m = managing();
        return m != null ? m.isFinished() && firstPerson : firstPerson;
    }

    @ModifyReturnValue(method = "isMirrored", at = @At("RETURN"))
    private boolean dusk$keepMirrored(boolean mirrored) {
        BehindYou m = managing();
        if (m == null) return mirrored;
        boolean animating = !m.isFinished()
                && m.previousPerspective() == CameraType.THIRD_PERSON_FRONT
                && Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON;
        return animating || mirrored;
    }

    private BehindYou managing() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || (Object) this != mc.options.getCameraType()) return null;
        BehindYou m = BehindYou.active();
        return m != null && m.animationEnabled() && m.isManagingPerspective() ? m : null;
    }
}
