package dev.dusk.client.render;

import net.minecraft.client.Minecraft;

/**
 * 26.2 version: the debug-gizmo list moved from the level renderer into
 * the level extractor with the frame-graph refactor.
 */
public final class HitboxHooks {
    private HitboxHooks() {}

    /** Rebuilds the debug-gizmo list so the hitbox renderer appears/vanishes with the module. */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelExtractor != null) mc.levelExtractor.debugRenderer.refreshRendererList();
    }
}
