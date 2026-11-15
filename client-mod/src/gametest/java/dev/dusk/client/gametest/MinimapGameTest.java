package dev.dusk.client.gametest;

import dev.dusk.client.DuskClient;
import dev.dusk.client.module.setting.Setting;
import dev.dusk.client.modules.hud.Minimap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Walks the tilted minimap through real terrain: the surface, a block edit, a
 * wider view, a dug-out cave and the Nether, one screenshot each. Nothing is
 * asserted about how it looks; that needs someone to read the screenshots
 * in {@code build/run/clientGameTest/screenshots}.
 */
public final class MinimapGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(client -> {
            Minimap map = DuskClient.modules().get(Minimap.class);
            map.setEnabled(true);
            set(map, "size", 160);
            set(map, "mobs", true);
            client.options.menuBackgroundBlurriness().set(0);
            client.options.renderDistance().set(10);
        });
        try (TestSingleplayerContext sp = ctx.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed("dusk"))
                .create()) {
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("gamemode creative Dusk");
            sp.getServer().runCommand("execute at Dusk run summon minecraft:pig ~4 ~ ~4");
            settle(ctx, 80);
            ctx.takeScreenshot("minimap-surface");

            // a block edit has to reach the map
            sp.getServer().runCommand("execute at Dusk run fill ~3 ~-1 ~-14 ~14 ~-1 ~-3 minecraft:red_wool");
            settle(ctx, 20);
            ctx.takeScreenshot("minimap-edited");

            ctx.runOnClient(client -> set(DuskClient.modules().get(Minimap.class), "range", 160));
            settle(ctx, 40);
            ctx.takeScreenshot("minimap-wide");
            ctx.runOnClient(client -> set(DuskClient.modules().get(Minimap.class), "range", 64));

            sp.getServer().runCommand("execute at Dusk positioned ~ 8 ~ run fill ~-10 ~ ~-10 ~10 ~6 ~10 minecraft:air");
            sp.getServer().runCommand("execute at Dusk positioned ~ 8 ~ run fill ~-10 ~ ~-10 ~-5 ~ ~-5 minecraft:water");
            sp.getServer().runCommand("execute at Dusk positioned ~ 8 ~ run fill ~5 ~ ~5 ~10 ~ ~10 minecraft:lava");
            sp.getServer().runCommand("execute at Dusk positioned ~ 8 ~ run setblock ~2 ~ ~-3 minecraft:torch");
            sp.getServer().runCommand("execute at Dusk run tp Dusk ~ 9 ~");
            settle(ctx, 40);
            ctx.takeScreenshot("minimap-cave");

            sp.getServer().runCommand("execute in minecraft:the_nether run tp Dusk 0 70 0");
            settle(ctx, 20);
            sp.getServer().runCommand("execute in minecraft:the_nether run fill -6 69 -6 6 73 6 minecraft:air");
            settle(ctx, 40);
            ctx.takeScreenshot("minimap-nether");
        }
        ctx.runOnClient(client -> {
            Minimap map = DuskClient.modules().get(Minimap.class);
            map.setEnabled(false);
            set(map, "size", 96);
            set(map, "mobs", false);
        });
    }

    /** Ticks with pauses, as tiles bake on real time. The pauses hold the game too, so its FPS reads low here. */
    private static void settle(ClientGameTestContext ctx, int steps) {
        for (int i = 0; i < steps; i++) {
            ctx.waitTicks(5);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(Minimap map, String id, T value) {
        for (Setting<?> setting : map.settings()) {
            if (setting.id().equals(id)) ((Setting<T>) setting).set(value);
        }
    }
}
