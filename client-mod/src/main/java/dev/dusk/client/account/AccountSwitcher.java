package dev.dusk.client.account;

import com.mojang.authlib.minecraft.UserApiService;
import dev.dusk.client.compat.AccountCompat;
import dev.dusk.client.compat.SkinCompat;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.mixin.account.MinecraftAccountAccessor;
import dev.dusk.client.social.SocialNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Switches the game to another of the launcher's accounts without a
 * restart: the launcher hands over a login, and everything the game built
 * from the old one at startup (profile and skin, chat signing keys, social
 * and telemetry services, our own sign-ins) is built again. Title screen
 * only: a world or server keeps the account it joined with.
 */
public final class AccountSwitcher {
    private static volatile boolean busy;

    private AccountSwitcher() {}

    public static boolean busy() {
        return busy;
    }

    /** Switch to the saved account {@code uuid}; {@code done} gets null or an error, on the main thread. */
    public static void switchTo(String uuid, Consumer<@Nullable String> done) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            done.accept("Leave the world to switch accounts.");
            return;
        }
        if (busy) return;
        busy = true;
        Thread t = new Thread(() -> {
            try {
                LauncherLink.Login login = LauncherLink.switchTo(uuid);
                UUID id = parse(login.uuid());
                User user = AccountCompat.user(login.username(), id, login.accessToken(), login.xuid());
                UserApiService api = AccountCompat.api(mc, login.accessToken());
                UserApiService.UserProperties props;
                try {
                    props = api.fetchProperties();
                } catch (Exception e) {
                    props = UserApiService.OFFLINE_PROPERTIES;
                }
                Object profile;
                try {
                    profile = AccountCompat.profile(mc, id);
                } catch (Exception e) {
                    profile = null;
                }
                UserApiService.UserProperties p = props;
                Object prof = profile;
                mc.execute(() -> {
                    try {
                        apply(mc, user, api, p, prof, login.accessToken());
                        done.accept(null);
                    } catch (RuntimeException e) {
                        done.accept("Couldn't switch: " + e.getMessage());
                    } finally {
                        busy = false;
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mc.execute(() -> {
                    busy = false;
                    done.accept(msg);
                });
            }
        }, "duskclient-account-switch");
        t.setDaemon(true);
        t.start();
    }

    private static void apply(Minecraft mc, User user, UserApiService api, UserApiService.UserProperties props,
                              @Nullable Object profile, String token) {
        MinecraftAccountAccessor acc = (MinecraftAccountAccessor) mc;
        acc.duskclient$setUser(user);
        acc.duskclient$setProfileFuture(CompletableFuture.completedFuture(profile));
        acc.duskclient$setUserApiService(api);
        acc.duskclient$setUserPropertiesFuture(CompletableFuture.completedFuture(props));
        acc.duskclient$setPlayerSocialManager(AccountCompat.social(mc, api, token));
        ClientTelemetryManager old = mc.getTelemetryManager();
        acc.duskclient$setTelemetryManager(new ClientTelemetryManager(mc, api, user));
        old.close();
        acc.duskclient$setProfileKeyPairManager(ProfileKeyPairManager.create(api, user, mc.gameDirectory.toPath()));
        acc.duskclient$setReportingContext(ReportingContext.create(ReportEnvironment.local(), api));

        DuskAccount.forget();
        SkinCompat.reset();
        CosmeticsManager.clear();
        SocialNotifier.forget();
    }

    private static UUID parse(String uuid) {
        String u = uuid.replace("-", "");
        if (u.length() != 32) throw new IllegalArgumentException("bad uuid from the launcher: " + uuid);
        return UUID.fromString(u.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }
}
