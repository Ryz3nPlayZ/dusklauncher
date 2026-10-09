package dev.dusk.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

/** Container clicks sent as the player's own; 26.1 renamed ClickType to ContainerInput. */
public final class Clicks {
    private Clicks() {}

    /** A plain left (0) or right (1) click on menu slot {@code slot}. */
    public static void pickup(int containerId, int slot, int button, Player player) {
        Minecraft.getInstance().gameMode.handleContainerInput(containerId, slot, button, ContainerInput.PICKUP, player);
    }
}
