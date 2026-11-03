package dev.dusk.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

/** Container clicks sent as the player's own; 26.1 renamed ClickType to ContainerInput. */
public final class Clicks {
    private Clicks() {}

    /** A plain left (0) or right (1) click on menu slot {@code slot}. */
    public static void pickup(int containerId, int slot, int button, Player player) {
        Minecraft.getInstance().gameMode.handleInventoryMouseClick(containerId, slot, button, ClickType.PICKUP, player);
    }

    /** Drops one item out of menu slot {@code slot}, as Q over it does. */
    public static void throwOne(int containerId, int slot, Player player) {
        Minecraft.getInstance().gameMode.handleInventoryMouseClick(containerId, slot, 0, ClickType.THROW, player);
    }
}
