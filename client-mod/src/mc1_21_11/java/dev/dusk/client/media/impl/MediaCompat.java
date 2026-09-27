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

    static long chunk(int x, int z) { return ChunkPos.asLong(x, z); }

    static long chunk(BlockPos pos) { return ChunkPos.asLong(pos); }

    static long chunk(ChunkPos pos) { return pos.toLong(); }

    static int motionId(ClientboundSetEntityMotionPacket p) { return p.getId(); }

    static Vec3 motion(ClientboundSetEntityMotionPacket p) { return p.getMovement(); }

    /** Chat, or the action bar when {@code overlay}. */
    static void message(Player player, Component text, boolean overlay) {
        player.displayClientMessage(text, overlay);
    }

    static boolean isPlayer(EntityType<?> type) { return type == EntityType.PLAYER; }
}
