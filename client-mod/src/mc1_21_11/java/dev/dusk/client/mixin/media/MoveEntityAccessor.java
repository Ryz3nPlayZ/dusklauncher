package dev.dusk.client.mixin.media;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Relative moves only expose their entity through a level lookup; the clip buffer needs the bare id. */
@Mixin(ClientboundMoveEntityPacket.class)
public interface MoveEntityAccessor {
    @Accessor("entityId")
    int duskclient$entityId();
}
