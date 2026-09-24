package dev.dusk.client.mixin.behindyou;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.render.BehindYou;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** With "Animate the F5 key" on, vanilla's perspective key goes through BehindYou too. */
@Mixin(Minecraft.class)
public class BehindYouF5Mixin {
    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;setCameraType(Lnet/minecraft/client/CameraType;)V"))
    private void dusk$behindYouF5(Options options, CameraType type, Operation<Void> original) {
        BehindYou m = BehindYou.active();
        if (m != null && m.capturesF5()) m.updatePerspective(type);
        else original.call(options, type);
    }
}
