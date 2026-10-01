package dev.dusk.client.modules.misc;

import dev.dusk.client.compat.Compat;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** {@link ConfirmDisconnect}'s "Are you sure?" screen: Back, or leave for real. */
final class ConfirmDisconnectScreen extends Screen {
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 48;

    private final Screen parent;
    private final boolean local;
    private final int delayTicks;
    private final boolean confirmOnLeft;
    private final Runnable disconnect;
    private Button back;
    private Button leave;
    private int ticks;

    ConfirmDisconnectScreen(Screen parent, boolean local, int delayTicks, boolean confirmOnLeft, Runnable disconnect) {
        super(Component.literal(local ? "Are you sure you want to quit?" : "Are you sure you want to disconnect?"));
        this.parent = parent;
        this.local = local;
        this.delayTicks = delayTicks;
        this.confirmOnLeft = confirmOnLeft;
        this.disconnect = disconnect;
    }

    @Override
    protected void init() {
        int titleWidth = font.width(title);
        addRenderableWidget(new StringWidget(width / 2 - titleWidth / 2, 40, titleWidth, font.lineHeight, title, font));

        int y = Math.max(60, (height - BUTTON_HEIGHT) / 4 + 100);
        int left = width / 2 - GAP / 2 - BUTTON_WIDTH;
        int right = width / 2 + GAP / 2;
        back = addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(confirmOnLeft ? right : left, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        leave = addRenderableWidget(Button.builder(Component.literal(local ? "Quit" : "Disconnect"), b -> disconnect.run())
                .bounds(confirmOnLeft ? left : right, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        setActive(ticks >= delayTicks);
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks >= delayTicks) setActive(true);
    }

    private void setActive(boolean active) {
        back.active = active;
        leave.active = active;
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
