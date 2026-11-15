package dev.dusk.client;

import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.LoadoutWatcher;
import dev.dusk.client.gui.DuskSettingsScreen;
import dev.dusk.client.gui.HudEditorScreen;
import dev.dusk.client.hud.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudRenderer;
import dev.dusk.client.media.ScreenRecorder;
import dev.dusk.client.module.ModuleManager;
import dev.dusk.client.modules.misc.Clips;
import dev.dusk.client.util.Guard;
import dev.dusk.client.modules.hud.ArmorStatus;
import dev.dusk.client.modules.hud.BedwarsResources;
import dev.dusk.client.modules.hud.Clock;
import dev.dusk.client.modules.hud.ComboDisplay;
import dev.dusk.client.modules.hud.Coordinates;
import dev.dusk.client.modules.hud.CpsCounter;
import dev.dusk.client.modules.hud.FpsDisplay;
import dev.dusk.client.modules.hud.HeldItem;
import dev.dusk.client.modules.hud.ItemCounter;
import dev.dusk.client.modules.hud.Keystrokes;
import dev.dusk.client.modules.hud.LowDurability;
import dev.dusk.client.modules.hud.Memory;
import dev.dusk.client.modules.hud.Ping;
import dev.dusk.client.modules.hud.Playtime;
import dev.dusk.client.modules.hud.PotionEffects;
import dev.dusk.client.modules.hud.Reach;
import dev.dusk.client.modules.hud.ServerAddress;
import dev.dusk.client.modules.hud.SneakStatus;
import dev.dusk.client.modules.hud.Speed;
import dev.dusk.client.modules.hud.SprintStatus;
import dev.dusk.client.modules.hud.Tps;
import dev.dusk.client.modules.misc.BackgroundFps;
import dev.dusk.client.modules.misc.LowInputLag;
import dev.dusk.client.modules.misc.MouseTweaks;
import dev.dusk.client.modules.misc.TntCountdown;
import dev.dusk.client.modules.movement.ToggleSprint;
import dev.dusk.client.modules.render.BlockHighlight;
import dev.dusk.client.modules.render.CrispVanillaCrosshair;
import dev.dusk.client.modules.render.CustomCrosshair;
import dev.dusk.client.modules.render.DamageTint;
import dev.dusk.client.modules.render.FovChanger;
import dev.dusk.client.modules.render.Freecam;
import dev.dusk.client.modules.render.Freelook;
import dev.dusk.client.modules.render.Fullbright;
import dev.dusk.client.modules.render.Hitbox;
import dev.dusk.client.modules.render.LowFire;
import dev.dusk.client.modules.render.CleanVoid;
import dev.dusk.client.modules.render.MotionBlur;
import dev.dusk.client.modules.render.Nametags;
import dev.dusk.client.modules.render.Particles;
import dev.dusk.client.modules.render.ScoreboardTweaks;
import dev.dusk.client.modules.render.TabPing;
import dev.dusk.client.modules.render.Zoom;
import dev.dusk.client.render.MotionBlurRenderer;
import dev.dusk.client.util.ClickTracker;
import dev.dusk.client.util.TpsTracker;
import net.minecraft.client.Minecraft;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.DuskTitleScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * The 1.8.9 port's entry point: registers every module, loads
 * config/duskclient-hud.json (same ids as 1.21.11, so configs copy over) and
 * runs the event wiring that the modern build does through its own hooks —
 * ticks, HUD overlay, crosshair, clicks, zoom, FOV and the world passes.
 */
@SideOnly(Side.CLIENT)
@Mod(modid = DuskClient.MODID, name = "Dusk Client", useMetadata = true, clientSideOnly = true)
public class DuskClient {
    public static final String MODID = "duskclient";

    private static ModuleManager modules;
    private static KeyBinding menuKey;

    public static ModuleManager modules() { return modules; }

    public static KeyBinding settingsKey() { return menuKey; }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        modules = new ModuleManager();
        menuKey = new KeyBinding("key.duskclient.settings", Keyboard.KEY_RSHIFT, "Dusk Client");
        net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(menuKey);

