package dev.dusk.client.modules.render;

import dev.dusk.client.compat.AppleSkinChannel;
import dev.dusk.client.compat.FoodCompat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.TooltipImage;
import dev.dusk.client.mixin.hunger.FoodDataAccessor;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;

/**
 * AppleSkin (Unlicense, squeek502 — see NOTICE), ported: saturation drawn
 * over the hunger bar, a flashing preview of the hunger, saturation and
 * health the held food restores, the exhaustion underlay behind the hunger
 * bar, and hunger and saturation icons in food tooltips. Same options and
 * defaults as AppleSkin's config.
 *
 * <p>Vanilla never sends exhaustion and only sends saturation now and then,
 * so like AppleSkin these come from the server: one running AppleSkin sends
 * them (see {@link AppleSkinChannel}), and in singleplayer they are read off
 * the integrated server. Not registered when AppleSkin itself is installed.
 */
public class HungerInfo extends Module {
    static final String ICONS = "duskclient:textures/hud/appleskin_icons.png";
    private static final String ALWAYS = "Always", SHIFT = "Holding Shift", NEVER = "Never";
    private static final float MAX_EXHAUSTION = 4.0F;
    private static final float REGEN_EXHAUSTION_INCREMENT = 6.0F;
    private static final int ICON = 9;

    private static HungerInfo instance;
    /** Whether the server has natural regeneration on; only a server running AppleSkin says otherwise. */
    private static boolean naturalRegeneration = true;

    private final ChoiceSetting tooltip = add(new ChoiceSetting("tooltip", "Food values in tooltips", ALWAYS, ALWAYS, SHIFT, NEVER));
    private final BoolSetting saturationOverlay = add(new BoolSetting("saturationOverlay", "Saturation overlay", true));
    private final BoolSetting foodValuesOverlay = add(new BoolSetting("foodValuesOverlay", "Held food preview", true));
    private final BoolSetting offhand = add(new BoolSetting("foodValuesWhenOffhand", "Preview food in off hand", true));
    private final BoolSetting exhaustionUnderlay = add(new BoolSetting("exhaustionUnderlay", "Exhaustion underlay", true));
    private final BoolSetting healthOverlay = add(new BoolSetting("healthOverlay", "Health restored preview", true));
    private final BoolSetting vanillaAnimations = add(new BoolSetting("vanillaAnimations", "Follow vanilla icon shake", true));
    private final IntSetting maxFlashAlpha = add(new IntSetting("maxFlashAlpha", "Preview flash opacity", 65, 0, 100, 5, "%"));

    private float unclampedFlashAlpha;
    private float flashAlpha;
    private byte alphaDir = 1;

    private final Offsets offsets = new Offsets();
    private final HeldFood heldFood = new HeldFood();

