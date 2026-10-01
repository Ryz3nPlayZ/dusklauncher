package dev.dusk.client.modules.hud;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Looking At, after Jade/WTHIT's idea: a small panel naming the block or mob
 * under the crosshair, with the mod it comes from, whether your tool can
 * harvest it, crop growth and other block states, a mob's health and armour,
 * horse stats and the breaking progress. Everything comes from what the
 * client already has: vanilla's own crosshair pick (no extra raycast unless
 * fluids are on), rebuilt once a tick, so it costs a few lines of text a frame.
 */
public class LookingAt extends HudElement {
    /** The reserved width the panel aligns inside, so it does not jump about as names change. */
    private static final int BOX_W = 180;
    private static final int ICON = 16, INSET = 4, LINE = 10;
    private static final int NAME = 0xFFFFFFFF, INFO = 0xFFAAAAAA, MOD = 0xFF5B7CF7, GOOD = 0xFF55FF55, BAD = 0xFFFF5555,
            HEART = 0xFFFF5555, BORDER = 0x50FFFFFF;

    private final ChoiceSetting alignment = add(new ChoiceSetting("alignment", "Alignment", "Auto", "Auto", "Left", "Center", "Right"));
    private final BoolSetting blocks = add(new BoolSetting("blocks", "Blocks", true), "Show");
    private final BoolSetting entities = add(new BoolSetting("entities", "Mobs and entities", true), "Show");
    private final BoolSetting fluids = add(new BoolSetting("fluids", "Fluids", false), "Show");
    private final ChoiceSetting details = add(new ChoiceSetting("details", "Details", "Always", "Always", "While sneaking", "Never"), "Show");
    private final BoolSetting icon = add(new BoolSetting("icon", "Item icon", true), "Show");
    private final BoolSetting modName = add(new BoolSetting("modName", "Mod name", true), "Show");
    private final BoolSetting harvest = add(new BoolSetting("harvest", "Harvest tool", true), "Show");
    private final BoolSetting states = add(new BoolSetting("states", "Growth and block states", true), "Show");
    private final BoolSetting health = add(new BoolSetting("health", "Health and armour", true), "Show");
    private final BoolSetting progress = add(new BoolSetting("progress", "Breaking progress", true), "Show");
    private final BoolSetting border = add(new BoolSetting("border", "Border", true));

    /** Rideable horses, by registry id: the horse classes and type constants move between versions. */
    private static final java.util.Set<String> HORSES = java.util.Set.of("minecraft:horse", "minecraft:donkey",
            "minecraft:mule", "minecraft:skeleton_horse", "minecraft:zombie_horse");

    private Info info;

    public LookingAt() {
        super("lookingat", "Looking At", "Names the block or mob you are looking at, like Jade or WAILA: mod, harvest tool, crop growth, health.", true);
        setPosition(230, 4);
    }

    @Override
    public void tick() {
        info = collect();
    }

    /** The panel draws its own background, sized to what it says. */
    @Override
    public boolean background() {
        return false;
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && info != null;
    }

    @Override
    public int width(HudContext ctx) {
        return Math.max(BOX_W, panelWidth(ctx, shown(ctx)));
    }

    @Override
    public int height(HudContext ctx) {
        return panelHeight(shown(ctx));
    }

    private Info shown(HudContext ctx) {
        return info != null ? info : sample();
    }

    private boolean iconOn(Info i) {
        return icon.get() && !i.stack.isEmpty();
    }

    private int panelWidth(HudContext ctx, Info i) {
        int text = 0;
        for (Line l : i.lines) text = Math.max(text, ctx.font().width(l.text));
        return INSET * 2 + (iconOn(i) ? ICON + 4 : 0) + text;
    }

