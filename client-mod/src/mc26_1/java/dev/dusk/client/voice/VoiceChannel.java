package dev.dusk.client.voice;

import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Wire side of {@link SvcClient}: Simple Voice Chat's plugin channels
 * (voicechat:*), each carried as raw bytes that SvcClient reads itself.
 * Not registered when Simple Voice Chat is installed, since it owns them.
 */
public final class VoiceChannel {
    private VoiceChannel() {}

    private static final Map<String, CustomPacketPayload.Type<Raw>> TYPES = new HashMap<>();

    public record Raw(Type<Raw> type, byte[] data) implements CustomPacketPayload {}

    private static CustomPacketPayload.Type<Raw> type(String path) {
        return TYPES.computeIfAbsent(path, p -> new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("voicechat", p)));
    }

    private static StreamCodec<FriendlyByteBuf, Raw> codec(CustomPacketPayload.Type<Raw> type) {
        return StreamCodec.of((buf, p) -> buf.writeBytes(p.data()), buf -> {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new Raw(type, bytes);
        });
    }

    /** Server to client channels go to {@code receive} (on the network thread); {@code serverSupports} hears when the server lists ours. */
    static void register(String[] toClient, String[] toServer, BiConsumer<String, byte[]> receive, Runnable serverSupports) {
        for (String path : toClient) {
            var t = type(path);
            PayloadTypeRegistry.clientboundPlay().register(t, codec(t));
            ClientPlayNetworking.registerGlobalReceiver(t, (payload, context) -> receive.accept(path, payload.data()));
        }
        for (String path : toServer) {
            var t = type(path);
            PayloadTypeRegistry.serverboundPlay().register(t, codec(t));
        }
        ServerboundPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(type(toServer[0]).id())) client.execute(serverSupports);
        });
    }

    static boolean canSend(String path) {
        return ClientPlayNetworking.canSend(type(path));
    }

    static void send(String path, byte[] data) {
        ClientPlayNetworking.send(new Raw(type(path), data));
    }
}
