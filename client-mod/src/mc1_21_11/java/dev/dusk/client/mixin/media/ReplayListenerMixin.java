package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.ReplayPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a replay the viewer is not the recorded player: it takes an id of its
 * own (so the recorded player's entity can exist beside it) and is always a
 * spectator, whatever mode the recording joined in.
 */
@Mixin(ClientPacketListener.class)
public abstract class ReplayListenerMixin {
    @ModifyArg(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;setId(I)V"))
    private int duskclient$viewerId(int id) {
        return ReplayPlayer.onLogin(id);
    }

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void duskclient$spectateLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        duskclient$spectate();
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void duskclient$spectateRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        duskclient$spectate();
    }

    @Unique
    private static void duskclient$spectate() {
        Minecraft mc = Minecraft.getInstance();
        if (ReplayPlayer.active() && mc.gameMode != null) mc.gameMode.setLocalMode(GameType.SPECTATOR);
    }
}
