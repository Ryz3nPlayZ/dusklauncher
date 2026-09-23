package dev.dusk.client.render;

import net.minecraft.client.Minecraft;

/**
 * Version seam for the Hitboxes module. The debug-gizmo list is only
 * rebuilt when the debug entries change, so toggling the module has to
 * kick it; 1.21.11 and 26.1 keep the list on the level renderer, while
 * 26.2 moved it into the level extractor (which carries its own copy of
 * this file).
 */
public final class HitboxHooks {
    private HitboxHooks() {}

    /** Rebuilds the debug-gizmo list so the hitbox renderer appears/vanishes with the module. */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) mc.levelRenderer.debugRenderer.refreshRendererList();
    }
}
