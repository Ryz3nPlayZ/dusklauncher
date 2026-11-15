package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.ItemCounts;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * The durability-notifier warning: names the worn or held item closest to
 * breaking once it is down to the threshold, with a chime the moment an item
 * first gets there. Hotbar slots are watched whether held or not, so scrolling
 * past a worn-out sword doesn't chime again.
 */
public class LowDurability extends TextHud {
    private static final String SAMPLE = "Diamond Sword: 9 uses left";
    private static final ResourceLocation PLING = new ResourceLocation("note.pling");

    private final IntSetting threshold = add(new IntSetting("threshold", "Warn at", 10, 1, 50, 1, "%"));
    private final BoolSetting checkArmor = add(new BoolSetting("checkArmor", "Watch armour", true));
    private final BoolSetting playSound = add(new BoolSetting("playSound", "Play a sound", true));

    /** armour 0-3, then hotbar 0-8: whether that slot was already low last tick */
    private final boolean[] low = new boolean[4 + 9];
    private String warning;

    public LowDurability() {
        super("lowdurability", "Low Durability", "Warns you when a tool or armour piece is about to break.", 0xFFFF5555);
        setPosition(150, 170);
    }

    private boolean isLow(ItemStack stack) {
        return ItemCounts.hasDurability(stack) && ItemCounts.durabilityPercent(stack) <= threshold.get();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            warning = null;
            return;
        }
        boolean chime = false;
        for (int i = 0; i < low.length; i++) {
            ItemStack s = i < 4 ? checkArmor.get() ? player.inventory.armorInventory[i] : null
                    : player.inventory.mainInventory[i - 4];
            boolean now = isLow(s);
            if (now && !low[i]) chime = true;
            low[i] = now;
        }
        ItemStack worst = null;
        for (int i = 0; i <= 4; i++) {
            if (i < 4 && !checkArmor.get()) continue;
            ItemStack s = i < 4 ? player.inventory.armorInventory[i] : player.getHeldItem();
            if (isLow(s) && (worst == null || ItemCounts.durability(s) < ItemCounts.durability(worst))) worst = s;
        }
        if (worst == null) {
            warning = null;
        } else {
            int left = ItemCounts.durability(worst);
            warning = worst.getDisplayName() + ": " + left + (left == 1 ? " use left" : " uses left");
        }
        if (chime && playSound.get() && player.ticksExisted > 20) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.create(PLING, 1.0f));
        }
    }

    @Override
    protected String text(HudContext ctx) { return warning; }

    @Override
    protected String sample() { return SAMPLE; }
}
