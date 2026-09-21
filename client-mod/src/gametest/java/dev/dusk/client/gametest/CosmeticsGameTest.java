package dev.dusk.client.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * End-to-end check of the cosmetics pipeline: equip the launcher-style loadout
 * (registry cape 5 "Animated Fire" + accessory 16 "Red Fire Arm"), join a world,
 * wait for the worker to resolve it, and screenshot the player from the front
 * and the back. Assertions cover what code can see; the screenshots in
 * {@code build/run/clientGameTest/screenshots} are for eyeballing.
 */
public final class CosmeticsGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("duskclient/gametest");
    private static final int CAPE = 5;
    private static final int ACCESSORY = 16;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // the launcher writes cosmetics.loadout into config/duskclient.json before launch;
        // do the same in-process since the run dir is wiped
        ctx.runOnClient(client -> {
            var cfg = DuskConfig.get().cosmetics;
            cfg.loadout.put("cape", new JsonPrimitive(CAPE));
            JsonArray acc = new JsonArray();
            acc.add(ACCESSORY);
            cfg.loadout.put("accessories", acc);
            DuskConfig.save();
            CosmeticsManager.reloadLocal();
        });

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            HarnessCompat.waitForChunksRender(sp);
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("gamemode creative Dusk");
            sp.getServer().runCommand("weather clear");

            // wait for the worker thread to resolve the local player's loadout
            int ticks = ctx.waitFor(client -> {
                PlayerCosmetics c = CosmeticsManager.get(client.player.getUUID(), client.player.getName().getString());
                return c.hasCape() && c.accessories().size() == 1;
            }, 200);
            LOG.info("Local loadout resolved after {} tick(s)", ticks);

            PlayerCosmetics c = ctx.computeOnClient(client ->
                    CosmeticsManager.get(client.player.getUUID(), client.player.getName().getString()));
            if (!c.hasCape()) throw new AssertionError("cape " + CAPE + " not resolved");
            if (c.accessories().size() != 1) throw new AssertionError("expected 1 accessory, got " + c.accessories());
            var a = c.accessories().get(0);
            if (a.entry().id() != ACCESSORY || !a.isReady()) throw new AssertionError("accessory not ready: " + a.entry());

            ctx.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                client.player.setYRot(0);
                client.player.setXRot(0);
            });
            ctx.waitTicks(20);
            Path front = ctx.takeScreenshot("cosmetics-front");
            ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            ctx.waitTicks(10);
            Path back = ctx.takeScreenshot("cosmetics-back");
            LOG.info("Screenshots: {} {}", front, back);
        }
        // the harness requires tests to hand back a client sitting on the title screen
        ctx.waitForScreen(TitleScreen.class);
    }
}
