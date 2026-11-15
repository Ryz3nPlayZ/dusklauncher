package dev.dusk.client.modules.misc;

import dev.dusk.client.mixin.GuiContainerAccessor;
import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.inventory.SlotFurnaceOutput;
import net.minecraft.inventory.SlotMerchantResult;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Mouse Tweaks for container screens:
 * <ul>
 *   <li>right-drag with a stack drops one item in every slot you pass over;</li>
 *   <li>left-drag after picking up a stack gathers matching items from the
 *       slots you pass over (with shift held, it shift-clicks them instead);</li>
 *   <li>shift + left-drag with nothing held shift-clicks every slot you pass;</li>
 *   <li>the scroll wheel over a stack moves items to or from the other
 *       inventory.</li>
 * </ul>
 * Everything is ordinary slot clicks, the same packets a player clicking by
 * hand would send. The logic follows Mouse Tweaks by YaLTeR (BSD-3-Clause,
 * see NOTICE), rewritten against 1.8.9's GuiContainer.
 */
public class MouseTweaks extends Module {
    private static MouseTweaks instance;

    private final BoolSetting rmbTweak = add(new BoolSetting("rmbTweak",
            "Right-drag drops one item per slot", true), "Dragging");
    private final BoolSetting lmbWithItem = add(new BoolSetting("lmbTweakWithItem",
            "Left-drag gathers matching items", true), "Dragging");
    private final BoolSetting lmbWithoutItem = add(new BoolSetting("lmbTweakWithoutItem",
            "Shift + left-drag moves every stack", true), "Dragging");
    private final BoolSetting wheelTweak = add(new BoolSetting("wheelTweak",
            "Scroll over a stack to move items", true), "Scrolling");
    private final ChoiceSetting direction = add(new ChoiceSetting("wheelScrollDirection", "Scroll direction",
            "NORMAL", "NORMAL", "INVERTED", "POSITION_AWARE", "POSITION_AWARE_INVERTED").labelled(o -> {
                switch (o) {
                    case "INVERTED": return "Up sends, down pulls";
                    case "POSITION_AWARE": return "Toward the other side sends";
                    case "POSITION_AWARE_INVERTED": return "Away from the other side sends";
                    default: return "Down sends, up pulls";
                }
            }), "Scrolling");
    private final ChoiceSetting searchOrder = add(new ChoiceSetting("wheelSearchOrder", "Pull from",
            "LAST_TO_FIRST", "LAST_TO_FIRST", "FIRST_TO_LAST").labelled(o ->
            "FIRST_TO_LAST".equals(o) ? "First slot first" : "Last slot first"), "Scrolling");
    private final ChoiceSetting scaling = add(new ChoiceSetting("scrollItemScaling", "Items per notch",
            "PROPORTIONAL", "PROPORTIONAL", "ALWAYS_ONE").labelled(o ->
            "ALWAYS_ONE".equals(o) ? "Always one" : "Follow scroll speed"), "Scrolling");

    private GuiScreen screen;
    private Slot oldSlot;
    private double accumulated;
    private boolean canLmbDrag, canRmbDrag, rmbLeftOriginal;
    private int held = -1;

    public MouseTweaks() {
        super("mousetweaks", "Mouse Tweaks", Category.MISC,
                "Drag and scroll to move items around chests and your inventory faster.");
        instance = this;
    }

    /**
     * One LWJGL mouse event about to reach {@code gui}. Runs before vanilla
     * sees it, like Mouse Tweaks' hooks at the head of the click handlers.
     * True when the event was spent and vanilla shouldn't see it.
     */
    public static boolean onMouseInput(GuiScreen gui) {
        MouseTweaks m = instance;
        if (m == null || !m.enabled()) return false;
        m.update(gui);
        Minecraft mc = Minecraft.getMinecraft();
        if (!(gui instanceof GuiContainer) || mc.thePlayer == null || mc.displayWidth <= 0 || mc.displayHeight <= 0) {
            return false;
        }
        GuiContainer container = (GuiContainer) gui;
        int x = dev.dusk.client.util.HiDpi.mouse(Mouse.getEventX()) * gui.width / mc.displayWidth;
        int y = gui.height - dev.dusk.client.util.HiDpi.mouse(Mouse.getEventY()) * gui.height / mc.displayHeight - 1;
        int button = Mouse.getEventButton();
        if (button != -1) {
            if (Mouse.getEventButtonState()) {
                m.held = button;
                m.pressed(container, x, y, button);
            } else {
                if (button == m.held) m.held = -1;
                m.released(button);
            }
            return false;
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            // LWJGL 2 reports 120 a notch on most mice; macOS trackpads send small steps
            double amount = Math.abs(wheel) >= 60 ? wheel / 120.0 : Math.signum(wheel);
            return m.scrolled(container, x, y, amount);
        }
        if (m.held == 0 || m.held == 1) m.dragged(container, x, y, m.held);
        return false;
    }

