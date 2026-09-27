package dev.dusk.client.media.impl;

import com.mojang.datafixers.util.Pair;
import dev.dusk.client.mixin.media.SynchedEntityDataAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The server never sends a player their own entity, so a recording would
 * show everyone but the person who made it. Each tick this turns the local
 * player into the packets another client would have received about it:
 * spawn, movement, head turn, entity data, equipment and swings.
 */
final class SelfTracker {
    private @Nullable LocalPlayer player;
    private @Nullable Level level;
    private @Nullable PositionMoveRotation last;
    private boolean lastOnGround;
    private byte lastHead;
    private final Map<Integer, Object> data = new HashMap<>();
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private boolean lastSwinging;
    private int lastSwingTime;

    void tick(Recorder.Session session, LocalPlayer p) {
        if (p != player || p.level() != level) {
            player = p;
            level = p.level();
            spawn(session, p);
            return;
        }

        PositionMoveRotation now = PositionMoveRotation.of(p);
        if (last == null || !now.position().equals(last.position()) || now.yRot() != last.yRot() || now.xRot() != last.xRot()
                || p.onGround() != lastOnGround) {
            session.captureSelf(new ClientboundEntityPositionSyncPacket(p.getId(), now, p.onGround()));
            last = now;
            lastOnGround = p.onGround();
        }

        byte head = Mth.packDegrees(p.getYHeadRot());
        if (head != lastHead) {
            session.captureSelf(new ClientboundRotateHeadPacket(p, head));
            lastHead = head;
        }

        List<SynchedEntityData.DataValue<?>> changed = new ArrayList<>();
        for (SynchedEntityData.DataItem<?> item : items(p)) {
            if (item == null) continue;
            SynchedEntityData.DataValue<?> v = item.value();
            if (!same(data.get(v.id()), v.value())) {
                changed.add(v);
                data.put(v.id(), v.value());
            }
        }
        if (!changed.isEmpty()) session.captureSelf(new ClientboundSetEntityDataPacket(p.getId(), changed));

        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = p.getItemBySlot(slot);
            if (!ItemStack.matches(stack, equipment.getOrDefault(slot, ItemStack.EMPTY))) {
                ItemStack copy = stack.copy();
                slots.add(Pair.of(slot, copy));
                equipment.put(slot, copy);
            }
        }
        if (!slots.isEmpty()) session.captureSelf(new ClientboundSetEquipmentPacket(p.getId(), slots));

        if (p.swinging && (!lastSwinging || p.swingTime < lastSwingTime)) {
            session.captureSelf(new ClientboundAnimatePacket(p, p.swingingArm == InteractionHand.OFF_HAND ? 3 : 0));
        }
        lastSwinging = p.swinging;
        lastSwingTime = p.swingTime;
    }

    /** The whole entity, as a tracker sends it when a player comes into view. */
    private void spawn(Recorder.Session session, LocalPlayer p) {
        Vec3 pos = p.position();
        session.captureSelf(new ClientboundAddEntityPacket(p.getId(), p.getUUID(), pos.x, pos.y, pos.z, p.getXRot(), p.getYRot(),
                p.getType(), 0, p.getDeltaMovement(), p.getYHeadRot()));
        data.clear();
        List<SynchedEntityData.DataValue<?>> all = new ArrayList<>();
        for (SynchedEntityData.DataItem<?> item : items(p)) {
            if (item == null) continue;
            SynchedEntityData.DataValue<?> v = item.value();
            all.add(v);
            data.put(v.id(), v.value());
        }
        if (!all.isEmpty()) session.captureSelf(new ClientboundSetEntityDataPacket(p.getId(), all));
        equipment.clear();
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack copy = p.getItemBySlot(slot).copy();
            equipment.put(slot, copy);
            if (!copy.isEmpty()) slots.add(Pair.of(slot, copy));
        }
        if (!slots.isEmpty()) session.captureSelf(new ClientboundSetEquipmentPacket(p.getId(), slots));
        last = PositionMoveRotation.of(p);
        lastOnGround = p.onGround();
        lastHead = Mth.packDegrees(p.getYHeadRot());
        lastSwinging = p.swinging;
        lastSwingTime = p.swingTime;
    }

    private static SynchedEntityData.DataItem<?>[] items(LocalPlayer p) {
        return ((SynchedEntityDataAccessor) p.getEntityData()).duskclient$itemsById();
    }

    private static boolean same(@Nullable Object a, @Nullable Object b) {
        if (a instanceof ItemStack x && b instanceof ItemStack y) return ItemStack.matches(x, y);
        return Objects.equals(a, b);
    }
}
