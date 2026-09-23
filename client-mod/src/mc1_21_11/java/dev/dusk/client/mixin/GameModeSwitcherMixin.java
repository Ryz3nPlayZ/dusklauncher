package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.misc.GameModeSwitcher;
import net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BactroMod's "Gamemode Switcher": the F3+F4 picker checks the client's own
 * permissions before it will send anything, which hides it on servers that
 * grant the mode through a command instead. Passing the check lets the picker
 * run; the server still has the final say over the command it sends.
 */
@Mixin(GameModeSwitcherScreen.class)
public class GameModeSwitcherMixin {
    @WrapOperation(
            method = "switchToHoveredGameMode(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/debug/GameModeSwitcherScreen$GameModeIcon;)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/permissions/PermissionCheck;check(Lnet/minecraft/server/permissions/PermissionSet;)Z"),
            require = 1)
    private static boolean duskclient$allowSwitch(PermissionCheck check, PermissionSet permissions,
                                                  Operation<Boolean> original) {
        return GameModeSwitcher.active() || original.call(check, permissions);
    }
}
