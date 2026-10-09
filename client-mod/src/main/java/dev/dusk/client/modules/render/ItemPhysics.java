package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;

/**
 * Dropped items that have landed lie on the ground instead of hovering and
 * spinning: flat items (most tools, food, ingots) lie face up, blocks sit on
 * their base, each turned its own way. Items still in the air or in water
 * move as they do in vanilla. Only how they are drawn changes; pickup and
 * hitboxes are the server's.
 */
public class ItemPhysics extends Module {
    private static ItemPhysics instance;

    public ItemPhysics() {
        super("itemphysics", "Item Physics", Category.RENDER,
                "Dropped items lie flat on the ground instead of floating and spinning.");
        instance = this;
    }

    public static boolean on() {
        ItemPhysics m = instance;
        return m != null && m.enabled();
    }
}