    private void update(GuiScreen gui) {
        if (gui == screen) return;
        screen = gui;
        oldSlot = null;
        accumulated = 0;
        canLmbDrag = false;
        canRmbDrag = false;
        rmbLeftOriginal = false;
        held = -1;
    }

    private void pressed(GuiContainer gui, int x, int y, int button) {
        oldSlot = slotAt(gui, x, y);
        ItemStack carried = carried();
        if (button == 0) {
            // with a stack on the mouse vanilla starts its own spread-drag; leave that alone
            if (empty(carried)) canLmbDrag = true;
        } else if (button == 1) {
            if (empty(carried) || !rmbTweak.get()) return;
            canRmbDrag = true;
            rmbLeftOriginal = false;
        }
    }

    private void released(int button) {
        if (button == 0) canLmbDrag = false;
        else if (button == 1) canRmbDrag = false;
    }

    private void dragged(GuiContainer gui, int x, int y, int button) {
        Slot slot = slotAt(gui, x, y);
        if (slot == oldSlot) return;
        ItemStack carried = carried();

        if (canRmbDrag && button == 1 && !rmbLeftOriginal) {
            // the first time we leave the slot the drag began on: stop vanilla's
            // spread-drag and drop an item where it started
            rmbLeftOriginal = true;
            GuiContainerAccessor acc = (GuiContainerAccessor) gui;
            acc.dusk$setIgnoreMouseUp(true);
            if (acc.dusk$dragSplitting() && acc.dusk$dragSplittingButton() == 1) acc.dusk$setDragSplitting(false);
            rmbMaybeClick(gui, oldSlot, carried);
        }

        oldSlot = slot;
        if (slot == null || ignored(gui, slot)) return;

        if (button == 0) {
            if (!canLmbDrag) return;
            ItemStack stack = slot.getStack();
            if (empty(stack)) return;
            boolean shift = GuiScreen.isShiftKeyDown();
            if (empty(carried)) {
                if (!lmbWithoutItem.get() || !shift) return;
                click(gui, slot, 0, true);
            } else {
                if (!lmbWithItem.get() || !compatible(stack, carried)) return;
                if (shift) {
                    click(gui, slot, 0, true);
                } else {
                    // merge into the slot and pick the lot back up; skip it if it
                    // wouldn't all fit back on the mouse
                    if (carried.stackSize + stack.stackSize > carried.getMaxStackSize()) return;
                    click(gui, slot, 0, false);
                    // an output slot hands its items straight to the mouse
                    if (!craftingOutput(slot)) click(gui, slot, 0, false);
                }
            }
        } else if (button == 1) {
            if (canRmbDrag) rmbMaybeClick(gui, slot, carried);
        }
    }

    private void rmbMaybeClick(GuiContainer gui, Slot slot, ItemStack carried) {
        if (slot == null || empty(carried) || ignored(gui, slot) || craftingOutput(slot)) return;
        ItemStack stack = slot.getStack();
        if (!compatible(stack, carried)) return;
        if (!empty(stack) && stack.stackSize >= max(slot, stack)) return;
        if (empty(stack) && !slot.isItemValid(carried)) return;
        click(gui, slot, 1, false);
    }

