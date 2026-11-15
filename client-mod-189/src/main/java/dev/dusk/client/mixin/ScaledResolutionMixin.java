package dev.dusk.client.mixin;

import dev.dusk.client.util.HiDpi;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Retina: the GUI scale option times the pixel factor, so menus keep their size and gain detail. */
@Mixin(ScaledResolution.class)
public abstract class ScaledResolutionMixin {
    @Redirect(method = "<init>", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/settings/GameSettings;guiScale:I", opcode = Opcodes.GETFIELD))
    private int dusk$guiScale(GameSettings settings) {
        return HiDpi.guiScale(settings.guiScale);
    }
}
