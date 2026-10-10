package dev.dusk.client.mixin.totem;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.dusk.client.modules.hud.TotemCounter;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Totem Counter's "In the tab list too". */
@Mixin(PlayerTabOverlay.class)
public abstract class TotemTabMixin {
    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
    private Component duskclient$popsInTab(Component name, @Local(argsOnly = true) PlayerInfo info) {
        return TotemCounter.tabName(info, name);
    }
}
