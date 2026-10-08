package dev.dusk.client.modules.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.MenuScreen;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.gui.Vanilla;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * {@link ConfirmDisconnect}'s "Are you sure?" screen, in the Dusk menu's
 * look: the question, then Back and Disconnect (or Quit) as grey pixel
 * buttons, the leaving one in red. Enter leaves, Esc goes back.
 */
final class ConfirmDisconnectScreen extends MenuScreen {
    private static final int BUTTON_W = 110;
    private static final int GAP = 8;

    private final boolean local;
    private final int delayTicks;
    private final boolean confirmOnLeft;
    private final Runnable disconnect;
    private int ticks;

    ConfirmDisconnectScreen(Screen parent, boolean local, int delayTicks, boolean confirmOnLeft, Runnable disconnect) {
        super(Component.literal(local ? "Are you sure you want to quit?" : "Are you sure you want to disconnect?"), parent);
        this.local = local;
        this.delayTicks = delayTicks;
        this.confirmOnLeft = confirmOnLeft;
        this.disconnect = disconnect;
    }

    private boolean ready() { return ticks >= delayTicks; }

    private int rowY() { return this.height / 2; }
    private int leftX() { return this.width / 2 - GAP / 2 - BUTTON_W; }
    private int rightX() { return this.width / 2 + GAP / 2; }
    private int backX() { return confirmOnLeft ? rightX() : leftX(); }
    private int leaveX() { return confirmOnLeft ? leftX() : rightX(); }

    @Override
    public void tick() {
        super.tick();
        ticks++;
    }

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        int y = rowY();
        Px.label(c, getTitle().getString(), 0, y - Px.H - 12, this.width, Px.H, Px.Size.S16, Px.Tone.ACTIVE,
                false, false, false, 0xFF);

        boolean on = ready();
        boolean backHot = on && Vanilla.inside(mouseX, mouseY, backX(), y, BUTTON_W, Px.H);
        boolean leaveHot = on && Vanilla.inside(mouseX, mouseY, leaveX(), y, BUTTON_W, Px.H);
        Px.box(c, backX(), y, BUTTON_W, Px.H, Theme.Family.GREY, backHot, !on, 0xFF);
        Px.label(c, "Back", backX(), y, BUTTON_W, Px.H, Px.Size.S16, Px.Tone.GREY, backHot, !on, false, 0xFF);
        String leave = local ? "Quit" : "Disconnect";
        if (!on) leave += " (" + (delayTicks - ticks + 19) / 20 + ")";
        Px.box(c, leaveX(), y, BUTTON_W, Px.H, Theme.Family.GREY, leaveHot, !on, 0xFF);
        Px.label(c, leave, leaveX(), y, BUTTON_W, Px.H, Px.Size.S16, Px.Tone.RED, leaveHot, !on, false, 0xFF);
    }

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        if (button != 0 || !ready()) return false;
        int y = rowY();
        if (Vanilla.inside(mx, my, backX(), y, BUTTON_W, Px.H)) {
            onClose();
        } else if (Vanilla.inside(mx, my, leaveX(), y, BUTTON_W, Px.H)) {
            disconnect.run();
        } else {
            return false;
        }
        return true;
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && ready()) {
            disconnect.run();
            return true;
        }
        return false;
    }
}
