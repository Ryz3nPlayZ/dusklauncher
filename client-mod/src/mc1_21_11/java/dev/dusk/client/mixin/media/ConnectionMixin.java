package dev.dusk.client.mixin.media;

import dev.dusk.client.media.impl.Recorder;
import dev.dusk.client.media.impl.ReplayPlayer;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands the recorder every packet a server sends, with the protocol it was decoded in. */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Unique
    private volatile ProtocolInfo<?> duskclient$inbound;

    @Shadow
    public abstract PacketFlow getReceiving();

    @Inject(method = "setupInboundProtocol", at = @At("HEAD"))
    private <T extends PacketListener> void duskclient$trackProtocol(ProtocolInfo<T> protocol, T listener, CallbackInfo ci) {
        duskclient$inbound = protocol;
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void duskclient$record(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (getReceiving() != PacketFlow.CLIENTBOUND || ReplayPlayer.isReplayConnection(self)) return;
        Recorder.onPacket(self, duskclient$inbound, packet);
    }
}