    private boolean scrolled(GuiContainer gui, int x, int y, double amount) {
        if (!wheelTweak.get()) return false;
        Slot selected = slotAt(gui, x, y);
        if (selected == null || ignored(gui, selected)) return false;

        // Over a slot the wheel always belongs to us, even when nothing moves,
        // so it doesn't suddenly start doing something else when a stack runs out.
        double scaled = scaling.is("ALWAYS_ONE") ? Math.signum(amount) : amount;
        if (accumulated != 0 && Math.signum(scaled) != Math.signum(accumulated)) accumulated = 0;
        accumulated += scaled;
        int delta = (int) accumulated;
        accumulated -= delta;
        if (delta == 0) return true;

        List<Slot> slots = gui.inventorySlots.inventorySlots;
        int count = Math.abs(delta);
        boolean push = delta < 0;
        String dir = direction.get();
        if (dir.startsWith("POSITION_AWARE") && otherInventoryIsAbove(selected, slots)) push = !push;
        if (dir.endsWith("INVERTED")) push = !push;

        ItemStack selectedStack = selected.getStack();
        if (empty(selectedStack)) return true;
        ItemStack carried = carried();

        if (craftingOutput(selected)) {
            if (!compatible(selectedStack, carried)) return true;
            if (empty(carried)) {
                if (!push) return true; // nothing can go into an output slot
                while (count-- > 0) {
                    // a craft comes out as a whole batch, so the whole batch needs a home
                    List<Slot> targets = findPushSlots(gui, slots, selected, selected.getStack(), true);
                    if (targets == null) break;
                    click(gui, selected, 0, false);
                    for (int i = 0; i < targets.size(); i++) {
                        Slot t = targets.get(i);
                        if (i == targets.size() - 1) {
                            click(gui, t, 0, false);
                        } else {
                            ItemStack ts = t.getStack();
                            int times = max(t, carried()) - (empty(ts) ? 0 : ts.stackSize);
                            while (times-- > 0) click(gui, t, 1, false);
                        }
                    }
                    if (empty(selected.getStack())) break;
                }
            } else {
                while (count-- > 0) click(gui, selected, 0, false);
            }
            return true;
        }

        // holding the same item makes any click merge instead of pick up
        if (!empty(carried) && compatible(selectedStack, carried)) return true;

        if (push) {
            if (!empty(carried) && !selected.isItemValid(carried)) return true;
            count = Math.min(count, selectedStack.stackSize);
            List<Slot> targets = findPushSlots(gui, slots, selected, selectedStack, false, count);
            if (targets.isEmpty()) return true;

            boolean hadCarried = !empty(carried);
            // right click picks up half and keeps furnace progress; left click
            // when we want the whole stack anyway
            int pickUp = empty(carried) && selectedStack.stackSize <= count ? 0 : 1;
            click(gui, selected, pickUp, false);

            ItemStack picked = carried();
            if (empty(picked)) return true;
            count = Math.min(count, picked.stackSize);
            for (Slot t : targets) {
                ItemStack ts = t.getStack();
                int times = max(t, picked) - (empty(ts) ? 0 : ts.stackSize);
                times = Math.min(times, count);
                count -= times;
                while (times-- > 0) click(gui, t, 1, false);
            }

            boolean leftover = !empty(carried());
            if (hadCarried || leftover) click(gui, selected, hadCarried && leftover ? 1 : 0, false);
            return true;
        }

        // pulling: never more than the slot can still take
        int room = max(selected, selectedStack) - selectedStack.stackSize;
        count = Math.min(count, room);
        while (count > 0) {
            Slot target = findPullSlot(gui, slots, selected);
            if (target == null) break;
            ItemStack targetStack = target.getStack();
            int inTarget = targetStack.stackSize;

            if (craftingOutput(target)) {
                // one notch is one craft, and only when the whole batch fits
                if (room < inTarget) break;
                room -= inTarget;
                count = Math.min(count - 1, room);
                if (!empty(carried) && !selected.isItemValid(carried)) break;
                click(gui, selected, 0, false);
                click(gui, target, 0, false);
                click(gui, selected, 0, false);
                continue;
            }

            boolean hadCarried = !empty(carried);
            if (hadCarried && !target.isItemValid(carried)) break;
            int pickUp = empty(carried) && inTarget == 1 ? 0 : 1;
            click(gui, target, pickUp, false);

            ItemStack picked = carried();
            if (empty(picked)) break;
            int move = Math.min(picked.stackSize, count);
            if (move == picked.stackSize) {
                click(gui, selected, 0, false);
            } else {
                for (int i = 0; i < move; i++) click(gui, selected, 1, false);
            }
            room -= move;
            count -= move;

            boolean leftover = !empty(carried());
            if (hadCarried || leftover) click(gui, target, hadCarried && leftover ? 1 : 0, false);
        }
        return true;
    }

