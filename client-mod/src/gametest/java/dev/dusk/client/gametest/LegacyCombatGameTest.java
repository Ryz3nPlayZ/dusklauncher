package dev.dusk.client.gametest;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.LegacyBlock;
import dev.dusk.client.modules.render.LegacyCombat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;

/**
 * Legacy Combat in a real world: holds right click with a sword and checks the
 * block starts and stops, with screenshots of the pose from both cameras, the
 * upright crouch and a hit on armour for reading by eye.
 */
public final class LegacyCombatGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!LegacyCombat.available()) return;
        ctx.runOnClient(client -> {
            DuskClient.modules().get(LegacyCombat.class).setEnabled(true);
            client.options.setCameraType(CameraType.FIRST_PERSON);
        });

        try (TestSingleplayerContext sp = Worlds.create(ctx)) {
            HarnessCompat.waitForChunksRender(sp);
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("gamemode survival Dusk");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("give Dusk diamond_sword");
            sp.getServer().runCommand("item replace entity Dusk armor.chest with iron_chestplate");
            sp.getServer().runCommand("item replace entity Dusk armor.legs with iron_leggings");
            ctx.waitTicks(20);
            ctx.takeScreenshot("legacy-held");

            ctx.getInput().holdKey(options -> options.keyUse);
            ctx.waitTicks(10);
            if (!ctx.computeOnClient(client -> LegacyBlock.blocking())) {
                throw new AssertionError("holding right click with a sword should block");
            }
            ctx.takeScreenshot("legacy-block-first-person");
            ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
            ctx.waitTicks(5);
            ctx.takeScreenshot("legacy-block-third-person");
            ctx.getInput().releaseKey(options -> options.keyUse);
            ctx.waitTicks(5);
            if (ctx.computeOnClient(client -> LegacyBlock.blocking())) {
                throw new AssertionError("letting go of right click should stop blocking");
            }

            ctx.getInput().holdKey(options -> options.keyShift);
            ctx.waitTicks(10);
            ctx.takeScreenshot("legacy-sneak");
            ctx.getInput().releaseKey(options -> options.keyShift);
            ctx.waitTicks(5);

            sp.getServer().runCommand("damage Dusk 1");
            ctx.waitTicks(2);
            ctx.takeScreenshot("legacy-hit-armour");
        }
        ctx.runOnClient(client -> {
            DuskClient.modules().get(LegacyCombat.class).setEnabled(false);
            client.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }
}
