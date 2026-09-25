package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;

/**
 * Something drawn over a screen that takes all input while open: a
 * dropdown list, the colour picker, the crosshair editor. The screen draws
 * its popups last and routes input to the topmost one only.
 */
public interface Popup {
    void render(Canvas c, int mouseX, int mouseY, int screenW, int screenH);

    /** A click outside should {@link #close()} the popup; either way the click is consumed. */
    void click(double mx, double my, int button);

    default void drag(double mx, double my) {}

    default void release() {}

    default void scroll(double mx, double my, double amount) {}

    /** Esc closes the popup when this returns false. */
    default boolean keyPressed(int key, int modifiers) { return false; }

    default void charTyped(char ch) {}

    void close();

    boolean closed();
}
