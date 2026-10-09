package dev.dusk.client.compat;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignText;
import org.jetbrains.annotations.Nullable;

/** The version-specific calls the shared code needs. 26.2 flavour. */
public final class Compat {
    private Compat() {}

    /**
     * Whether this target has the 1.21.11+ client internals the BactroMod
     * ports hook: SubmitNodeCollector hands, the FogEnvironment split and
     * the permission checks around the debug keys. The modules that rely on
     * them stay unregistered elsewhere rather than showing a dead toggle.
     */
    public static final boolean MODERN_CLIENT_HOOKS = true;

    public static void setScreen(Minecraft mc, @Nullable Screen screen) {
        mc.gui.setScreen(screen);
    }

    @Nullable
    public static Screen currentScreen(Minecraft mc) {
        return mc.gui.screen();
    }

    /** Whether F1 has hidden the HUD (and with it, vanilla nametags). */
    public static boolean hudHidden(Minecraft mc) {
        return mc.gui.hud.isHidden();
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

    /** A line on the action bar, above the hotbar. */
    public static void actionBar(net.minecraft.world.entity.player.Player player, net.minecraft.network.chat.Component text) {
        player.sendOverlayMessage(text);
    }

    /** The monster Light Overlay tests spawn spots for (EntityType moved its constants to EntityTypes in 26.2). */
    public static net.minecraft.world.entity.EntityType<?> spawnTestMob() {
        return net.minecraft.world.entity.EntityTypes.ZOMBIE;
    }

    /** A key mapping's Controls category as shown on screen (a translation key before 1.21.9). */
    public static String keyCategoryLabel(KeyMapping key) {
        return key.getCategory().label().getString();
    }

    /** The framebuffer the world was just drawn into (motion blur). */
    public static RenderTarget mainTarget(Minecraft mc) {
        return mc.gameRenderer.mainRenderTarget();
    }

    /** Path part of a registry key (biome names). */
    public static String keyPath(ResourceKey<?> key) {
        return key.identifier().getPath();
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
        return level.getOverworldClockTime();
    }

    /** Sign face texture for a wood type ("oak", or "modid:wood"), as a namespaced id. 26.2 moved these under textures/block. */
    public static String signTexture(String woodName, boolean hanging) {
        int colon = woodName.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : woodName.substring(0, colon);
        String path = colon < 0 ? woodName : woodName.substring(colon + 1);
        return namespace + ":textures/block/" + path + (hanging ? "_hanging_sign.png" : "_sign.png");
    }

    /** The darker outline colour glowing sign text is drawn with. */
    public static int signDarkColor(SignText text) {
        return AbstractSignRenderer.getDarkColor(text);
    }

    /** The local player's skin texture as "namespace:path" (the default skin until it has loaded). */
    public static String localSkin(Minecraft mc) {
        return skin(mc).body().texturePath().toString();
    }

    /** Whether the local player's skin uses the slim (3px) arms. */
    public static boolean localSkinSlim(Minecraft mc) {
        return skin(mc).model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
    }

    private static net.minecraft.world.entity.player.PlayerSkin skin(Minecraft mc) {
        return SkinCompat.current(mc);
    }

    /** Speed, which 1.21.5 renamed from MOVEMENT_SPEED. */
    public static Holder<MobEffect> speedEffect() {
        return MobEffects.SPEED;
    }

    /** Whether sign textures use the 32x32 block sheet (front above back) 26.2 moved them to. */
    public static boolean flatSignSheet() {
        return true;
    }

    /** Leaves the world or server, as the pause menu's Disconnect does (lands on the title or server list). */
    public static void leaveWorld(Minecraft mc) {
        mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
    }

    /** Open the integrated server to other players on `port`, with the world's own game mode and cheat setting. */
    public static boolean publishLan(net.minecraft.client.server.IntegratedServer server, int port) {
        return server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, port);
    }
}
