package dev.dusk.client.compat;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The account-bound pieces the game builds at startup, built again for
 * another account (see AccountSwitcher). 1.21.1-1.21.8 flavour.
 */
public final class AccountCompat {
    private AccountCompat() {}

    public static User user(String name, UUID id, String token, Optional<String> xuid) {
        return new User(name, id, token, xuid, Optional.empty(), User.Type.MSA);
    }

    /** Mojang's account services for {@code token}; offline when it can't be had. Blocking. */
    public static UserApiService api(Minecraft mc, String token) {
        if (token.isEmpty()) return UserApiService.OFFLINE;
        try {
            return new YggdrasilAuthenticationService(mc.getProxy()).createUserApiService(token);
        } catch (Exception e) {
            return UserApiService.OFFLINE;
        }
    }

    /** The profile (with its skin) for the game's profile future; null when it can't be fetched. Blocking. */
    @Nullable
    public static Object profile(Minecraft mc, UUID id) {
        return mc.getMinecraftSessionService().fetchProfile(id, true);
    }

    /** Main thread. */
    public static PlayerSocialManager social(Minecraft mc, UserApiService api, String token) {
        return new PlayerSocialManager(mc, api);
    }
}
