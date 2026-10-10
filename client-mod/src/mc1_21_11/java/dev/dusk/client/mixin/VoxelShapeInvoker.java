package dev.dusk.client.mixin;

import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Block Highlight's Air Exposed faces ask whether a neighbour's shape fills its whole cell. */
@Mixin(VoxelShape.class)
public interface VoxelShapeInvoker {
    @Invoker("isCubeLike")
    boolean duskclient$isCubeLike();
}
