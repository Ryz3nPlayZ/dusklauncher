package dev.dusk.client.modules.render;

import dev.dusk.client.compat.LegacyBlock;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;

/**
 * Makes your side of a fight look and sound like 1.8.9. Everything here is
 * visual: damage, knockback, reach and hit timing belong to the server, so
 * the packets sent are the ones vanilla sends and nothing here needs a
 * server's permission.
 *
 * <p>The sword block, crouch pose and sneak camera are ported from
 * old-animations (MIT, see NOTICE), using its 1.8 sneak rather than its 1.7
 * ease. The first-person block transform is the one 1.7 and 1.8 share. The
 * swing during a block is 1.7's blockhit, which vanilla 1.8.9 dropped and
 * almost everyone playing it added back, so it is a setting. The swing and
 * mining tweaks that send extra or different swings are left out.
 */
public class LegacyCombat extends Module {
    private static LegacyCombat instance;

    private final BoolSetting sword = add(new BoolSetting("sword", "Block with your sword", true));
    private final BoolSetting blockHit = add(new BoolSetting("blockHit", "Swing while blocking", true));
    private final BoolSetting sneak = add(new BoolSetting("sneak", "1.8 sneaking", true));
    private final BoolSetting hits = add(new BoolSetting("hits", "1.8 hit effects", true));

    public LegacyCombat() {
        super("legacycombat", "Legacy Combat", Category.RENDER,
                "1.8.9 looks: raise your sword with right click, snappy sneaking, red armour on hit, no sweep particles or attack sounds.");
        instance = this;
    }

    /** Sword blocking needs 1.21.11's item and render code; below that the module isn't registered. */
    public static boolean available() {
        return LegacyBlock.SUPPORTED;
    }

    private static boolean on() {
        LegacyCombat m = instance;
        return m != null && m.enabled();
    }

    /** Whether the blocking sword keeps the attack swing, 1.7's blockhit. */
    public static boolean blockHit() {
        return on() && instance.blockHit.get();
    }

    /** The instant sneak camera and the upright crouch pose. */
    public static boolean oldSneak() {
        return on() && instance.sneak.get();
    }

    /** Red armour on hit, and none of the particles and sounds 1.9 added. */
    public static boolean oldHits() {
        return on() && instance.hits.get();
    }

    /** Sounds 1.9 added: swings play nothing in 1.8, the hit is just the hurt sound. */
    public static boolean mutes(String soundPath) {
        return soundPath.startsWith("entity.player.attack.") && oldHits();
    }

    @Override
    public void tick() {
        if (sword.get()) LegacyBlock.tick();
        else LegacyBlock.reset();
    }

    @Override
    protected void onDisable() {
        LegacyBlock.reset();
    }
}
