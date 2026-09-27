package dev.dusk.client.mixin.media;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A replay's connection is ticked like one to a server while it logs in. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("pendingConnection")
    void duskclient$setPendingConnection(Connection connection);
}
