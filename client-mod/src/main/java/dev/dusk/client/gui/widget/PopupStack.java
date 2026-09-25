package dev.dusk.client.gui.widget;

import dev.dusk.client.gui.Canvas;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** The open popups of one screen, topmost last; input goes to the topmost only. */
public class PopupStack implements PopupHost {
    private final List<Popup> popups = new ArrayList<>();

    @Override
    public void open(Popup popup) {
        popups.add(popup);
    }

    public boolean any() {
        popups.removeIf(Popup::closed);
        return !popups.isEmpty();
    }

    private Popup top() {
        return popups.get(popups.size() - 1);
    }

    public void clear() {
        for (Popup p : popups) p.close();
        popups.clear();
    }

    public void render(Canvas c, int mouseX, int mouseY, int screenW, int screenH) {
        popups.removeIf(Popup::closed);
        for (int i = 0; i < popups.size(); i++) {
            boolean top = i == popups.size() - 1;
            c.beginLayer();
            popups.get(i).render(c, top ? mouseX : -1, top ? mouseY : -1, screenW, screenH);
            c.endLayer();
        }
    }

    /** All return true (consumed) while a popup is open. */
    public boolean click(double mx, double my, int button) {
        if (!any()) return false;
        top().click(mx, my, button);
        return true;
    }

    public boolean drag(double mx, double my) {
        if (!any()) return false;
        top().drag(mx, my);
        return true;
    }

    public boolean release() {
        if (!any()) return false;
        top().release();
        return true;
    }

    public boolean scroll(double mx, double my, double amount) {
        if (!any()) return false;
        top().scroll(mx, my, amount);
        return true;
    }

    public boolean keyPressed(int key, int modifiers) {
        if (!any()) return false;
        Popup p = top();
        if (!p.keyPressed(key, modifiers) && key == GLFW.GLFW_KEY_ESCAPE) p.close();
        return true;
    }

    public boolean charTyped(char ch) {
        if (!any()) return false;
        top().charTyped(ch);
        return true;
    }
}
