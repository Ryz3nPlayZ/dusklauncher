package dev.dusk.client.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** The version-specific calls the shared code needs. 26.2 flavour. */
public final class Compat {
    private Compat() {}

    public static void setScreen(Minecraft mc, @Nullable Screen screen) {
        mc.gui.setScreen(screen);
    }

    @Nullable
    public static Screen currentScreen(Minecraft mc) {
        return mc.gui.screen();
    }

    public static Screen optionsScreen(Screen parent, Minecraft mc) {
        return new OptionsScreen(parent, mc.options, false);
    }

    /** Textured, alpha-tested, both faces visible: what capes/ears want. */
    public static RenderType entityCutoutNoCull(Identifier texture) {
        return RenderTypes.entityCutout(texture); // 26.x: cull is the opt-in variant
    }

    private static KeyMapping.Category keyCategory;

    /** Registers a key in the "DuskClient" controls category (a KeyMapping.Category since 1.21.9). */
    public static KeyMapping registerKey(String name, int glfwKey) {
        if (keyCategory == null) {
            keyCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("duskclient", "client"));
        }
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYSYM, glfwKey, keyCategory));
    }

    /** The framebuffer the world was just drawn into (motion blur). */
    public static RenderTarget mainTarget(Minecraft mc) {
        return mc.gameRenderer.mainRenderTarget();
    }

    /** Path part of a registry key (biome names). */
    public static String keyPath(ResourceKey<?> key) {
        return key.identifier().getPath();
    }

    /** Ticks on the overworld day clock (drives the clock/day-counter HUD). */
    public static long dayTime(Level level) {
        return level.getOverworldClockTime();
    }
}
