package dev.dusk.client.gametest;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.ConfigScreen;
import dev.dusk.client.gui.HudEditorScreen;
import dev.dusk.client.modules.hud.HeldItem;
import dev.dusk.client.modules.hud.ShieldStatus;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.modules.render.Fullbright;
import dev.dusk.client.modules.render.MotionBlur;
import dev.dusk.client.render.MotionBlurRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Boots into a world with the default module set (plus fullbright and motion
 * blur), screenshots the live HUD (custom crosshair, fullbright lightmap mixin
 * and the blur post pass applied), then opens the
 * HUD editor with the centred module window in both its list and settings
 * views. Screenshots land in {@code build/run/clientGameTest/screenshots}.
 */
public final class HudGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("duskclient/gametest");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // fresh run dir = default module set; add the ones the test asserts on
        ctx.runOnClient(client -> {
            Fullbright.instance().setEnabled(true);
            MotionBlur.instance().setEnabled(true);
            DuskClient.modules().get(ShieldStatus.class).setEnabled(true);
            DuskClient.modules().get(HeldItem.class).setEnabled(true);
            client.options.setCameraType(CameraType.FIRST_PERSON);
        });

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            HarnessCompat.waitForChunksRender(sp);
            sp.getServer().runCommand("time set midnight");
            sp.getServer().runCommand("gamemode survival Dusk");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("give Dusk diamond_sword");
            sp.getServer().runCommand("give Dusk shield");
            sp.getServer().runCommand("item replace entity Dusk armor.head with iron_helmet");
            sp.getServer().runCommand("item replace entity Dusk armor.chest with iron_chestplate");
            sp.getServer().runCommand("effect give Dusk speed 120 1");
            ctx.waitTicks(20);

            boolean crosshair = ctx.computeOnClient(client -> CustomCrosshair.instance().shouldDraw(client));
            if (!crosshair) throw new AssertionError("custom crosshair should draw in first person");
            if (!ctx.computeOnClient(client -> Fullbright.instance().enabled())) throw new AssertionError("fullbright not enabled");
            Object gamma = Fullbright.applyGamma(1.0);
            if (!(gamma instanceof Double d) || d < 1.0) throw new AssertionError("fullbright gamma not applied: " + gamma);

            // spin the camera so the accumulation blur has something to smear
            ctx.runOnClient(client -> client.player.setYRot(client.player.getYRot() + 90));
            ctx.waitTicks(2);
            Path hud = ctx.takeScreenshot("hud-ingame");
            int blended = ctx.computeOnClient(client -> MotionBlurRenderer.blendedFrames());
            if (blended <= 0) throw new AssertionError("motion blur pass never ran (post chain failed to load?)");
            LOG.info("Motion blur blended {} frames", blended);

            ctx.runOnClient(client -> Compat.setScreen(client, new HudEditorScreen(null)));
            ctx.waitTicks(5);
            if (!ctx.computeOnClient(client -> Compat.currentScreen(client) instanceof HudEditorScreen)) {
                throw new AssertionError("HUD editor did not open");
            }
            Path editor = ctx.takeScreenshot("hud-editor-list");

            ctx.runOnClient(client -> {
                Compat.setScreen(client, ConfigScreen.module(Compat.currentScreen(client), CustomCrosshair.instance()));
            });
            ctx.waitTicks(5);
            Path settings = ctx.takeScreenshot("hud-editor-settings");
            LOG.info("Screenshots: {} {} {}", hud, editor, settings);

            ctx.runOnClient(client -> Compat.setScreen(client, null));
            ctx.waitTicks(5);
        }
        ctx.waitForScreen(TitleScreen.class);
    }
}
