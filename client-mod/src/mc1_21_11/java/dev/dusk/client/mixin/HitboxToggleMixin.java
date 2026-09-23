package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.Hitbox;
import dev.dusk.client.render.HitboxHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kicks the debug-gizmo list when the Hitboxes module toggles: the list is
 * only rebuilt when the debug entries change, so without this the renderer
 * would not appear/vanish until the next F3 change. Lives in the version
 * layer (rather than a direct call from {@code Hitbox}) because the gizmo
 * list moved between versions — 26.2 keeps it on the level extractor, not
 * the level renderer — and the main layer cannot name either holder on
 * targets that lack them.
 */
@Mixin(Hitbox.class)
public class HitboxToggleMixin {
    @Inject(method = "onEnable", at = @At("HEAD"))
    private void duskclient$refreshOn(CallbackInfo ci) {
        HitboxHooks.refresh();
    }

    @Inject(method = "onDisable", at = @At("HEAD"))
    private void duskclient$refreshOff(CallbackInfo ci) {
        HitboxHooks.refresh();
    }
}
