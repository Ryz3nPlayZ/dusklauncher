package dev.dusk.client.gui.widget;

/** A screen that can show {@link Popup}s over itself. */
public interface PopupHost {
    void open(Popup popup);
}