    public HungerInfo() {
        super("hungerinfo", "Hunger & Food Saturation", Category.RENDER,
                "AppleSkin: your food saturation and exhaustion on the hunger bar, what held food restores, and food values in tooltips.");
        instance = this;
        setEnabled(true);
        AppleSkinChannel.register();
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (enabled()) addTooltip(stack, lines);
        });
    }

    private static HungerInfo active() {
        HungerInfo m = instance;
        return m != null && m.enabled() ? m : null;
    }

    // ---- server sync ----

    public static void onSyncedExhaustion(float exhaustion) {
        Player player = Minecraft.getInstance().player;
        if (player != null) ((FoodDataAccessor) player.getFoodData()).dusk$setExhaustion(exhaustion);
    }

    public static void onSyncedSaturation(float saturation) {
        Player player = Minecraft.getInstance().player;
        if (player != null) player.getFoodData().setSaturation(saturation);
    }

    public static void onSyncedNaturalRegeneration(boolean on) {
        naturalRegeneration = on;
    }

    @Override
    public void tick() {
        unclampedFlashAlpha += alphaDir * 0.125F;
        if (unclampedFlashAlpha >= 1.5F) {
            alphaDir = -1;
        } else if (unclampedFlashAlpha <= -0.5F) {
            alphaDir = 1;
        }
        flashAlpha = Math.max(0F, Math.min(1F, unclampedFlashAlpha)) * Math.max(0F, Math.min(1F, maxFlashAlpha.get() / 100F));
        syncFromIntegratedServer();
    }

    /** Singleplayer has no AppleSkin server to send these; read them where the server keeps them. */
    private static void syncFromIntegratedServer() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (serverPlayer == null) return;
        FoodData food = serverPlayer.getFoodData();
        onSyncedSaturation(food.getSaturationLevel());
        onSyncedExhaustion(((FoodDataAccessor) food).dusk$getExhaustion());
    }

    private static float exhaustion(Player player) {
        return ((FoodDataAccessor) player.getFoodData()).dusk$getExhaustion();
    }

    // ---- HUD, called from HungerHudMixin around vanilla's food and heart rendering ----

    /** Before vanilla draws the hunger bar, so the exhaustion bar sits behind it. */
    public static void onPreRenderFood(Canvas c, Player player, int top, int right) {
        HungerInfo m = active();
        if (m == null || !m.exhaustionUnderlay.get()) return;
        drawExhaustionOverlay(c, exhaustion(player), right, top, 1f);
    }

    public static void onRenderFood(Canvas c, Player player, int top, int right, int guiTicks) {
        HungerInfo m = active();
        if (m == null || !m.shouldRenderAnyOverlays()) return;
        FoodData stats = player.getFoodData();
        boolean saturation = m.saturationOverlay.get();

        if (saturation) m.drawSaturationOverlay(c, player, 0, stats.getSaturationLevel(), right, top, 1F, guiTicks);

        Held result = m.heldFood.result(guiTicks, player, m);
        if (result == null) {
            m.resetFlash();
            return;
        }

        if (m.foodValuesOverlay.get()) {
            int foodHunger = result.food().nutrition();
            float foodSaturationIncrement = result.food().saturation();

            m.drawHungerOverlay(c, player, foodHunger, stats.getFoodLevel(), right, top, m.flashAlpha,
                    FoodCompat.isRotten(result.stack()), guiTicks);

            int newFoodValue = stats.getFoodLevel() + foodHunger;
            float newSaturationValue = stats.getSaturationLevel() + foodSaturationIncrement;

            if (saturation) {
                float saturationGained = newSaturationValue > newFoodValue
                        ? newFoodValue - stats.getSaturationLevel() : foodSaturationIncrement;
                m.drawSaturationOverlay(c, player, saturationGained, stats.getSaturationLevel(), right, top, m.flashAlpha, guiTicks);
            }
        }
    }

    public static void onRenderHealth(Canvas c, Player player, int left, int top, int guiTicks) {
        HungerInfo m = active();
        if (m == null || !m.shouldRenderAnyOverlays()) return;

        Held result = m.heldFood.result(guiTicks, player, m);
        if (result == null) {
            m.resetFlash();
            return;
        }

        if (m.shouldShowEstimatedHealth(player, guiTicks)) {
            float foodHealthIncrement = estimatedHealthIncrement(player, result);
            float currentHealth = player.getHealth();
            float modifiedHealth = Math.min(currentHealth + foodHealthIncrement, player.getMaxHealth());
            if (currentHealth < modifiedHealth) {
                m.drawHealthOverlay(c, player, currentHealth, modifiedHealth, left, top, m.flashAlpha, guiTicks);
            }
        }
    }

    private void drawSaturationOverlay(Canvas c, Player player, float saturationGained, float saturationLevel,
                                       int right, int top, float alpha, int guiTicks) {
        if (saturationLevel + saturationGained < 0) return;

        int alphaColor = argb(1F, 1F, 1F, alpha);
        float modifiedSaturation = Math.max(0, Math.min(saturationLevel + saturationGained, 20));

        int startSaturationBar = 0;
        int endSaturationBar = (int) Math.ceil(modifiedSaturation / 2.0F);

        // when drawing the gained saturation, start from the tail of the current saturation
        if (saturationGained != 0) startSaturationBar = (int) Math.max(saturationLevel / 2.0F, 0);

        int[][] foodBarOffsets = offsets.food(guiTicks, player, this);
        for (int i = startSaturationBar; i < endSaturationBar; ++i) {
            int[] offset = i < foodBarOffsets.length ? foodBarOffsets[i] : ORIGIN;
            int x = right + offset[0];
            int y = top + offset[1];

            int u = 0;
            float effectiveSaturationOfBar = (modifiedSaturation / 2.0F) - i;
            if (effectiveSaturationOfBar >= 1) u = 3 * ICON;
            else if (effectiveSaturationOfBar > .5) u = 2 * ICON;
            else if (effectiveSaturationOfBar > .25) u = ICON;

            c.blit(ICONS, x, y, u, 0, ICON, ICON, 256, 256, alphaColor);
        }
    }

    private void drawHungerOverlay(Canvas c, Player player, int hungerRestored, int foodLevel, int right, int top,
                                   float alpha, boolean rotten, int guiTicks) {
        if (hungerRestored <= 0) return;

        int alphaColor = argb(1F, 1F, 1F, alpha);
        int modifiedFood = Math.max(0, Math.min(20, foodLevel + hungerRestored));

        int startFoodBars = Math.max(0, foodLevel / 2);
        int endFoodBars = (int) Math.ceil(modifiedFood / 2.0F);

        int[][] foodBarOffsets = offsets.food(guiTicks, player, this);
        for (int i = startFoodBars; i < endFoodBars; ++i) {
            int[] offset = i < foodBarOffsets.length ? foodBarOffsets[i] : ORIGIN;
            int x = right + offset[0];
            int y = top + offset[1];

            // very faint background
            c.blit(foodSprite(rotten, "empty"), x, y, 0, 0, ICON, ICON, ICON, ICON, argb(1F, 1F, 1F, alpha * 0.25F));

            boolean isHalf = i * 2 + 1 == modifiedFood;
            c.blit(foodSprite(rotten, isHalf ? "half" : "full"), x, y, 0, 0, ICON, ICON, ICON, ICON, alphaColor);
        }
    }

    private void drawHealthOverlay(Canvas c, Player player, float health, float modifiedHealth, int left, int top,
                                   float alpha, int guiTicks) {
        if (modifiedHealth <= health) return;

        int alphaColor = argb(1F, 1F, 1F, alpha);
        int fixedModifiedHealth = (int) Math.ceil(modifiedHealth);
        boolean hardcore = player.level().getLevelData().isHardcore();

        int startHealthBars = (int) Math.max(0, (Math.ceil(health) / 2.0F));
        int endHealthBars = (int) Math.max(0, Math.ceil(modifiedHealth / 2.0F));

        int[][] healthBarOffsets = offsets.health(guiTicks, player, this);
        for (int i = startHealthBars; i < endHealthBars; ++i) {
            int[] offset = i < healthBarOffsets.length ? healthBarOffsets[i] : ORIGIN;
            int x = left + offset[0];
            int y = top + offset[1];

            // very faint background
            c.blit(heartSprite(hardcore, "container"), x, y, 0, 0, ICON, ICON, ICON, ICON, argb(1F, 1F, 1F, alpha * 0.25F));

            boolean isHalf = i * 2 + 1 == fixedModifiedHealth;
            c.blit(heartSprite(hardcore, isHalf ? "half" : "full"), x, y, 0, 0, ICON, ICON, ICON, ICON, alphaColor);
        }
    }

    private static void drawExhaustionOverlay(Canvas c, float exhaustion, int right, int top, float alpha) {
        // clamp between 0 and 1
        float ratio = Math.min(1, Math.max(0, exhaustion / MAX_EXHAUSTION));
        int width = (int) (ratio * 81);
        c.blit(ICONS, right - width, top, 81 - width, 18, width, ICON, 256, 256, argb(1F, 1F, 1F, 0.75F * alpha));
    }

    private boolean shouldRenderAnyOverlays() {
        return foodValuesOverlay.get() || saturationOverlay.get() || healthOverlay.get();
    }

    private void resetFlash() {
        unclampedFlashAlpha = flashAlpha = 0;
        alphaDir = 1;
    }

    private boolean shouldShowEstimatedHealth(Player player, int guiTicks) {
        if (!healthOverlay.get()) return false;
        // no offsets means health is too large to draw
        if (offsets.health(guiTicks, player, this).length == 0) return false;
        // health comes back faster on peaceful
        if (player.level().getDifficulty() == Difficulty.PEACEFUL) return false;
        // anything else changing health would make the estimate confusing
        if (player.getFoodData().getFoodLevel() >= 18) return false;
        if (player.hasEffect(MobEffects.POISON)) return false;
        if (player.hasEffect(MobEffects.WITHER)) return false;
        if (player.hasEffect(MobEffects.REGENERATION)) return false;
        return true;
    }

    // ---- food math (AppleSkin's FoodHelper) ----

    private static boolean canConsume(Player player, FoodProperties food) {
        return player.canEat(food.canAlwaysEat());
    }

    private static float estimatedHealthIncrement(Player player, Held held) {
        if (!player.isHurt()) return 0;

        FoodData stats = player.getFoodData();
        int foodLevel = Math.min(stats.getFoodLevel() + held.food().nutrition(), 20);
        float healthIncrement = 0;

        // health from natural regeneration
        if (foodLevel >= 18.0F && naturalRegeneration) {
            float saturationLevel = Math.min(stats.getSaturationLevel() + held.food().saturation(), (float) foodLevel);
            healthIncrement = estimatedHealthIncrement(foodLevel, saturationLevel, exhaustion(player));
        }

        // health from a regeneration effect
        healthIncrement += FoodCompat.regenerationHealth(held.stack());
        return healthIncrement;
    }

    private static float estimatedHealthIncrement(int foodLevel, float saturationLevel, float exhaustionLevel) {
        float health = 0;
        if (!Float.isFinite(exhaustionLevel) || !Float.isFinite(saturationLevel)) return 0;

        while (foodLevel >= 18) {
            while (exhaustionLevel > MAX_EXHAUSTION) {
                exhaustionLevel -= MAX_EXHAUSTION;
                if (saturationLevel > 0) saturationLevel = Math.max(saturationLevel - 1, 0);
                else foodLevel -= 1;
            }
            // Treating near-zero saturation as zero keeps this from looping
            // forever when adding it no longer changes exhaustion.
            if (foodLevel >= 20 && Float.compare(saturationLevel, Float.MIN_NORMAL) > 0) {
                // fast regen: only health and exhaustion grow here, so jump
                // straight to the iteration that pushes exhaustion over the max
                float limitedSaturationLevel = Math.min(saturationLevel, REGEN_EXHAUSTION_INCREMENT);
                float exhaustionUntilAboveMax = Math.nextUp(MAX_EXHAUSTION) - exhaustionLevel;
                int numIterationsUntilAboveMax = Math.max(1, (int) Math.ceil(exhaustionUntilAboveMax / limitedSaturationLevel));

                health += (limitedSaturationLevel / REGEN_EXHAUSTION_INCREMENT) * numIterationsUntilAboveMax;
                exhaustionLevel += limitedSaturationLevel * numIterationsUntilAboveMax;
            } else if (foodLevel >= 18) {
                // slow regen
                health += 1;
                exhaustionLevel += REGEN_EXHAUSTION_INCREMENT;
            }
        }
        return health;
    }

    // ---- tooltip ----

    private void addTooltip(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty() || !shouldShowTooltip()) return;
        FoodProperties food = FoodCompat.food(stack);
        if (food == null) return;
        FoodTooltip overlay = new FoodTooltip(food, FoodCompat.isRotten(stack));
        if (!overlay.shouldRenderHungerBars()) return;
        try {
            lines.add(new TooltipImage.Line(overlay));
        } catch (UnsupportedOperationException ignored) {
            // the list is immutable, e.g. the item hides its tooltip
        }
    }

    private boolean shouldShowTooltip() {
        if (tooltip.is(ALWAYS)) return true;
        if (!tooltip.is(SHIFT)) return false;
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
    }

    // ---- helpers ----

    private static final int[] ORIGIN = {0, 0};

    static int argb(float r, float g, float b, float a) {
        return ((int) Math.floor(a * 255.0) << 24) | ((int) Math.floor(r * 255.0) << 16)
                | ((int) Math.floor(g * 255.0) << 8) | (int) Math.floor(b * 255.0);
    }

    static String foodSprite(boolean rotten, String type) {
        return "minecraft:textures/gui/sprites/hud/food_" + type + (rotten ? "_hunger" : "") + ".png";
    }

    private static String heartSprite(boolean hardcore, String type) {
        return switch (type) {
            case "container" -> hardcore ? "minecraft:textures/gui/sprites/hud/heart/container_hardcore.png"
                    : "minecraft:textures/gui/sprites/hud/heart/container.png";
            default -> "minecraft:textures/gui/sprites/hud/heart/" + (hardcore ? "hardcore_" : "") + type + ".png";
        };
    }

    private record Held(ItemStack stack, FoodProperties food) {}

    /** The food in hand that can be eaten right now, looked up once per GUI tick. */
    private static final class HeldFood {
        private Held result;
        private int lastGuiTick;

        Held result(int guiTick, Player player, HungerInfo m) {
            if (guiTick != lastGuiTick) {
                result = query(player, m);
                lastGuiTick = guiTick;
            }
            return result;
        }

        private static Held query(Player player, HungerInfo m) {
            ItemStack heldItem = player.getMainHandItem();
            FoodProperties heldFood = FoodCompat.food(heldItem);
            boolean canConsume = heldFood != null && canConsume(player, heldFood);
            if (m.offhand.get() && !canConsume) {
                heldItem = player.getOffhandItem();
                heldFood = FoodCompat.food(heldItem);
                canConsume = heldFood != null && canConsume(player, heldFood);
            }
            return !heldItem.isEmpty() && canConsume ? new Held(heldItem, heldFood) : null;
        }
    }

    /**
     * Where each heart and shank sits, shake included. Hearts and food are
     * worked out together from vanilla's seed so the shake matches vanilla's.
     */
    private static final class Offsets {
        private static final int PREFER_HEALTH_BARS = 10, PREFER_FOOD_BARS = 10;

        private int[][] food = new int[0][];
        private int[][] health = new int[0][];
        private int lastGuiTick;
        private final Random random = new Random();

        int[][] health(int guiTick, Player player, HungerInfo m) {
            refresh(guiTick, player, m);
            return health;
        }

        int[][] food(int guiTick, Player player, HungerInfo m) {
            refresh(guiTick, player, m);
            return food;
        }

        private void refresh(int guiTick, Player player, HungerInfo m) {
            if (guiTick != lastGuiTick || food.length == 0) {
                generate(guiTick, player, m);
                lastGuiTick = guiTick;
            }
        }

        private void generate(int guiTicks, Player player, HungerInfo m) {
            float maxHealth = player.getMaxHealth();
            float absorptionHealth = (float) Math.ceil(player.getAbsorptionAmount());

            int healthBars = (int) Math.ceil((maxHealth + absorptionHealth) / 2.0F);
            // Vanilla stops drawing hearts past Integer.MAX_VALUE; stop far sooner,
            // since nobody needs offsets for thousands of hearts.
            if (healthBars < 0 || healthBars > 1000) healthBars = 0;

            int healthRows = (int) Math.ceil((float) healthBars / (float) PREFER_HEALTH_BARS);
            int healthRowHeight = Math.max(10 - (healthRows - 2), 3);

            boolean shouldAnimatedHealth = false;
            boolean shouldAnimatedFood = false;
            if (m.vanillaAnimations.get()) {
                FoodData hunger = player.getFoodData();
                // vanilla shakes the shanks when saturation is gone
                shouldAnimatedFood = hunger.getSaturationLevel() <= 0.0F && guiTicks % (hunger.getFoodLevel() * 3 + 1) == 0;
                // and the hearts when health is low
                shouldAnimatedHealth = Math.ceil(player.getHealth()) <= 4;
            }

            // the seed vanilla's Gui uses
            random.setSeed((long) (guiTicks * 312871));

            if (health.length != healthBars) health = new int[healthBars][2];
            if (food.length != PREFER_FOOD_BARS) food = new int[PREFER_FOOD_BARS][2];

            // left aligned, multiple rows, last heart first
            for (int i = healthBars - 1; i >= 0; --i) {
                int row = (int) Math.ceil((float) (i + 1) / (float) PREFER_HEALTH_BARS) - 1;
                int x = i % PREFER_HEALTH_BARS * 8;
                int y = -(row * healthRowHeight);
                if (shouldAnimatedHealth) y += random.nextInt(2);
                health[i][0] = x;
                health[i][1] = y;
            }

            // right aligned, one row
            for (int i = 0; i < PREFER_FOOD_BARS; ++i) {
                int x = -(i * 8) - 9;
                int y = 0;
                if (shouldAnimatedFood) y += random.nextInt(3) - 1;
                food[i][0] = x;
                food[i][1] = y;
            }
        }
    }
}
