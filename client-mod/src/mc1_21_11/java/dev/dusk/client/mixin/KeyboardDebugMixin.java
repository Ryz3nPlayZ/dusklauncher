package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.modules.misc.GameModeSwitcher;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.PermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The other half of "Gamemode Switcher": the F3 handler makes the same
 * permission check before it will open the picker at all.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardDebugMixin {
    @WrapOperation(
            method = "handleDebugKeys",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/permissions/PermissionCheck;check(Lnet/minecraft/server/permissions/PermissionSet;)Z"),
            require = 2)
    private boolean duskclient$allowDebugKey(PermissionCheck check, PermissionSet permissions,
                                             Operation<Boolean> original) {
        return GameModeSwitcher.active() || original.call(check, permissions);
    }
}
