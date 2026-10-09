package dev.dusk.client.modules.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.hud.Keys;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.KeySetting;
import dev.dusk.client.module.setting.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

/**
 * Slot locking, the inventory-mod staple: hover a slot of your own inventory
 * and press the lock key, and that slot can no longer be clicked, shift-clicked,
 * swapped with a number key or dropped with Q, so a sword or pearls cannot be
 * thrown or moved by accident. Purely client-side: it only holds back your
 * own clicks, it never sends any.
 */
public class SlotLock extends Module {
    public static final int OUTLINE = 0xFFFF5555, TINT = 0x30FF5555;
    private static SlotLock instance;

    private final KeySetting key = add(new KeySetting("slotlock", "Lock key (hover a slot)", InputConstants.KEY_K));
    /** Locked inventory indices (0–8 hotbar, 9–35 main, 36–39 armour, 40 offhand), comma-separated. */
    private final TextSetting locked = add(new TextSetting("locked", "Locked slots", "", 160));

    private String parsedFrom;
    private final BitSet slots = new BitSet(41);
    private boolean keyWasDown;

    public SlotLock() {
        super("slotlock", "Slot Lock", Category.MISC,
                "Hover a slot in your inventory and press K: it can't be moved or dropped until you unlock it.");
        instance = this;
    }

    @Nullable
    public static SlotLock active() {
        SlotLock m = instance;
        return m != null && m.enabled() ? m : null;
    }

    private BitSet slots() {
        String raw = locked.get();
        if (!raw.equals(parsedFrom)) {
            parsedFrom = raw;
            slots.clear();
            for (String part : raw.split(",")) {
                try {
                    int i = Integer.parseInt(part.trim());
                    if (i >= 0 && i <= 40) slots.set(i);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return slots;
    }

    public boolean isLocked(int inventoryIndex) {
        return inventoryIndex >= 0 && slots().get(inventoryIndex);
    }

    /** Whether {@code slot} is one of your own inventory's locked slots. */
    public boolean isLocked(@Nullable Slot slot) {
        return slot != null && slot.container instanceof Inventory && isLocked(slot.getContainerSlot());
    }

    private void toggle(int index) {
        BitSet s = slots();
        s.flip(index);
        StringBuilder out = new StringBuilder();
        for (int i = s.nextSetBit(0); i >= 0; i = s.nextSetBit(i + 1)) {
            if (out.length() > 0) out.append(',');
            out.append(i);
        }
        locked.set(out.toString());
        parsedFrom = locked.get();
        if (dev.dusk.client.DuskClient.modules() != null) dev.dusk.client.DuskClient.modules().saveConfig();
    }

    /**
     * Whether a container click must be held back: any click on a locked slot,
     * or a number-key / offhand swap that would pull a locked slot along.
     */
    public static boolean blocks(@Nullable Slot slot, int button, String clickType) {
        SlotLock m = active();
        if (m == null) return false;
        if (m.isLocked(slot)) return true;
        return clickType.equals("SWAP") && m.isLocked(button);
    }

    /** Q on a locked hotbar slot. */
    public static boolean blocksDrop(Player player) {
        SlotLock m = active();
        if (m == null) return false;
        var held = player.getMainHandItem();
        if (held.isEmpty()) return false; // nothing to drop; and every empty slot holds the same EMPTY, so the match below can't tell them apart
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i) == held) return m.isLocked(i);
        return false;
    }

    /** Polled every frame a container screen draws: the lock key toggles the hovered slot. */
    public static void poll(@Nullable Slot hovered) {
        SlotLock m = active();
        if (m == null) return;
        boolean down = Keys.physicallyDown(m.key.mapping());
        if (down && !m.keyWasDown && hovered != null && hovered.container instanceof Inventory
                && Minecraft.getInstance().player != null) {
            m.toggle(hovered.getContainerSlot());
        }
        m.keyWasDown = down;
    }
}
