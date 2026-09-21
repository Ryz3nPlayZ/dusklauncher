package dev.dusk.client;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.ModuleManager;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.DuskSettingsScreen;
import dev.dusk.client.modules.hud.CpsCounter;
import dev.dusk.client.modules.hud.FpsDisplay;
import dev.dusk.client.modules.hud.Keystrokes;
import dev.dusk.client.modules.toggle.ToggleSprint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.cosmetics.CosmeticsManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Registers all PvP modules. Anti-cheat safety rule:
 * modules are client-side-only (rendering/HUD/input) and never alter
 * outbound packets or movement math.
 */
public class DuskClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("duskclient");
    private static ModuleManager modules;

    public static ModuleManager modules() {
        return modules;
    }

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager();
        modules.register(new Keystrokes());
        modules.register(new CpsCounter());
        modules.register(new FpsDisplay());
        modules.register(new ToggleSprint());
        modules.register(new dev.dusk.client.modules.hud.ArmorStatus());
        modules.register(new dev.dusk.client.modules.hud.ComboDisplay());
        // TODO: HitDelayFix (mixin), Zoom, CustomScoreboard, HUD layout editor screen
        modules.loadConfig();
        DuskConfig.get(); // ensure duskclient.json exists for the launcher bridge
        CosmeticsManager.init();
        KeyMapping settingsKey = Compat.registerKey("key.duskclient.settings", GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("duskclient", "client")));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.consumeClick()) {
                if (client.player != null || client.level != null) {
                    Compat.setScreen(client, new DuskSettingsScreen(Compat.currentScreen(client)));
                }
            }
        });
        LOGGER.info("DuskClient initialized with {} modules", modules.all().size());
    }
}
