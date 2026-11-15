package dev.dusk.client.mixin;

import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's own "is the crosshair shown now" check (debug screen, spectator), for the crisp crosshair. */
@Mixin(GuiIngame.class)
public interface GuiIngameAccessor {
    @Invoker("showCrosshair")
    boolean dusk$showCrosshair();
}
