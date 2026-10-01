package dev.dusk.client.compat;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** A screen's widgets, live, for adding buttons from outside it (Fabric's Screens.getButtons). */
public final class ScreenWidgets {
    private ScreenWidgets() {}

    public static List<AbstractWidget> of(Screen screen) {
        return Screens.getButtons(screen);
    }
}
