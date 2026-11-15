package dev.dusk.client.mixin.minimap;

import dev.dusk.client.render.map.MapTiles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the Minimap a block changed, so the tiles showing it are baked again. */
@Mixin(ClientLevel.class)
abstract class MapBlockUpdateMixin {
    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void duskclient$mapBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        // A forest resends its leaves' distance values by the thousand, and that never shows on a map.
        if (oldState.getBlock() != newState.getBlock()) {
            MapTiles.blockChanged(pos.getX(), pos.getZ());
        }
    }
}
