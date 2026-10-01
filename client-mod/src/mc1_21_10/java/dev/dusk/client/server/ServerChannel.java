package dev.dusk.client.server;

import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

/**
 * Wire side of {@link ServerApi}: both channels carry a bare UTF-8 JSON body, no length prefix.
 * The payload types are public so an in-process server (singleplayer, LAN, tests) can use them.
 */
public final class ServerChannel {
    private ServerChannel() {}

    public record Rules(String json) implements CustomPacketPayload {
        public static final Type<Rules> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("dusk", "rules"));
        static final StreamCodec<FriendlyByteBuf, Rules> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.json.getBytes(StandardCharsets.UTF_8)),
                buf -> new Rules(readRest(buf)));

        @Override
        public Type<Rules> type() { return TYPE; }
    }

    public record Hello(String json) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("dusk", "hello"));
        static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.json.getBytes(StandardCharsets.UTF_8)),
                buf -> new Hello(readRest(buf)));

        @Override
        public Type<Hello> type() { return TYPE; }
    }

    static void register() {
        PayloadTypeRegistry.playS2C().register(Rules.TYPE, Rules.CODEC);
        PayloadTypeRegistry.playC2S().register(Hello.TYPE, Hello.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Rules.TYPE, (payload, context) -> ServerApi.applyRules(payload.json()));
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(Hello.TYPE.id())) client.execute(ServerApi::greetIfListening);
        });
    }

    static boolean canGreet() {
        return ClientPlayNetworking.canSend(Hello.TYPE);
    }

    static void greet(String json) {
        ClientPlayNetworking.send(new Hello(json));
    }

    static void notice(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(text), false);
    }

    private static String readRest(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
