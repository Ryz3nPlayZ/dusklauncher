package dev.dusk.client.mixin.media;

import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Same as {@link MoveEntityAccessor}, for head turns. */
@Mixin(ClientboundRotateHeadPacket.class)
public interface RotateHeadAccessor {
    @Accessor("entityId")
    int duskclient$entityId();
}
