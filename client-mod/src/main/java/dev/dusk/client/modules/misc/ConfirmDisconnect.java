package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.lang.ref.WeakReference;

/**
 * Confirm Disconnect's behaviour (MicrocontrollersDev; LGPL, so written from
 * how it behaves, not its code): leaving from the pause menu first asks "Are
 * you sure?" on a screen of its own, or with Discrete confirmation on the
 * Disconnect button itself, which then has to be pressed again.
 */
public class ConfirmDisconnect extends Module {
    private static ConfirmDisconnect instance;

    private final BoolSetting discrete = add(new BoolSetting("useDiscreteConfirmation", "Discrete confirmation", false));
    private final BoolSetting singleplayer = add(new BoolSetting("enableInSingleplayer", "In singleplayer", true));
    private final BoolSetting multiplayer = add(new BoolSetting("enableInMultiplayer", "In multiplayer", true));
    private final IntSetting delay = add(new IntSetting("confirmDelay", "Delay", 0, 0, 10, 1, "s"));
    private final BoolSetting confirmOnLeft = add(new BoolSetting("confirmOnLeft", "Confirm on left", false));

    /** The pause menu button waiting for its second press in discrete mode. */
    private WeakReference<Button> armed = new WeakReference<>(null);
    private int armedTicks;

    public ConfirmDisconnect() {
        super("confirmdisconnect", "Confirm Disconnect", Category.MISC,
                "Asks before leaving a world or server from the pause menu.");
        instance = this;
        setEnabled(true);
    }

    /**
     * Called when {@code button}, the pause menu's disconnect button, is
     * pressed; true holds the press back. {@code press} presses it again for
     * real, which the confirmation screen does on Disconnect.
     */
    public static boolean holdBack(Button button, Runnable press) {
        ConfirmDisconnect m = instance;
        if (m == null || !m.enabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        boolean local = mc.isLocalServer();
        if (!(local ? m.singleplayer.get() : m.multiplayer.get())) return false;
        if (!m.discrete.get()) {
            Compat.setScreen(mc, new ConfirmDisconnectScreen(Compat.currentScreen(mc), local,
                    m.delay.get() * 20, m.confirmOnLeft.get(), press));
            return true;
        }
        if (m.armed.get() == button) return false;
        m.armed = new WeakReference<>(button);
        m.armedTicks = 0;
        button.setMessage(Component.literal("Are you sure?").withStyle(ChatFormatting.RED));
        button.active = false;
        return true;
    }

    @Override
    public void tick() {
        Button button = armed.get();
        if (button != null && !button.active && ++armedTicks >= delay.get() * 20) button.active = true;
    }
}
