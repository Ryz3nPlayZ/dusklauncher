package dev.dusk.client.mixin.host;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.social.HostedLogins;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A world opened to friends checks every joiner with Mojang, so a friend on an offline account
 * was turned away with an auth error. Friends whose Dusk account is offline skip that check and
 * join under their offline profile; everyone else is still checked.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class HostedOfflineLoginMixin {
    @Shadow
    String requestedUsername;

    @WrapOperation(method = "handleHello", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean duskclient$offlineFriends(MinecraftServer server, Operation<Boolean> original) {
        boolean checks = original.call(server);
        if (checks && server instanceof IntegratedServer hosted && hosted.isPublished()
                && requestedUsername != null && HostedLogins.mayJoinOffline(requestedUsername)) {
            return false;
        }
        return checks;
    }
}
