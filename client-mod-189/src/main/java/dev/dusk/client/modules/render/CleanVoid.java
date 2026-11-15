package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;

/**
 * Stops 1.8.9 drawing its void under the sky: a black box around you while
 * your eyes are below the world's horizon height (63, or 0 in superflat),
 * and a flat 768-block dark square at that height that shows its edges when
 * you look down into the void. Both hang off World.getHorizon(), which
 * WorldMixin pushes far below the far clip plane while this is on; the sky
 * (or a resource pack's custom sky) then carries on below the horizon the
 * way it does in modern versions. Purely visual.
 */
public class CleanVoid extends Module {
    private static CleanVoid instance;

    public CleanVoid() {
        super("cleanvoid", "Clean Void", Category.RENDER,
                "Removes the black box and dark square drawn below the horizon.");
        instance = this;
        setEnabled(true);
    }

    public static boolean active() {
        CleanVoid m = instance;
        return m != null && m.enabled();
    }
}