    private int panelHeight(Info i) {
        int text = i.lines.size() * LINE - 1;
        int h = INSET * 2 + Math.max(iconOn(i) ? ICON : 0, text);
        return i.progress >= 0 ? h + 3 : h;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        Info i = shown(ctx);
        int pw = panelWidth(ctx, i), ph = panelHeight(i), total = width(ctx);
        String a = alignment.get();
        boolean centred = Math.abs(x() + screenWidth(ctx) / 2.0 - ctx.width() / 2.0) < ctx.width() / 6.0;
        boolean right = x() + screenWidth(ctx) / 2.0 > ctx.width() / 2.0;
        int ox;
        if (a.equals("Center") || a.equals("Auto") && centred) ox = (total - pw) / 2;
        else if (a.equals("Right") || a.equals("Auto") && right) ox = total - pw;
        else ox = 0;

        if (background.get()) c.fill(ox, 0, ox + pw, ph, backgroundColor());
        if (border.get()) c.outline(ox, 0, pw, ph, BORDER);

        int tx = ox + INSET;
        int lines = i.lines.size() * LINE - 1;
        if (iconOn(i)) {
            int iy = INSET + Math.max(0, (lines - ICON) / 2);
            c.item(i.stack, tx, iy);
            tx += ICON + 4;
        }
        int ty = INSET + Math.max(0, ((iconOn(i) ? ICON : 0) - lines) / 2);
        for (Line l : i.lines) {
            c.text(l.text, tx, ty, l.color, true);
            ty += LINE;
        }
        if (i.progress >= 0) {
            int bx0 = ox + 2, bx1 = ox + pw - 2, by = ph - 3;
            c.fill(bx0, by, bx1, by + 1, 0x60000000);
            c.fill(bx0, by, bx0 + Math.round((bx1 - bx0) * i.progress), by + 1, Theme.ACCENT);
        }
    }

    // ---- what is under the crosshair ----------------------------------------

    private record Line(Component text, int color) {}

    private static final class Info {
        ItemStack stack = ItemStack.EMPTY;
        final List<Line> lines = new ArrayList<>();
        /** 0..1 while breaking the block, else -1. */
        float progress = -1;

        void add(String text, int color) {
            lines.add(new Line(Component.literal(text), color));
        }
    }

    private Info sample() {
        Info i = new Info();
        i.stack = new ItemStack(Items.WHEAT);
        i.add("Wheat Crops", NAME);
        if (details.is("Always")) i.add("Growth: 71%", INFO);
        if (modName.get()) i.lines.add(new Line(Component.literal("Minecraft").withStyle(s -> s.withItalic(true)), MOD));
        return i;
    }

