package dev.dusk.client.modules.render;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.TooltipImage;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.gui.Font;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The container preview utility clients put in tooltips (the idea of
 * Meteor's BetterTooltips; GPL, so written from how it behaves, not its
 * code): a shulker box, or any item
 * carrying a container's contents, shows them as a grid of slots in place of
 * vanilla's "Diamond x3 … and 4 more" list.
 */
public class ContainerPreview extends Module {
    private static final String ALWAYS = "Always", SHIFT = "While holding Shift";
    private static final int SIZE = 27, COLUMNS = 9, SLOT = 18;

    private final ChoiceSetting show = add(new ChoiceSetting("show", "Show", ALWAYS, ALWAYS, SHIFT));
    private final BoolSetting tint = add(new BoolSetting("tint", "Tint by box colour", true));
    private final BoolSetting hideList = add(new BoolSetting("hideList", "Hide vanilla item list", true));

    public ContainerPreview() {
        super("containerpreview", "Container Preview", Category.RENDER,
                "Shows what's inside shulker boxes as a grid in their tooltip.");
        setEnabled(true);
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (enabled()) addTooltip(stack, lines);
        });
    }

    private void addTooltip(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty() || !shouldShow()) return;
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return;
        NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        contents.copyInto(items);
        int last = -1;
        for (int i = 0; i < SIZE; i++) if (!items.get(i).isEmpty()) last = i;
        if (last < 0) return;
        int color = 0;
        if (tint.get() && stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock box) {
            DyeColor dye = box.getColor();
            if (dye != null) color = dye.getTextureDiffuseColor() & 0xFFFFFF;
        }
        try {
            if (hideList.get()) lines.removeIf(ContainerPreview::isVanillaListLine);
            lines.add(Math.min(1, lines.size()), new TooltipImage.Line(new Grid(items, last / COLUMNS + 1, color)));
        } catch (UnsupportedOperationException ignored) {
            // the list is immutable, e.g. the item hides its tooltip
        }
    }

    private static boolean isVanillaListLine(Component line) {
        return line.getContents() instanceof TranslatableContents t
                && (t.getKey().equals("container.shulkerBox.itemCount") || t.getKey().equals("container.shulkerBox.more"));
    }

    private boolean shouldShow() {
        if (show.is(ALWAYS)) return true;
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
    }

    /** The slots, row by row, as far as the last filled one. */
    private record Grid(List<ItemStack> items, int rows, int rgb) implements TooltipImage {
        @Override
        public int height() {
            return rows * SLOT + 4;
        }

        @Override
        public int width(Font font) {
            return COLUMNS * SLOT;
        }

        @Override
        public void draw(Canvas c, int x, int y) {
            y += 1;
            int slot = rgb == 0 ? 0x30FFFFFF : 0x60000000 | rgb;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < COLUMNS; col++) {
                    int sx = x + col * SLOT, sy = y + row * SLOT;
                    c.fill(sx, sy, sx + SLOT - 1, sy + SLOT - 1, slot);
                    ItemStack stack = items.get(row * COLUMNS + col);
                    if (stack.isEmpty()) continue;
                    c.item(stack, sx, sy);
                    c.itemDecorations(stack, sx, sy);
                }
            }
        }
    }
}
