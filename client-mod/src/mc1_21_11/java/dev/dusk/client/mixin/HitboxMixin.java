package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.dusk.client.modules.render.Hitbox;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shows entity hitboxes while the Hitboxes module is on, without needing
 * F3+B: the debug-gizmo list is rebuilt from these flags (see
 * {@code HitboxHooks.refresh}), so forcing the hitbox entry on drops
 * vanilla's own {@code EntityHitboxDebugRenderer} into the frame.
 */
@Mixin(DebugScreenEntryList.class)
public class HitboxMixin {
    @ModifyReturnValue(method = "isCurrentlyEnabled", at = @At("RETURN"))
    private boolean duskclient$forceHitboxes(boolean original, Identifier entry) {
        if (Hitbox.active() && DebugScreenEntries.ENTITY_HITBOXES.equals(entry)) return true;
        return original;
    }
}
