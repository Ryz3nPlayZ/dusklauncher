package dev.dusk.client.media.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.EntityType;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** The few recorder/replay calls whose names moved between versions. */
final class MediaCompat {
    private MediaCompat() {}

    static long chunk(int x, int z) { return ChunkPos.pack(x, z); }

    static long chunk(BlockPos pos) { return ChunkPos.pack(pos); }

    static long chunk(ChunkPos pos) { return pos.pack(); }

    static int motionId(ClientboundSetEntityMotionPacket p) { return p.id(); }

    static Vec3 motion(ClientboundSetEntityMotionPacket p) { return p.movement(); }

    /** Chat, or the action bar when {@code overlay}. */
    static void message(Player player, Component text, boolean overlay) {
        if (overlay) player.sendOverlayMessage(text);
        else player.sendSystemMessage(text);
    }

    static boolean isPlayer(EntityType<?> type) { return type == EntityType.PLAYER; }
}
