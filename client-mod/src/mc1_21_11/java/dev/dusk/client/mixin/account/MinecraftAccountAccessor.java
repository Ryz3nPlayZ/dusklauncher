package dev.dusk.client.mixin.account;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/** Everything the game builds from the signed-in account, for switching accounts in place. */
@Mixin(Minecraft.class)
public interface MinecraftAccountAccessor {
    @Mutable @Accessor("user")
    void duskclient$setUser(User user);

    @Mutable @Accessor("profileFuture")
    void duskclient$setProfileFuture(CompletableFuture<?> future);

    @Mutable @Accessor("userApiService")
    void duskclient$setUserApiService(UserApiService api);

    @Mutable @Accessor("userPropertiesFuture")
    void duskclient$setUserPropertiesFuture(CompletableFuture<?> future);

    @Mutable @Accessor("playerSocialManager")
    void duskclient$setPlayerSocialManager(PlayerSocialManager manager);

    @Mutable @Accessor("telemetryManager")
    void duskclient$setTelemetryManager(ClientTelemetryManager manager);

    @Mutable @Accessor("profileKeyPairManager")
    void duskclient$setProfileKeyPairManager(ProfileKeyPairManager manager);

    @Accessor("reportingContext")
    void duskclient$setReportingContext(ReportingContext context);
}
