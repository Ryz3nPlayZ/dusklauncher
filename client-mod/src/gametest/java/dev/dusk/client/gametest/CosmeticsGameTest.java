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
    /** DUSK_GAMETEST_ACCESSORIES=62,68 wears those instead, to eyeball other models. */
    private static final int[] ACCESSORIES = accessories();

    private static int[] accessories() {
        String env = System.getenv("DUSK_GAMETEST_ACCESSORIES");
        if (env == null || env.isBlank()) return new int[] {ACCESSORY};
        return java.util.Arrays.stream(env.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // the launcher writes cosmetics.loadout into config/duskclient.json before launch;
        // do the same in-process since the run dir is wiped
        ctx.runOnClient(client -> {
            var cfg = DuskConfig.get().cosmetics;
            cfg.loadout.put("cape", new JsonPrimitive(CAPE));
            JsonArray acc = new JsonArray();
            for (int id : ACCESSORIES) acc.add(id);
            cfg.loadout.put("accessories", acc);
            DuskConfig.save();
            CosmeticsManager.reloadLocal();
        });

        try (TestSingleplayerContext sp = Worlds.create(ctx)) {
            HarnessCompat.waitForChunksRender(sp);
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("gamemode creative Dusk");
            sp.getServer().runCommand("weather clear");

            // wait for the worker thread to resolve the local player's loadout
            int ticks = ctx.waitFor(client -> {
                PlayerCosmetics c = CosmeticsManager.get(client.player.getUUID(), client.player.getName().getString());
                return c.hasCape() && c.accessories().size() == ACCESSORIES.length;
            }, 200);
            LOG.info("Local loadout resolved after {} tick(s)", ticks);

            PlayerCosmetics c = ctx.computeOnClient(client ->
                    CosmeticsManager.get(client.player.getUUID(), client.player.getName().getString()));
            if (!c.hasCape()) throw new AssertionError("cape " + CAPE + " not resolved");
            if (c.accessories().size() != ACCESSORIES.length)
                throw new AssertionError("expected " + ACCESSORIES.length + " accessories, got " + c.accessories());
            for (var a : c.accessories()) {
                if (!a.isReady()) throw new AssertionError("accessory not ready: " + a.entry());
            }

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
            if (System.getenv("DUSK_GAMETEST_ACCESSORIES") != null) closeUps(ctx);
        }
        // the harness requires tests to hand back a client sitting on the title screen
        ctx.waitForScreen(TitleScreen.class);
    }

    /** Each accessory alone, zoomed in, from four sides. */
    private static void closeUps(ClientGameTestContext ctx) {
        ctx.runOnClient(client -> {
            HarnessCompat.hideHud(client);
            client.options.fov().set(30);
            client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        for (int id : ACCESSORIES) {
            ctx.runOnClient(client -> {
                var cfg = DuskConfig.get().cosmetics;
                cfg.loadout.remove("cape");
                JsonArray acc = new JsonArray();
                acc.add(id);
                cfg.loadout.put("accessories", acc);
                CosmeticsManager.reloadLocal();
            });
            ctx.waitFor(client -> {
                PlayerCosmetics c = CosmeticsManager.get(client.player.getUUID(), client.player.getName().getString());
                return c.accessories().size() == 1 && c.accessories().get(0).entry().id() == id
                        && c.accessories().get(0).isReady();
            }, 200);
            for (int yaw : new int[] {0, 60, 150, 240}) {
                ctx.runOnClient(client -> {
                    client.player.setYRot(yaw);
                    client.player.setYHeadRot(yaw);
                    client.player.setYBodyRot(yaw);
                    client.player.setXRot(25);
                });
                ctx.waitTicks(4);
                ctx.takeScreenshot("acc-" + id + "-" + yaw);
            }
        }
    }
}
