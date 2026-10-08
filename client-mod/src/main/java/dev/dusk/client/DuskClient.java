package dev.dusk.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.ChatCompat;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.Input;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.LoadoutWatcher;
import dev.dusk.client.gui.DuskMenuScreen;
import dev.dusk.client.gui.MenuScreen;
import dev.dusk.client.hud.HudHooks;
import dev.dusk.client.hud.Raycast;
import dev.dusk.client.hud.TpsTracker;
import dev.dusk.client.media.MediaBackend;
import dev.dusk.client.module.ModuleManager;
import dev.dusk.client.modules.hud.ArmorStatus;
import dev.dusk.client.modules.hud.Biome;
import dev.dusk.client.modules.hud.Clock;
import dev.dusk.client.modules.hud.Cooldowns;
import dev.dusk.client.modules.hud.ResourcePacks;
import dev.dusk.client.modules.hud.Stopwatch;
import dev.dusk.client.modules.hud.ComboDisplay;
import dev.dusk.client.modules.hud.Compass;
import dev.dusk.client.modules.hud.Coordinates;
import dev.dusk.client.modules.hud.CpsCounter;
import dev.dusk.client.modules.hud.DayCounter;
import dev.dusk.client.modules.hud.Distance;
import dev.dusk.client.modules.hud.EntityCount;
import dev.dusk.client.modules.hud.FpsDisplay;
import dev.dusk.client.modules.hud.FullInventory;
import dev.dusk.client.modules.hud.GameTime;
import dev.dusk.client.modules.hud.HeldItem;
import dev.dusk.client.modules.hud.InventoryDisplay;
import dev.dusk.client.modules.hud.Keystrokes;
import dev.dusk.client.modules.hud.LightLevel;
import dev.dusk.client.modules.hud.LookingAt;
import dev.dusk.client.modules.hud.ServerLag;
import dev.dusk.client.modules.misc.BackgroundFps;
import dev.dusk.client.modules.misc.NameHider;
import dev.dusk.client.modules.render.BlockOutline;
import dev.dusk.client.modules.render.BossBarTweaks;
import dev.dusk.client.modules.render.ScoreboardTweaks;
import dev.dusk.client.modules.render.TabPing;
import dev.dusk.client.modules.hud.Memory;
import dev.dusk.client.modules.hud.Minimap;
import dev.dusk.client.modules.hud.NetherCoordinates;
import dev.dusk.client.modules.hud.Ping;
import dev.dusk.client.modules.hud.PitchDisplay;
import dev.dusk.client.modules.hud.Playtime;
import dev.dusk.client.modules.hud.PotionEffects;
import dev.dusk.client.modules.hud.Reach;
import dev.dusk.client.modules.hud.ServerAddress;
import dev.dusk.client.modules.hud.SignReader;
import dev.dusk.client.modules.hud.SneakStatus;
import dev.dusk.client.modules.hud.Speed;
import dev.dusk.client.modules.hud.SprintStatus;
import dev.dusk.client.modules.hud.Tps;
import dev.dusk.client.modules.hud.Weather;
import dev.dusk.client.modules.misc.ChatHeads;
import dev.dusk.client.modules.misc.ChatMentions;
import dev.dusk.client.modules.misc.ChatTimestamps;
import dev.dusk.client.modules.misc.CompactChat;
import dev.dusk.client.modules.misc.BoatMap;
import dev.dusk.client.modules.misc.AutoReconnect;
import dev.dusk.client.modules.misc.ChatHistory;
import dev.dusk.client.modules.misc.ChatMacros;
import dev.dusk.client.modules.misc.ConfirmDisconnect;
import dev.dusk.client.modules.misc.Statistics;
import dev.dusk.client.modules.misc.GameModeSwitcher;
import dev.dusk.client.modules.misc.TntCountdown;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.modules.render.ColorSaturation;
import dev.dusk.client.modules.render.ContainerPreview;
import dev.dusk.client.modules.render.DamageTint;
import dev.dusk.client.modules.render.FogControl;
import dev.dusk.client.modules.render.Fullbright;
import dev.dusk.client.modules.render.Hitbox;
import dev.dusk.client.modules.render.ItemScale;
import dev.dusk.client.modules.render.LowFire;
import dev.dusk.client.modules.render.TotemPop;
import dev.dusk.client.modules.render.GlintColor;
import dev.dusk.client.modules.render.LowShield;
import dev.dusk.client.modules.render.ShieldStatuses;
import dev.dusk.client.modules.misc.Waypoints;
import dev.dusk.client.waypoints.WaypointCommands;
import dev.dusk.client.modules.render.MotionBlur;
import dev.dusk.client.modules.render.Nametags;
import dev.dusk.client.modules.render.BehindYou;
import dev.dusk.client.modules.render.Zoom;
import dev.dusk.client.modules.render.Freecam;
import dev.dusk.client.modules.render.Freelook;
import dev.dusk.client.modules.render.HungerInfo;
import dev.dusk.client.modules.render.CapePhysics;
import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.modules.render.SkinLayers3D;
import dev.dusk.client.modules.render.FovChanger;
import dev.dusk.client.modules.hud.TotemCounter;
import dev.dusk.client.modules.hud.ItemCounter;
import dev.dusk.client.modules.hud.BedwarsResources;
import dev.dusk.client.modules.hud.SkyblockStats;
import dev.dusk.client.modules.misc.HypixelTweaks;
import dev.dusk.client.modules.misc.SlotLock;
import dev.dusk.client.modules.render.NoNightVision;
import dev.dusk.client.modules.render.NoPumpkinBlur;
import dev.dusk.client.modules.render.RiptideShieldFix;
import dev.dusk.client.modules.render.TimeChanger;
import dev.dusk.client.modules.render.WeatherChanger;
import dev.dusk.client.modules.toggle.ToggleSprint;
import dev.dusk.client.server.ServerApi;
import dev.dusk.client.social.SocialNotifier;
import dev.dusk.client.gui.QuestsScreen;
import dev.dusk.client.gui.DuskStatsScreen;
import dev.dusk.client.gui.SocialScreen;
import dev.dusk.client.compat.ScreenWidgets;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Registers every module and the keybinds. Anti-cheat safety
 * rule: modules are client-side-only (rendering/HUD/input) and never alter
 * outbound packets or movement math.
 */
