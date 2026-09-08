package dev.fasterlauncher.client;

import dev.fasterlauncher.client.module.Module;
import dev.fasterlauncher.client.module.ModuleManager;
import dev.fasterlauncher.client.config.DuskConfig;
import dev.fasterlauncher.client.gui.DuskSettingsScreen;
import dev.fasterlauncher.client.modules.hud.CpsCounter;
import dev.fasterlauncher.client.modules.hud.FpsDisplay;
import dev.fasterlauncher.client.modules.hud.Keystrokes;
import dev.fasterlauncher.client.modules.toggle.ToggleSprint;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Registers all PvP modules. Anti-cheat safety rule:
 * modules are client-side-only (rendering/HUD/input) and never alter
 * outbound packets or movement math.
 */
public class FasterClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("fasterclient");
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
        modules.register(new dev.fasterlauncher.client.modules.hud.ArmorStatus());
        modules.register(new dev.fasterlauncher.client.modules.hud.ComboDisplay());
        // TODO: HitDelayFix (mixin), Zoom, CustomScoreboard, HUD layout editor screen
        modules.loadConfig();
        DuskConfig.get(); // ensure duskclient.json exists for the launcher bridge
        KeyBinding settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fasterclient.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyBinding.Category.create(Identifier.of("fasterclient", "client"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.wasPressed()) {
                if (client.player != null || client.world != null) {
                    client.setScreen(new DuskSettingsScreen(client.currentScreen));
                }
            }
        });
        LOGGER.info("FasterClient initialized with {} modules", modules.all().size());
    }
}
