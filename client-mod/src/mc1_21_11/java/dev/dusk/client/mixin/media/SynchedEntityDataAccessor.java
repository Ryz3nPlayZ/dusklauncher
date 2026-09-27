package dev.dusk.client.mixin.media;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every value the local player carries, so the recording can show them as a server would. */
@Mixin(SynchedEntityData.class)
public interface SynchedEntityDataAccessor {
    @Accessor("itemsById")
    SynchedEntityData.DataItem<?>[] duskclient$itemsById();
}
