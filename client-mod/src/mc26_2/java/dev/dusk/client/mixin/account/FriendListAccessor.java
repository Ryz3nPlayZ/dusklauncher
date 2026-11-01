package dev.dusk.client.mixin.account;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The friends list poller, which belongs to the signed-in account (26.2+). */
@Mixin(Minecraft.class)
public interface FriendListAccessor {
    @Accessor("remoteFriendListUpdateHandler")
    RemoteFriendListUpdateHandler duskclient$friendList();

    @Mutable @Accessor("remoteFriendListUpdateHandler")
    void duskclient$setFriendList(RemoteFriendListUpdateHandler handler);
}
