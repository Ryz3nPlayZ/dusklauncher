package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.BlockHighlight;
import dev.dusk.client.modules.render.Freelook;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The selection box, handed to the Block Highlight module to redraw; and
 * terrain culling that follows freelook.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMixin {
    @Shadow private boolean displayListEntitiesDirty;

    @Unique private static boolean dusk$wasLooking;
    @Unique private static float dusk$lastYaw, dusk$lastPitch;

    @Inject(method = "drawSelectionBox", at = @At("HEAD"), cancellable = true)
    private void dusk$selection(EntityPlayer player, MovingObjectPosition hit, int execute, float partialTicks,
                                CallbackInfo ci) {
        if (!BlockHighlight.active()) return;
        if (execute != 0 || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        BlockHighlight.draw(player.worldObj, player, hit, partialTicks);
        ci.cancel();
    }

    /**
     * setupTerrain only rebuilds the visible chunk set when the view entity's
     * own rotation changes. Freelook turns the camera without turning the
     * player, so the set stayed the one from before (look at the sky, swing
     * round, and the ground was gone). Rebuild it whenever the freelook angle
     * moves, and once on starting and stopping.
     */
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void dusk$freelookCulling(Entity viewEntity, double partialTicks, ICamera camera, int frameCount,
                                      boolean playerSpectator, CallbackInfo ci) {
        Freelook f = Freelook.looking();
        boolean looking = f != null;
        if (looking != dusk$wasLooking) displayListEntitiesDirty = true;
        dusk$wasLooking = looking;
        if (!looking) return;
        if (f.yaw() != dusk$lastYaw || f.pitch() != dusk$lastPitch) displayListEntitiesDirty = true;
        dusk$lastYaw = f.yaw();
        dusk$lastPitch = f.pitch();
    }
}