    @Nullable
    private Info collect() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.getCameraEntity() == null) return null;
        boolean more = details.is("Always") || details.is("While sneaking") && player.isShiftKeyDown();

        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult eh && entities.get()) return entity(eh.getEntity(), more);
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK && blocks.get()) {
            return block(mc, level, player, bh.getBlockPos(), more);
        }
        if (fluids.get()) {
            HitResult f = mc.getCameraEntity().pick(player.blockInteractionRange(), 0, true);
            if (f instanceof BlockHitResult fb && f.getType() == HitResult.Type.BLOCK) {
                FluidState fs = level.getFluidState(fb.getBlockPos());
                if (!fs.isEmpty()) return fluid(fs);
            }
        }
        return null;
    }

    private Info block(Minecraft mc, ClientLevel level, LocalPlayer player, BlockPos pos, boolean more) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        Info i = new Info();
        i.stack = new ItemStack(block.asItem());
        i.add(block.getName().getString(), NAME);
        if (more && states.get()) blockStates(i, state);
        if (more && harvest.get()) harvest(i, player, state);
        if (modName.get()) mod(i, BuiltInRegistries.BLOCK.getKey(block).getNamespace());
        if (progress.get() && mc.gameMode != null && mc.gameMode.isDestroying()) {
            int stage = mc.gameMode.getDestroyStage();
            if (stage >= 0) i.progress = (stage + 1) / 10f;
        }
        return i;
    }

    private Info fluid(FluidState fs) {
        Info i = new Info();
        i.stack = new ItemStack(fs.getType().getBucket());
        i.add(fs.createLegacyBlock().getBlock().getName().getString(), NAME);
        if (modName.get()) mod(i, BuiltInRegistries.FLUID.getKey(fs.getType()).getNamespace());
        return i;
    }

    private Info entity(Entity e, boolean more) {
        Info i = new Info();
        if (e instanceof ItemEntity item) {
            i.stack = item.getItem();
            i.add(item.getItem().getHoverName().getString() + (item.getItem().getCount() > 1 ? " ×" + item.getItem().getCount() : ""), NAME);
        } else if (e instanceof ItemFrame frame && !frame.getItem().isEmpty()) {
            i.stack = frame.getItem();
            i.add(e.getDisplayName().getString(), NAME);
            i.add(frame.getItem().getHoverName().getString(), INFO);
        } else {
            i.add(e.getDisplayName().getString(), NAME);
        }
        if (more && health.get() && e instanceof LivingEntity living) {
            i.add("❤ " + half(living.getHealth()) + " / " + half(living.getMaxHealth()), HEART);
            int armour = living.getArmorValue();
            if (armour > 0) i.add("Armour " + armour, INFO);
        }
        if (more && states.get()) {
            if (e instanceof LivingEntity living && living.isBaby()) i.add("Baby", INFO);
            if (e instanceof TamableAnimal pet && pet.isTame()) i.add("Tamed", INFO);
            if (e instanceof LivingEntity horse && HORSES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())) {
                double speed = horse.getAttributeValue(Attributes.MOVEMENT_SPEED) * 42.16;
                double j = horse.getAttributeValue(Attributes.JUMP_STRENGTH);
                double jump = -0.1817584952 * j * j * j + 3.689713992 * j * j + 2.128599134 * j - 0.343930367;
                i.add(String.format(Locale.ROOT, "Speed %.1f b/s  Jump %.1f b", speed, jump), INFO);
            }
        }
        if (modName.get()) mod(i, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace());
        return i;
    }

    private static String half(float v) {
        float r = Math.round(v * 2) / 2f;
        return r == Math.floor(r) ? String.valueOf((int) r) : String.valueOf(r);
    }

    private static void mod(Info i, String namespace) {
        String name = namespace.equals("minecraft") ? "Minecraft"
                : FabricLoader.getInstance().getModContainer(namespace).map(m -> m.getMetadata().getName()).orElse(namespace);
        i.lines.add(new Line(Component.literal(name).withStyle(s -> s.withItalic(true)), MOD));
    }

    // ---- block details ------------------------------------------------------------

    private static void blockStates(Info i, BlockState state) {
        Block b = state.getBlock();
        if (b instanceof CropBlock || b instanceof StemBlock || b instanceof CocoaBlock || b instanceof NetherWartBlock
                || b instanceof SweetBerryBushBlock || b instanceof PitcherCropBlock) {
            float g = growth(state);
            if (g >= 0) i.add(g >= 1 ? "Growth: mature" : "Growth: " + Math.round(g * 100) + "%", g >= 1 ? GOOD : INFO);
            return;
        }
        if (b instanceof ComposterBlock) {
            String v = value(state, "level");
            if (v != null) i.add("Compost: " + v + " / 8", INFO);
            return;
        }
        if (b instanceof LayeredCauldronBlock) {
            String v = value(state, "level");
            if (v != null) i.add("Level: " + v + " / 3", INFO);
            return;
        }
        String[][] shown = {
                {"power", "Power"}, {"delay", "Delay"}, {"mode", "Mode"}, {"note", "Note"},
                {"honey_level", "Honey"}, {"charges", "Charges"}, {"bites", "Bites"}, {"pickles", "Pickles"},
                {"eggs", "Eggs"}, {"candles", "Candles"}, {"powered", "Powered"}, {"lit", "Lit"},
        };
        for (String[] s : shown) {
            String v = value(state, s[0]);
            if (v == null) continue;
            if (v.equals("true") || v.equals("false")) {
                i.add(s[1] + ": " + (v.equals("true") ? "yes" : "no"), INFO);
            } else {
                i.add(s[1] + ": " + v.toLowerCase(Locale.ROOT), INFO);
            }
        }
    }

    /** 0..1 along the block's "age" property, or -1 without one. */
    private static float growth(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof IntegerProperty ip && p.getName().equals("age")) {
                int max = 0;
                for (Integer v : ip.getPossibleValues()) max = Math.max(max, v);
                return max == 0 ? 1 : state.getValue(ip) / (float) max;
            }
        }
        return -1;
    }

    @Nullable
    private static String value(BlockState state, String name) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(name)) return valueOf(state, p);
        }
        return null;
    }

    private static <T extends Comparable<T>> String valueOf(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    private static void harvest(Info i, LocalPlayer player, BlockState state) {
        String tool = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? "Pickaxe"
                : state.is(BlockTags.MINEABLE_WITH_AXE) ? "Axe"
                : state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? "Shovel"
                : state.is(BlockTags.MINEABLE_WITH_HOE) ? "Hoe" : null;
        if (tool == null && !state.requiresCorrectToolForDrops()) return;
        String tier = state.is(BlockTags.NEEDS_DIAMOND_TOOL) ? "diamond"
                : state.is(BlockTags.NEEDS_IRON_TOOL) ? "iron"
                : state.is(BlockTags.NEEDS_STONE_TOOL) ? "stone" : null;
        String what = (tool != null ? tool : "Tool") + (tier != null && state.requiresCorrectToolForDrops() ? " (" + tier + "+)" : "");
        if (!state.requiresCorrectToolForDrops()) {
            i.add("Tool: " + what, INFO);
        } else if (player.hasCorrectToolForDrops(state)) {
            i.add("✔ " + what, GOOD);
        } else {
            i.add("✘ " + what, BAD);
        }
    }
}
