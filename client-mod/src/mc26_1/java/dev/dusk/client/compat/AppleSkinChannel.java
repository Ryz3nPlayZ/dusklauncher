package dev.dusk.client.compat;

import dev.dusk.client.modules.render.HungerInfo;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * AppleSkin's server sync (Unlicense, squeek502 — see NOTICE): a server
 * running AppleSkin sends exhaustion, saturation and the natural
 * regeneration rule on these channels. Only registered when AppleSkin
 * itself is not installed, since it registers the same ones.
 */
public final class AppleSkinChannel {
    private AppleSkinChannel() {}

    public record Exhaustion(float exhaustion) implements CustomPacketPayload {
        static final Type<Exhaustion> TYPE = new Type<>(Identifier.fromNamespaceAndPath("appleskin", "exhaustion"));
        static final StreamCodec<FriendlyByteBuf, Exhaustion> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeFloat(p.exhaustion), buf -> new Exhaustion(buf.readFloat()));

        @Override
        public Type<Exhaustion> type() { return TYPE; }
    }

    public record Saturation(float saturation) implements CustomPacketPayload {
        static final Type<Saturation> TYPE = new Type<>(Identifier.fromNamespaceAndPath("appleskin", "saturation"));
        static final StreamCodec<FriendlyByteBuf, Saturation> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeFloat(p.saturation), buf -> new Saturation(buf.readFloat()));

        @Override
        public Type<Saturation> type() { return TYPE; }
    }

    public record NaturalRegeneration(boolean naturalRegeneration) implements CustomPacketPayload {
        static final Type<NaturalRegeneration> TYPE = new Type<>(Identifier.fromNamespaceAndPath("appleskin", "natural_regeneration"));
        static final StreamCodec<FriendlyByteBuf, NaturalRegeneration> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.naturalRegeneration), buf -> new NaturalRegeneration(buf.readBoolean()));

        @Override
        public Type<NaturalRegeneration> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Exhaustion.TYPE, Exhaustion.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Saturation.TYPE, Saturation.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(NaturalRegeneration.TYPE, NaturalRegeneration.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Exhaustion.TYPE, (p, context) -> HungerInfo.onSyncedExhaustion(p.exhaustion()));
        ClientPlayNetworking.registerGlobalReceiver(Saturation.TYPE, (p, context) -> HungerInfo.onSyncedSaturation(p.saturation()));
        ClientPlayNetworking.registerGlobalReceiver(NaturalRegeneration.TYPE,
                (p, context) -> HungerInfo.onSyncedNaturalRegeneration(p.naturalRegeneration()));
    }
}