        // HUD elements, in the modern build's order so a copied config reads naturally
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
        modules.register(new LowDurability());
        modules.register(new ItemCounter());
        modules.register(new BedwarsResources());
        modules.register(new Coordinates());
        modules.register(new Speed());
        modules.register(new Clock());
        modules.register(new Playtime());
        modules.register(new Memory());
        modules.register(new ServerAddress());
        // Movement / render / misc
        modules.register(new ToggleSprint());
        modules.register(new CustomCrosshair());
        modules.register(new Fullbright());
        modules.register(new MotionBlur());
        modules.register(new TntCountdown());
        modules.register(new FovChanger());
        modules.register(new LowFire());
        modules.register(new CleanVoid());
        modules.register(new DamageTint());
        modules.register(new Hitbox());
        modules.register(new Particles());
        modules.register(new Nametags());
        modules.register(new ScoreboardTweaks());
        modules.register(new TabPing());
        modules.register(new BlockHighlight());
        modules.register(new BackgroundFps());
        modules.register(new MouseTweaks());
        modules.register(new Zoom());
        modules.register(new Freelook());
        modules.register(new Freecam());
        modules.register(new Clips());
        if (LowInputLag.available()) modules.register(new LowInputLag());

        modules.loadConfig();
        Guard.run("cosmetics init", null, CosmeticsManager::init);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.START) {
            Guard.run("loadout watch", null, LoadoutWatcher::tick);
            // before the player reads its keys: latched sprint/sneak have to win
            final ToggleSprint sprint = modules.get(ToggleSprint.class);
            if (sprint != null) Guard.run("ToggleSprint tick", sprint, () -> sprint.preTick(mc));
            final Zoom zoom = modules.get(Zoom.class);
            if (zoom != null) Guard.run("Zoom keys", zoom, () -> zoom.tickKeys(mc));
            final Fullbright fullbright = modules.get(Fullbright.class);
            if (fullbright != null) Guard.run("Fullbright keys", fullbright, () -> {
                if (fullbright.tickKeys()) modules.markDirty();
            });
            final Freecam freecam = modules.get(Freecam.class);
            if (freecam != null) Guard.run("Freecam tick", freecam, () -> freecam.tickKeys(mc));
            final Freelook freelook = modules.get(Freelook.class);
            if (freelook != null) Guard.run("Freelook keys", freelook, () -> freelook.tickKeys(mc));
            final Clips clips = modules.get(Clips.class);
            if (clips != null) {
                Guard.run("Clips keys", clips, () -> clips.tickKeys(mc));
                // runs while Clips is off too: that is what ends the encoder
                Guard.run("Clips", clips, () -> ScreenRecorder.tick(mc));
            }
            // in-world only; the pause menu has its own key handler and button below
            if (mc.thePlayer != null && mc.currentScreen == null) {
                while (menuKey.isPressed()) {
                    mc.displayGuiScreen(new DuskSettingsScreen(null));
                }
            }
        } else {
            modules.tickToggleKeys(mc);
            modules.tick();
        }
    }

    /** No first-person hand while the camera is off flying. */
    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (Freecam.active() != null) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        final ToggleSprint sprint = modules == null ? null : modules.get(ToggleSprint.class);
        if (sprint != null) Guard.run("ToggleSprint keys", sprint, () -> sprint.reassert(Minecraft.getMinecraft()));
    }

    /** Container screens: Mouse Tweaks sees each mouse event before vanilla does. */
    @SubscribeEvent
    public void onGuiMouse(final GuiScreenEvent.MouseInputEvent.Pre event) {
        final MouseTweaks tweaks = modules == null ? null : modules.get(MouseTweaks.class);
        if (tweaks == null || !tweaks.enabled()) return;
        Guard.run("Mouse Tweaks", tweaks, () -> {
            if (MouseTweaks.onMouseInput(event.gui)) event.setCanceled(true);
        });
    }

    @SubscribeEvent
    public void onMouse(final MouseEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (event.button >= 0) {
            Guard.run("click tracking", null, () -> ClickTracker.onMouseButton(mc, event.button, event.buttonstate));
        }
        if (event.dwheel != 0) {
            final Zoom zoom = modules.get(Zoom.class);
            if (zoom != null) Guard.run("Zoom scroll", zoom, () -> {
                if (zoom.scroll(event.dwheel)) event.setCanceled(true);
            });
        }
        final ToggleSprint sprint = modules.get(ToggleSprint.class);
        if (sprint != null) Guard.run("ToggleSprint mouse", sprint, () -> sprint.reassert(mc));
    }

    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        final Minecraft mc = Minecraft.getMinecraft();
        Guard.run("HUD", null, () -> {
            ClickTracker.update(mc);
            Canvas canvas = new Canvas(mc);
            HudContext ctx = new HudContext(mc,
                    event.resolution.getScaledWidth(), event.resolution.getScaledHeight(),
                    event.partialTicks, mc.currentScreen instanceof HudEditorScreen);
            HudRenderer.render(canvas, ctx);
            Clips clips = modules.get(Clips.class);
            if (clips != null) clips.drawIndicator(canvas, ctx);
        });
    }

    @SubscribeEvent
    public void onCrosshair(final RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS) return;
        final Minecraft mc = Minecraft.getMinecraft();
        final CustomCrosshair crosshair = modules.get(CustomCrosshair.class);
        if (crosshair == null || !crosshair.shouldDraw(mc)) {
            // texture-pack/vanilla crosshair; cancelled only once the crisp one drew
            Guard.run("Crisp crosshair", null, () -> {
                if (CrispVanillaCrosshair.draw(mc, event.resolution)) event.setCanceled(true);
            });
            return;
        }
        Guard.run("Custom Crosshair", crosshair, () -> {
            crosshair.render(new Canvas(mc),
                    CustomCrosshair.centre(event.resolution.getScaledWidth()),
                    CustomCrosshair.centre(event.resolution.getScaledHeight()),
                    mc, event.resolution.getScaleFactor());
            // cancelled only once ours drew, so a failure leaves vanilla's crosshair
            event.setCanceled(true);
        });
    }

    @SubscribeEvent
    public void onAttack(final AttackEntityEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (event.target == null || mc.thePlayer != event.entityPlayer) return;
        Guard.run("Reach", modules.get(Reach.class), () -> Reach.onAttack(event.entityPlayer, event.target));
        Guard.run("Combo Display", modules.get(ComboDisplay.class), () -> ComboDisplay.onAttack(event.target));
        Guard.run("Particles", modules.get(Particles.class),
                () -> Particles.onAttack(mc, event.entityPlayer, event.target));
    }

    @SubscribeEvent
    public void onFovUpdate(final FOVUpdateEvent event) {
        Guard.run("FOV Changer", modules.get(FovChanger.class), () -> event.newfov = FovChanger.apply(event.fov));
    }

    @SubscribeEvent
    public void onViewFov(final EntityViewRenderEvent.FOVModifier event) {
        Guard.run("Zoom FOV", modules.get(Zoom.class), () -> {
            double divisor = Zoom.fovDivisor();
            if (divisor != 1) event.setFOV((float) (event.getFOV() / divisor));
        });
    }

    @SubscribeEvent
    public void onRenderWorldLast(final RenderWorldLastEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        Guard.run("Motion Blur", modules.get(MotionBlur.class), () -> MotionBlurRenderer.postPass(event.partialTicks));
        Guard.run("Nametags", modules.get(Nametags.class), () -> Nametags.renderWorldLast(mc, event.partialTicks));
    }

    /** Swaps vanilla's title screen for Dusk's (Settings → General → Dusk title screen). */
    @SubscribeEvent
    public void onGuiOpen(final GuiOpenEvent event) {
        if (event.gui == null || event.gui.getClass() != GuiMainMenu.class) return;
        Guard.run("Dusk title screen", null, () -> {
            if (DuskConfig.get().duskTitle) event.gui = new DuskTitleScreen();
        });
    }

    /** A "Dusk Client" bar in the pause menu's empty row, between Achievements and Options. */
    @SubscribeEvent
    public void onInitGui(final GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiIngameMenu)) return;
        Guard.run("pause menu button", null, () -> event.buttonList.add(new GuiButton(PAUSE_BUTTON_ID,
                event.gui.width / 2 - 100, event.gui.height / 4 + 56, 200, 20, "Dusk Client")));
    }

    @SubscribeEvent
    public void onAction(final GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (!(event.gui instanceof GuiIngameMenu) || event.button.id != PAUSE_BUTTON_ID) return;
        Guard.run("pause menu button", null,
                () -> Minecraft.getMinecraft().displayGuiScreen(new DuskSettingsScreen(event.gui)));
    }

    /** The menu key from the pause menu, like 1.21.11 (key bindings don't tick inside screens). */
    @SubscribeEvent
    public void onScreenKey(final GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (!(event.gui instanceof GuiIngameMenu) || menuKey == null || menuKey.getKeyCode() == 0) return;
        if (!Keyboard.getEventKeyState() || Keyboard.getEventKey() != menuKey.getKeyCode()) return;
        Guard.run("menu key", null,
                () -> Minecraft.getMinecraft().displayGuiScreen(new DuskSettingsScreen(event.gui)));
    }

    private static final int PAUSE_BUTTON_ID = 0x6475;

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Guard.run("TPS reset", null, TpsTracker::reset);
        Guard.run("cosmetics clear", null, CosmeticsManager::clear);
    }
}
