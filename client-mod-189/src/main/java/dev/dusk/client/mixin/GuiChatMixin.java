package dev.dusk.client.mixin;

import dev.dusk.client.util.HiDpi;
import net.minecraft.client.gui.GuiChat;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Retina: chat's hover and click-on-text position, in pixels like the rest of the GUI. */
@Mixin(GuiChat.class)
public abstract class GuiChatMixin {
    @Redirect(method = {"mouseClicked", "drawScreen"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getX()I"))
    private int dusk$x() {
        return HiDpi.mouse(Mouse.getX());
    }

    @Redirect(method = {"mouseClicked", "drawScreen"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getY()I"))
    private int dusk$y() {
        return HiDpi.mouse(Mouse.getY());
    }
}
