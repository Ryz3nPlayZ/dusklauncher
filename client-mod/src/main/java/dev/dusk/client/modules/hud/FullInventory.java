package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Flex-HUD's full-inventory warning, with the same chime when it fills up. */
public class FullInventory extends TextHud {
    private final BoolSetting playSound = add(new BoolSetting("playSound", "Play a sound", true));

    private boolean full;

    public FullInventory() {
        super("fullinventory", "Full Inventory", "", "Warns you when every inventory slot is taken.");
        setPosition(150, 148);
        showLabel.set(false);
        valueColor.set(0xFFFF0000);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.AIR)) {
                full = false;
                return;
            }
        }

        if (!full && playSound.get()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 1.0f, 2.0f));
        }
        full = true;
    }

    @Override
    protected String value(HudContext ctx) {
        return full ? "Inventory full!" : null;
    }

    @Override
    protected String sample() {
        return "Inventory full!";
    }
}
