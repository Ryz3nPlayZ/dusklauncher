package dev.dusk.client.media.impl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.configuration.ClientboundCodeOfConductPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;

import java.util.Optional;

/**
 * Sits between the decoder and the connection while a replay plays and
 * keeps the recording from acting on the viewer: nothing that disconnects,
 * opens screens, moves or re-modes the camera, changes the tick rate or
 * needs an answer from a server that isn't there. Signed chat becomes
 * plain chat, since the signatures can't be checked against a session
 * that no longer exists.
 */
final class ReplayFilter extends ChannelInboundHandlerAdapter {
    private boolean positionAllowed = true;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Packet<?> packet)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (packet instanceof BundlePacket<?>) {
            ctx.fireChannelRead(msg);
            return;
        }
        Packet<?> out = filter(packet);
        if (out != null) ctx.fireChannelRead(out);
    }

    private Packet<?> filter(Packet<?> p) {
        return switch (p) {
            case ClientboundLoginPacket x -> {
                positionAllowed = true;
                yield p;
            }
            case ClientboundRespawnPacket x -> {
                positionAllowed = true;
                yield p;
            }
            // the first one places the camera in the world; after that the camera is the viewer's
            case ClientboundPlayerPositionPacket x -> {
                boolean ok = positionAllowed;
                positionAllowed = false;
                yield ok ? p : null;
            }
            case ClientboundGameEventPacket g -> g.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE
                    || g.getEvent() == ClientboundGameEventPacket.WIN_GAME
                    || g.getEvent() == ClientboundGameEventPacket.DEMO_EVENT ? null : p;
            case ClientboundPlayerChatPacket c -> new ClientboundSystemChatPacket(c.chatType().decorate(
                    c.unsignedContent() != null ? c.unsignedContent() : Component.literal(c.body().content())), false);
            // the viewer would take damage or die with it; the HUD reads hunger from here while watching them
            case ClientboundSetHealthPacket h -> {
                ReplayPlayer.onHealth(h.getFood(), h.getSaturation());
                yield null;
            }
            case ClientboundExplodePacket e -> e.playerKnockback().isEmpty() ? p : new ClientboundExplodePacket(e.center(), e.radius(),
                    e.blockCount(), Optional.empty(), e.explosionParticle(), e.explosionSound(), e.blockParticles());
            default -> dropped(p) ? null : p;
        };
    }

    private static boolean dropped(Packet<?> p) {
        return p instanceof ClientboundDisconnectPacket || p instanceof ClientboundResourcePackPushPacket
                || p instanceof ClientboundResourcePackPopPacket || p instanceof ClientboundCustomPayloadPacket
                || p instanceof ClientboundTransferPacket || p instanceof ClientboundStoreCookiePacket
                || p instanceof ClientboundCookieRequestPacket || p instanceof ClientboundShowDialogPacket
                || p instanceof ClientboundClearDialogPacket || p instanceof ClientboundCodeOfConductPacket
                || p instanceof ClientboundKeepAlivePacket || p instanceof ClientboundPingPacket
                || p instanceof ClientboundLoginCompressionPacket || p instanceof ClientboundHelloPacket
                || p instanceof ClientboundCustomQueryPacket
                || p instanceof ClientboundDeleteChatPacket || p instanceof ClientboundPlayerAbilitiesPacket
                || p instanceof ClientboundSetCameraPacket
                || p instanceof ClientboundPlayerLookAtPacket || p instanceof ClientboundPlayerRotationPacket
                || p instanceof ClientboundMoveVehiclePacket || p instanceof ClientboundOpenScreenPacket
                || p instanceof ClientboundOpenBookPacket || p instanceof ClientboundOpenSignEditorPacket
                || p instanceof ClientboundMerchantOffersPacket || p instanceof ClientboundMountScreenOpenPacket
                || p instanceof ClientboundTickingStatePacket || p instanceof ClientboundTickingStepPacket
                || p instanceof ClientboundUpdateAdvancementsPacket || p instanceof ClientboundRecipeBookAddPacket
                || p instanceof ClientboundRecipeBookRemovePacket || p instanceof ClientboundRecipeBookSettingsPacket
                || p instanceof ClientboundAwardStatsPacket;
    }
}
