package dev.dusk.client.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignText;
import org.jetbrains.annotations.Nullable;

/** The version-specific calls the shared code needs. 1.21.4 flavour. */
public final class Compat {
    private Compat() {}

    /**
     * Whether this target has the 1.21.11+ client internals the BactroMod
     * ports hook: SubmitNodeCollector hands, the FogEnvironment split and
     * the permission checks around the debug keys. The modules that rely on
     * them stay unregistered elsewhere rather than showing a dead toggle.
     */
    public static final boolean MODERN_CLIENT_HOOKS = false;

    public static void setScreen(Minecraft mc, @Nullable Screen screen) {
        mc.setScreen(screen);
    }

    @Nullable
    public static Screen currentScreen(Minecraft mc) {
        return mc.screen;
    }

    public static Screen optionsScreen(Screen parent, Minecraft mc) {
        return new OptionsScreen(parent, mc.options);
    }

    /** Textured, alpha-tested, both faces visible: what capes/ears want. */
    public static RenderType entityCutoutNoCull(ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture);
    }

    /** Registers a key in the "DuskClient" controls category (a translation key before 1.21.9). */
    public static KeyMapping registerKey(String name, int glfwKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, glfwKey, "key.categories.duskclient"));
    }

    /** The framebuffer the world was just drawn into (motion blur). */
    public static RenderTarget mainTarget(Minecraft mc) {
        return mc.getMainRenderTarget();
    }

    /** Path part of a registry key (biome names). */
    public static String keyPath(ResourceKey<?> key) {
        return key.location().getPath();
    }

    /** Item cooldowns are keyed by stack from 1.21.2 (by item before). */
    public static boolean isOnCooldown(Player player, ItemStack stack) {
        return player.getCooldowns().isOnCooldown(stack);
    }

    public static float cooldownPercent(Player player, ItemStack stack, float partialTick) {
        return player.getCooldowns().getCooldownPercent(stack, partialTick);
    }

    /** Ticks on the overworld day clock (drives the clock/day-counter HUD). */
    public static long dayTime(Level level) {
        return level.getDayTime();
    }

    /** Sign face texture for a wood type ("oak", or "modid:wood"), as a namespaced id. */
    public static String signTexture(String woodName, boolean hanging) {
        int colon = woodName.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : woodName.substring(0, colon);
        String path = colon < 0 ? woodName : woodName.substring(colon + 1);
        return namespace + ":textures/entity/signs/" + (hanging ? "hanging/" : "") + path + ".png";
    }

    /** The darker outline colour glowing sign text is drawn with. */
    public static int signDarkColor(SignText text) {
        return AbstractSignRenderer.getDarkColor(text);
    }

    private static java.util.function.Supplier<net.minecraft.client.resources.PlayerSkin> skin;

    /** The local player's skin texture as "namespace:path" (the default skin until it has loaded). */
    public static String localSkin(Minecraft mc) {
        return skin(mc).texture().toString();
    }

    /** Whether the local player's skin uses the slim (3px) arms. */
    public static boolean localSkinSlim(Minecraft mc) {
        return skin(mc).model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM;
    }

    private static net.minecraft.client.resources.PlayerSkin skin(Minecraft mc) {
        if (skin == null) skin = mc.getSkinManager().lookupInsecure(mc.getGameProfile());
        return skin.get();
    }
}
