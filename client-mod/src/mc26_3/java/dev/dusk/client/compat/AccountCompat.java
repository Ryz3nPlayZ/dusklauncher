package dev.dusk.client.compat;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.FriendsService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import dev.dusk.client.mixin.account.FriendListAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The account-bound pieces the game builds at startup, built again for
 * another account (see AccountSwitcher). 26.3 flavour: services come from
 * the discovery service, and the friends list.
 */
public final class AccountCompat {
    private AccountCompat() {}

    @Nullable private static volatile MinecraftServicesDiscoveryService discovery;

    private static MinecraftServicesDiscoveryService discovery(Minecraft mc) {
        MinecraftServicesDiscoveryService d = discovery;
        if (d == null) discovery = d = MinecraftServicesDiscoveryService.create(mc.getProxy(), false);
        return d;
    }

    public static User user(String name, UUID id, String token, Optional<String> xuid) {
        return new User(name, id, token, xuid, Optional.empty());
    }

    /** Mojang's account services for {@code token}; offline when it can't be had. Blocking. */
    public static UserApiService api(Minecraft mc, String token) {
        if (token.isEmpty()) return UserApiService.OFFLINE;
        try {
            return discovery(mc).createUserApiService(token);
        } catch (Exception e) {
            return UserApiService.OFFLINE;
        }
    }

    /** The profile (with its skin) for the game's profile future; null when it can't be fetched. Blocking. */
    @Nullable
    public static Object profile(Minecraft mc, UUID id) {
        return mc.services().sessionService().fetchProfile(id, true);
    }

    /** Main thread, after {@link #api}. Swaps in a friends list poller for the new account too. */
    public static PlayerSocialManager social(Minecraft mc, UserApiService api, String token) {
        FriendsService friends = discovery(mc).createFriendsService(token);
        RemoteFriendListUpdateHandler handler = new RemoteFriendListUpdateHandler(friends, mc);
        FriendListAccessor acc = (FriendListAccessor) mc;
        acc.duskclient$friendList().close();
        acc.duskclient$setFriendList(handler);
        PlayerSocialManager m = new PlayerSocialManager(mc, api, friends, handler);
        if (m.isFriendListEnabled()) handler.start();
        return m;
    }
}
