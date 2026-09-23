package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Flex-HUD's inventory display: the three main rows on the vanilla panel. */
public class InventoryDisplay extends HudElement {
    private static final String INVENTORY_TEXTURE = "minecraft:textures/gui/container/inventory.png";
    private static final int NUM_ROWS = 3;
    private static final int NUM_COLS = 9;
    private static final int ITEM_SIZE = 18;
    private static final int PADDING = 2;

    private final IntSetting backgroundOpacity =
            add(new IntSetting("backgroundOpacity", "Panel opacity", 255, 0, 255));

    public InventoryDisplay() {
        super("inventorydisplay", "Inventory Display", "Your inventory's main rows, drawn on the HUD.");
        setPosition(10, 100);
    }

    @Override
    public int width(HudContext ctx) {
        return PADDING + ITEM_SIZE * NUM_COLS;
    }

    @Override
    public int height(HudContext ctx) {
        return PADDING + ITEM_SIZE * NUM_ROWS;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        int opacity = backgroundOpacity.get();
        if (opacity != 0) {
            c.blit(INVENTORY_TEXTURE, 0, 0, 6, 82, 164, 56, 256, 256, (opacity << 24) | 0xFFFFFF);
        }

        if (ctx.player() == null) return;

        Inventory inventory = ctx.player().getInventory();
        for (int row = 0; row < NUM_ROWS; row++) {
            for (int col = 0; col < NUM_COLS; col++) {
                int slot = 9 + NUM_COLS * row + col;
                if (inventory.getContainerSize() <= slot) continue;
                ItemStack stack = inventory.getItem(slot);
                int x = PADDING + col * ITEM_SIZE;
                int y = PADDING + row * ITEM_SIZE;
                c.item(stack, x, y);
                c.itemDecorations(stack, x, y);
            }
        }
    }
}
