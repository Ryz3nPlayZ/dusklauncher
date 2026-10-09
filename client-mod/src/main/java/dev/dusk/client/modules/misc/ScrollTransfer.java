package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.Clicks;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The scroll-wheel half of Mouse Tweaks: over a slot in a container screen,
 * each notch down sends one item of that stack to the other side (chest ↔
 * your inventory; in your own inventory, hotbar ↔ the rest), each notch up
 * pulls one matching item back onto it. It is done with ordinary clicks —
 * pick the stack up, drop one, put the rest back — so servers see nothing
 * a player couldn't do by hand. Written for Dusk; no Mouse Tweaks code.
 */
public class ScrollTransfer extends Module {
    private static final int NONE = -1, PLAYER = 0, OTHER = 1, HOTBAR = 2, MAIN = 3;
    private static ScrollTransfer instance;

    private final BoolSetting invert = add(new BoolSetting("invert", "Scroll up sends, down pulls", false));

    /** trackpads scroll in fractions of a notch: one item per whole notch */
    private double pending;

    public ScrollTransfer() {
        super("scrolltransfer", "Scroll Transfer", Category.MISC,
                "Scroll over a stack in a chest or your inventory to move its items one at a time.");
        instance = this;
    }

    @Nullable
    public static ScrollTransfer active() {
        ScrollTransfer m = instance;
        return m != null && m.enabled() ? m : null;
    }

    /** One wheel event over {@code hovered}; true when it was spent moving items. */
    public static boolean scroll(@Nullable Slot hovered, AbstractContainerMenu menu, double amount) {
        ScrollTransfer m = active();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (m == null || hovered == null || player == null || mc.gameMode == null || !menu.getCarried().isEmpty()) return false;
        if (section(menu, hovered) == NONE) return false;
        if (Math.signum(amount) != Math.signum(m.pending)) m.pending = 0;
        m.pending += amount;
        while (Math.abs(m.pending) >= 1) {
            boolean down = m.pending < 0;
            m.pending -= Math.signum(m.pending);
            boolean send = down != m.invert.get();
            if (!(send ? m.send(menu, hovered, player) : m.pull(menu, hovered, player))) {
                m.pending = 0;
                break;
            }
        }
        return true;
    }

    /** One item of {@code from} onto the other side. */
    private boolean send(AbstractContainerMenu menu, Slot from, LocalPlayer player) {
        ItemStack stack = from.getItem();
        // the rest has to be able to go back where it came from
        if (stack.isEmpty() || locked(from) || !from.mayPickup(player) || !from.mayPlace(stack)) return false;
        Slot to = target(menu, opposite(section(menu, from)), stack);
        if (to == null) return false;
        moveOne(menu, from, to, stack.getCount() > 1, player);
        return true;
    }

    /** One matching item from the other side onto {@code to}. */
    private boolean pull(AbstractContainerMenu menu, Slot to, LocalPlayer player) {
        ItemStack stack = to.getItem();
        if (stack.isEmpty() || locked(to) || !to.mayPlace(stack) || stack.getCount() >= to.getMaxStackSize(stack)) return false;
        int side = opposite(section(menu, to));
        Slot from = null;
        for (Slot s : menu.slots) {
            if (section(menu, s) != side || locked(s) || !s.mayPickup(player)) continue;
            ItemStack there = s.getItem();
            if (!there.isEmpty() && ItemStack.isSameItemSameComponents(there, stack) && s.mayPlace(there)) {
                // a partial stack first, so full ones stay whole
                if (from == null || there.getCount() < from.getItem().getCount()) from = s;
            }
        }
        if (from == null) return false;
        moveOne(menu, from, to, from.getItem().getCount() > 1, player);
        return true;
    }

    private static void moveOne(AbstractContainerMenu menu, Slot from, Slot to, boolean restGoesBack, LocalPlayer player) {
        Clicks.pickup(menu.containerId, from.index, 0, player);
        Clicks.pickup(menu.containerId, to.index, 1, player);
        if (restGoesBack) Clicks.pickup(menu.containerId, from.index, 0, player);
    }

    /** Where one more of {@code stack} fits on {@code side}: a stack of it with room, else an empty slot. */
    @Nullable
    private static Slot target(AbstractContainerMenu menu, int side, ItemStack stack) {
        Slot empty = null;
        for (Slot s : menu.slots) {
            if (section(menu, s) != side || locked(s) || !s.mayPlace(stack)) continue;
            ItemStack there = s.getItem();
            if (there.isEmpty()) {
                if (empty == null) empty = s;
            } else if (ItemStack.isSameItemSameComponents(there, stack) && there.getCount() < s.getMaxStackSize(stack)) {
                return s;
            }
        }
        return empty;
    }

    private static int section(AbstractContainerMenu menu, Slot s) {
        boolean mine = s.container instanceof Inventory;
        if (menu instanceof InventoryMenu) {
            // your own screen: hotbar and the 27 above it; armour, offhand and crafting stay out
            if (!mine) return NONE;
            int i = s.getContainerSlot();
            return i < 9 ? HOTBAR : i < 36 ? MAIN : NONE;
        }
        if (mine) return s.getContainerSlot() < 36 ? PLAYER : NONE;
        return OTHER;
    }

    private static int opposite(int section) {
        return switch (section) {
            case PLAYER -> OTHER;
            case OTHER -> PLAYER;
            case HOTBAR -> MAIN;
            case MAIN -> HOTBAR;
            default -> NONE;
        };
    }

    private static boolean locked(Slot s) {
        SlotLock lock = SlotLock.active();
        return lock != null && lock.isLocked(s);
    }
}