    /** Whether most slots of the other inventory sit above {@code selected} on screen. */
    private static boolean otherInventoryIsAbove(Slot selected, List<Slot> slots) {
        boolean selectedInPlayer = inPlayerInventory(selected);
        int above = 0, below = 0;
        for (Slot s : slots) {
            if (inPlayerInventory(s) != selectedInPlayer) {
                if (s.yDisplayPosition < selected.yDisplayPosition) above++;
                else below++;
            }
        }
        return above > below;
    }

    private Slot findPullSlot(GuiContainer gui, List<Slot> slots, Slot selected) {
        boolean firstToLast = searchOrder.is("FIRST_TO_LAST");
        int start = firstToLast ? 0 : slots.size() - 1, end = firstToLast ? slots.size() : -1, step = firstToLast ? 1 : -1;
        ItemStack selectedStack = selected.getStack();
        boolean wantPlayer = !inPlayerInventory(selected);
        for (int i = start; i != end; i += step) {
            Slot s = slots.get(i);
            if (ignored(gui, s) || inPlayerInventory(s) != wantPlayer) continue;
            ItemStack stack = s.getStack();
            if (empty(stack) || !compatible(selectedStack, stack)) continue;
            return s;
        }
        return null;
    }

    private List<Slot> findPushSlots(GuiContainer gui, List<Slot> slots, Slot selected, ItemStack item, boolean all) {
        return findPushSlots(gui, slots, selected, item, all, item.stackSize);
    }

    /**
     * Slots on the other side that can take {@code count} of {@code item}:
     * matching stacks with room first, then empty slots that accept it. Null
     * when {@code all} is set and not everything fits.
     */
    private List<Slot> findPushSlots(GuiContainer gui, List<Slot> slots, Slot selected, ItemStack item, boolean all, int count) {
        boolean wantPlayer = !inPlayerInventory(selected);
        List<Slot> out = new ArrayList<Slot>();
        List<Slot> emptySlots = new ArrayList<Slot>();
        for (int i = 0; i < slots.size() && count > 0; i++) {
            Slot s = slots.get(i);
            if (ignored(gui, s) || inPlayerInventory(s) != wantPlayer || craftingOutput(s)) continue;
            ItemStack stack = s.getStack();
            if (empty(stack)) {
                if (s.isItemValid(item)) emptySlots.add(s);
            } else if (compatible(item, stack) && stack.stackSize < max(s, stack)) {
                out.add(s);
                count -= Math.min(count, max(s, stack) - stack.stackSize);
            }
        }
        for (int i = 0; i < emptySlots.size() && count > 0; i++) {
            Slot s = emptySlots.get(i);
            out.add(s);
            count -= Math.min(count, max(s, item));
        }
        return all && count > 0 ? null : out;
    }

    private static Slot slotAt(GuiContainer gui, int x, int y) {
        return ((GuiContainerAccessor) gui).dusk$slotAt(x, y);
    }

    private static void click(GuiContainer gui, Slot slot, int button, boolean shift) {
        ((GuiContainerAccessor) gui).dusk$click(slot, slot.slotNumber, button, shift ? 1 : 0);
    }

    /** The creative palette and its trash slot aren't real inventory; only the player's own slots count there. */
    private static boolean ignored(GuiContainer gui, Slot slot) {
        return gui instanceof GuiContainerCreative && !inPlayerInventory(slot);
    }

    private static boolean craftingOutput(Slot slot) {
        return slot instanceof SlotCrafting || slot instanceof SlotFurnaceOutput || slot instanceof SlotMerchantResult;
    }

    private static boolean inPlayerInventory(Slot slot) {
        return slot.inventory == Minecraft.getMinecraft().thePlayer.inventory;
    }

    private static ItemStack carried() {
        return Minecraft.getMinecraft().thePlayer.inventory.getItemStack();
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.stackSize <= 0;
    }

    /** Empty stacks go with anything; otherwise same item, damage and NBT. */
    private static boolean compatible(ItemStack a, ItemStack b) {
        return empty(a) || empty(b) || (a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b));
    }

    private static int max(Slot slot, ItemStack stack) {
        return Math.min(stack.getMaxStackSize(), slot.getItemStackLimit(stack));
    }
}
