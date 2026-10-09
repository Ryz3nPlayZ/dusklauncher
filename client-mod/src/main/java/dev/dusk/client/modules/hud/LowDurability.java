package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.ItemCounts;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The durability-notifier warning: names the worn or held item closest to
 * breaking once it is down to the threshold, with a chime the moment an item
 * first gets there. Hotbar slots are watched whether held or not, so scrolling
 * past a worn-out pickaxe doesn't chime again; mending it back up does reset it.
 */
public class LowDurability extends TextHud {
    private static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
    private static final String SAMPLE = "Diamond Pickaxe: 9 uses left";

    private final IntSetting threshold = add(new IntSetting("threshold", "Warn at", 10, 1, 50, 1, "%"));
    private final BoolSetting checkArmor = add(new BoolSetting("checkArmor", "Watch armour", true));
    private final BoolSetting playSound = add(new BoolSetting("playSound", "Play a sound", true));

    /** worn slots, then hotbar 0-8: whether that slot was already low last tick */
    private final boolean[] low = new boolean[WORN.length + 9];
    private String warning;

    public LowDurability() {
        super("lowdurability", "Low Durability", "Warns you when a tool or armour piece is about to break.", 0xFFFF5555);
        setPosition(150, 170);
    }

    private boolean isLow(ItemStack stack) {
        return stack.isDamageableItem() && ItemCounts.durabilityPercent(stack) <= threshold.get();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            warning = null;
            return;
        }
        boolean chime = false;
        for (int i = 0; i < low.length; i++) {
            ItemStack s;
            if (i < WORN.length) {
                boolean armor = WORN[i] != EquipmentSlot.OFFHAND;
                s = armor && !checkArmor.get() ? ItemStack.EMPTY : player.getItemBySlot(WORN[i]);
            } else {
                s = player.getInventory().getItem(i - WORN.length);
            }
            boolean now = isLow(s);
            if (now && !low[i]) chime = true;
            low[i] = now;
        }

        // the text: whichever worn or held item has the fewest uses left
        ItemStack worst = null;
        for (int i = 0; i <= WORN.length; i++) {
            ItemStack s = i < WORN.length ? player.getItemBySlot(WORN[i]) : player.getMainHandItem();
            if (i < WORN.length && WORN[i] != EquipmentSlot.OFFHAND && !checkArmor.get()) continue;
            if (isLow(s) && (worst == null || ItemCounts.durability(s) < ItemCounts.durability(worst))) worst = s;
        }
        if (worst == null) {
            warning = null;
        } else {
            int left = ItemCounts.durability(worst);
            warning = worst.getHoverName().getString() + ": " + left + (left == 1 ? " use left" : " uses left");
        }

        // a chime for an item that just got there, not one that was low on joining
        if (chime && playSound.get() && player.tickCount > 20) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.6f, 1.0f));
        }
    }

    @Override
    protected String text(HudContext ctx) {
        return warning;
    }

    @Override
    protected String sample() {
        return SAMPLE;
    }
}
