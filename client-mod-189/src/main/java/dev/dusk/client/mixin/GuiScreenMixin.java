package dev.dusk.client.mixin;

import dev.dusk.client.util.HiDpi;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Retina: every screen's click and drag position, from LWJGL's points into Minecraft's pixels. */
@Mixin(GuiScreen.class)
public abstract class GuiScreenMixin {
    @Redirect(method = "handleMouseInput", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventX()I"))
    private int dusk$eventX() {
        return HiDpi.mouse(Mouse.getEventX());
    }

    @Redirect(method = "handleMouseInput", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getEventY()I"))
    private int dusk$eventY() {
        return HiDpi.mouse(Mouse.getEventY());
    }
}
