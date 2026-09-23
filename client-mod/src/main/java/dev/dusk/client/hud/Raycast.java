package dev.dusk.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Flex-HUD's RaycastTickable: a once-per-tick pick from the camera out to the
 * render distance, shared by the elements that need to know what you are
 * looking at further away than vanilla's reach.
 */
public final class Raycast {
    private static HitResult hitResult;

    private Raycast() {}

    public static void tick(Minecraft mc) {
        if (mc.getCameraEntity() == null) {
            hitResult = null;
            return;
        }
        int viewDistanceBlocks = mc.options.renderDistance().get() * 16;
        hitResult = mc.getCameraEntity().pick(viewDistanceBlocks, 0, false);
    }

    @Nullable
    public static HitResult hitResult() {
        return hitResult;
    }
}