public class DuskClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("duskclient");
    private static ModuleManager modules;
    private static KeyMapping settingsKey;
    private static KeyMapping clipKey;
    private static KeyMapping friendsKey;

    public static ModuleManager modules() {
        return modules;
    }

    /** The key that opens (and, inside the editor, closes) the HUD editor. */
    public static KeyMapping settingsKey() {
        return settingsKey;
    }

    /** Saves the clip buffer (Media → Clips). */
    public static KeyMapping clipKey() {
        return clipKey;
    }

    @Override
    public void onInitializeClient() {
        modules = new ModuleManager();
        // Keystroke-ish HUD
        // first, so it heads the Dusk section of Controls; module keys register with their modules
        settingsKey = Compat.registerKey("key.duskclient.settings", InputConstants.KEY_RSHIFT);
        modules.register(new Keystrokes());
        modules.register(new CpsCounter());
        modules.register(new FpsDisplay());
        modules.register(new Ping());
        modules.register(new Tps());
        modules.register(new ArmorStatus());
        modules.register(new HeldItem());
        modules.register(new PotionEffects());
        modules.register(new ComboDisplay());
        modules.register(new Reach());
        modules.register(new SprintStatus());
        modules.register(new SneakStatus());
        modules.register(new FullInventory());
        modules.register(new InventoryDisplay());
        modules.register(new TotemCounter());
        modules.register(new ItemCounter());
        // Info HUD
        modules.register(new Coordinates());
        modules.register(new NetherCoordinates());
        modules.register(new Compass());
        modules.register(new Minimap());
        modules.register(new PitchDisplay());
        modules.register(new Speed());
        modules.register(new Biome());
        modules.register(new LightLevel());
        modules.register(new Clock());
        modules.register(new GameTime());
        modules.register(new DayCounter());
        modules.register(new Weather());
        modules.register(new Playtime());
        modules.register(new EntityCount());
        modules.register(new Memory());
        modules.register(new ServerAddress());
        modules.register(new Distance());
        modules.register(new SignReader());
        modules.register(new LookingAt());
        modules.register(new ServerLag());
        modules.register(new Stopwatch());
        modules.register(new ResourcePacks());
        // Movement / render
        modules.register(new ToggleSprint());
        modules.register(new CustomCrosshair());
        modules.register(new Fullbright());
        modules.register(new MotionBlur());
        modules.register(new TntCountdown());
        modules.register(new NameHider());
        modules.register(new FovChanger());
        modules.register(new SlotLock());
        // Hypixel: chat tweaks, Bed Wars and SkyBlock HUDs
        modules.register(new HypixelTweaks());
        HypixelTweaks.register();
        modules.register(new BedwarsResources());
        modules.register(new SkyblockStats());
        SkyblockStats.register();
        if (Compat.MODERN_CLIENT_HOOKS) {
            // BactroMod ports; they hook client internals only 1.21.11+ has
            modules.register(new NoPumpkinBlur());
            modules.register(new LowFire());
            modules.register(new TotemPop());
            modules.register(new GlintColor());
            modules.register(new Cooldowns());
            modules.register(new LowShield());
            modules.register(new NoNightVision());
            modules.register(new RiptideShieldFix());
            modules.register(new ShieldStatuses()); // Walksy's Shield Statuses
            modules.register(new ItemScale());
            modules.register(new FogControl());
            modules.register(new BoatMap());
            modules.register(new GameModeSwitcher());
            modules.register(new TimeChanger());
            modules.register(new WeatherChanger());
            modules.register(new DamageTint());
            modules.register(new ColorSaturation());
            modules.register(new Hitbox());
            modules.register(new Particles());
            modules.register(new Nametags());
            modules.register(new ScoreboardTweaks());
            modules.register(new BossBarTweaks());
            // both step aside for the standalone mods the default pack already ships
            if (!FabricLoader.getInstance().isModLoaded("betterpingdisplay")) modules.register(new TabPing());
            modules.register(new BlockOutline());
            if (!FabricLoader.getInstance().isModLoaded("dynamic_fps")) modules.register(new BackgroundFps());
        }
        BehindYou behindYou = new BehindYou();
        modules.register(behindYou);
        // essentials the launcher's injection brings everywhere; each steps aside for its standalone mod
        FabricLoader fabric = FabricLoader.getInstance();
        Zoom zoom = fabric.isModLoaded("zoomify") ? null : new Zoom();
        if (zoom != null) modules.register(zoom);
        Freelook freelook = fabric.isModLoaded("freelook") || fabric.isModLoaded("perspectivemod") ? null : new Freelook();
        if (freelook != null) modules.register(freelook);
        Freecam freecam = fabric.isModLoaded("freecam") ? null : new Freecam();
        if (freecam != null) modules.register(freecam);
        if (!fabric.isModLoaded("appleskin")) modules.register(new HungerInfo());
        if (!fabric.isModLoaded("shulkerboxtooltip")) modules.register(new ContainerPreview());
        if (!fabric.isModLoaded("skinlayers3d")) modules.register(new SkinLayers3D());
        if (!fabric.isModLoaded("waveycapes")) modules.register(new CapePhysics());
        modules.register(new CompactChat()); // Compact Chat
        modules.register(new ChatTimestamps()); // Plague's Chat Timestamps
        modules.register(new ChatMentions());
        if (ChatCompat.HEADS && !fabric.isModLoaded("chat_heads")) modules.register(new ChatHeads());
        modules.register(new ConfirmDisconnect());
        modules.register(new Statistics());
        modules.register(new ChatHistory());
        ChatMacros chatMacros = new ChatMacros();
        modules.register(chatMacros);
        modules.register(new AutoReconnect());
        AutoReconnect.register();
        Waypoints waypoints = new Waypoints();
        modules.register(waypoints);
        ClientCommandRegistrationCallback.EVENT.register(WaypointCommands::register);
        modules.loadConfig();
        DuskConfig.get(); // ensure duskclient.json exists for the launcher bridge
        CosmeticsManager.init();
        HudHooks.register();
        ServerApi.init();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            TpsTracker.reset();
            dev.dusk.client.hud.PingTracker.reset();
            ServerApi.greetIfListening();
            // the launcher reads these lines to show friends where you are; a replay is not a server
            if (MediaBackend.replaying()) return;
            var server = client.getCurrentServer();
            if (server != null && server.ip != null && !server.ip.isBlank()) LOGGER.info("[DuskPresence] server {}", server.ip);
            else {
                LOGGER.info("[DuskPresence] singleplayer");
                dev.dusk.client.social.WorldHost.onJoin(client);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerApi.reset();
            LOGGER.info("[DuskPresence] menu");
        });
        clipKey = Compat.registerKey("key.duskclient.save_clip", InputConstants.KEY_F8);
        friendsKey = Compat.registerKey("key.duskclient.friends", Input.UNKNOWN);
        MediaBackend.init();
        SocialNotifier.start();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PauseScreen) {
                addFriendsButton(screen);
                replaceStatsButton(screen);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LoadoutWatcher.tick();
            // vanilla's Statistics screen reached some other way than the pause menu
            if (Statistics.on() && Compat.currentScreen(client) instanceof Screen stats
                    && stats.getClass() == net.minecraft.client.gui.screens.achievement.StatsScreen.class) {
                DuskStatsScreen.show(DuskStatsScreen.parentOf(stats));
            }
            SocialNotifier.tick(client);
            while (settingsKey.consumeClick()) {
                Screen current = Compat.currentScreen(client);
                if (client.player != null && !(current instanceof MenuScreen)) {
                    Compat.setScreen(client, new DuskMenuScreen(current));
                }
            }
            boolean changed = Fullbright.instance().tickKeys();
            changed |= modules.get(ToggleSprint.class).tickKeys();
            if (TimeChanger.instance() != null) changed |= TimeChanger.instance().tickKeys();
            changed |= ShieldStatuses.tickKeys();
            changed |= modules.tickToggleKeys(client);
            if (changed) modules.saveConfig();
            while (clipKey.consumeClick()) MediaBackend.saveClip();
            while (friendsKey.consumeClick()) {
                Screen current = Compat.currentScreen(client);
                if (!(current instanceof SocialScreen)) SocialScreen.show(current);
            }
            behindYou.tickKeys();
            if (zoom != null) zoom.tickKeys();
            if (freelook != null) freelook.tickKeys();
            if (freecam != null) freecam.tickKeys();
            chatMacros.tickKeys();
            waypoints.tickKeys();
            if (modules.get(Distance.class).enabled() || modules.get(SignReader.class).enabled()) {
                Raycast.tick(client);
            }
            modules.tick();
        });
        LOGGER.info("DuskClient initialized with {} modules", modules.all().size());
    }

    /** The pause menu's Statistics button opens {@link DuskStatsScreen} while {@link Statistics} is on. */
    private static void replaceStatsButton(Screen screen) {
        if (!Statistics.on()) return;
        var widgets = ScreenWidgets.of(screen);
        Component label = Component.translatable("gui.stats");
        for (int i = 0; i < widgets.size(); i++) {
            AbstractWidget b = widgets.get(i);
            if (!(b instanceof Button) || !label.equals(b.getMessage())) continue;
            Button mine = Button.builder(b.getMessage(), x -> DuskStatsScreen.show(screen))
                    .bounds(b.getX(), b.getY(), b.getWidth(), b.getHeight()).build();
            mine.active = b.active;
            widgets.set(i, mine);
            return;
        }
    }

    /** Friends & Chat and Quests under the pause menu's buttons (none when the menu is hidden, F3+Esc). */
    private static void addFriendsButton(Screen screen) {
        var widgets = ScreenWidgets.of(screen);
        int x = -1, width = 204, bottom = 0;
        for (AbstractWidget b : widgets) {
            if (b.getY() + b.getHeight() >= bottom) {
                bottom = b.getY() + b.getHeight();
                x = b.getX();
                width = b.getWidth();
            }
        }
        if (x < 0) return;
        int n = SocialNotifier.badge(), q = SocialNotifier.ready();
        String label = n > 0 ? "Friends & Chat (" + n + ")" : "Friends & Chat";
        String quests = q > 0 ? "Quests (" + q + ")" : "Quests";
        int left = screen.width / 2 - width / 2, y = Math.min(bottom + 4, screen.height - 24), half = (width - 4) / 2;
        widgets.add(Button.builder(Component.literal(label), b -> SocialScreen.show(screen))
                .bounds(left, y, half, 20).build());
        widgets.add(Button.builder(Component.literal(quests), b -> QuestsScreen.show(screen))
                .bounds(left + width - half, y, half, 20).build());
    }
}
