package dev.dusk.client.gui;

import dev.dusk.client.DuskClient;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.gui.widget.PopupStack;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * A page of the Dusk menu (landing, module list, a module's config, the HUD
 * editor). Esc goes back one page, the menu key leaves the whole menu. Owns
 * the page's popups: they draw last and take all input while open.
 */
public abstract class MenuScreen extends DuskScreen {
    @Nullable protected final Screen parent;
    protected final PopupStack popups = new PopupStack();

    protected MenuScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected boolean inWorld() {
        return this.minecraft != null && this.minecraft.level != null;
    }

    protected static void saveModules() {
        if (DuskClient.modules() != null) DuskClient.modules().saveConfig();
    }

    /** Runs before leaving the page, either way (commit text boxes, save). */
    protected void beforeClose() {
        saveModules();
    }

    // ---- background ------------------------------------------------------------

    @Override
    protected boolean vanillaBackground() {
        return inWorld() || !DuskConfig.get().titleScene;
    }

    @Override
    protected void drawBackgroundOverlay(Canvas c) {
        if (!vanillaBackground()) {
            TitleScene.draw(c, this.width, this.height);
            c.fill(0, 0, this.width, this.height, 0x66000000);
        }
    }

    // ---- drawing and input, popups first -------------------------------------------

    protected abstract void drawMenu(Canvas c, int mouseX, int mouseY, float delta);

    @Override
    protected final void drawOverlay(Canvas c, int mouseX, int mouseY, float delta) {
        boolean blocked = popups.any();
        drawMenu(c, blocked ? -1 : mouseX, blocked ? -1 : mouseY, delta);
        popups.render(c, mouseX, mouseY, this.width, this.height);
    }

    protected boolean menuClick(double mx, double my, int button) { return false; }

    protected boolean menuDrag(double mx, double my, int button) { return false; }

    protected boolean menuRelease(double mx, double my, int button) { return false; }

    protected boolean menuScroll(double mx, double my, double amount) { return false; }

    protected boolean menuKey(int key, int scancode, int modifiers) { return false; }

    protected boolean menuChar(char ch) { return false; }

    @Override
    protected final boolean onClick(double mx, double my, int button) {
        return popups.click(mx, my, button) || menuClick(mx, my, button);
    }

    @Override
    protected final boolean onDrag(double mx, double my, int button) {
        return popups.drag(mx, my) || menuDrag(mx, my, button);
    }

    @Override
    protected final boolean onRelease(double mx, double my, int button) {
        return popups.release() || menuRelease(mx, my, button);
    }

    @Override
    protected final boolean onScroll(double mx, double my, double amount) {
        return popups.scroll(mx, my, amount) || menuScroll(mx, my, amount);
    }

    @Override
    protected final boolean onKey(int key, int scancode, int modifiers) {
        if (popups.keyPressed(key, modifiers)) return true;
        if (menuKey(key, scancode, modifiers)) return true;
        if (isSettingsKey(key, scancode)) {
            exitMenu();
            return true;
        }
        return false;
    }

    @Override
    protected final boolean onChar(char ch) {
        return popups.charTyped(ch) || menuChar(ch);
    }

    // ---- navigation --------------------------------------------------------------

    protected void open(Screen screen) {
        if (this.minecraft != null) Compat.setScreen(this.minecraft, screen);
    }

    /** Back one page. */
    @Override
    public void onClose() {
        popups.clear();
        beforeClose();
        if (this.minecraft != null) Compat.setScreen(this.minecraft, parent);
    }

    /** Leaves the menu entirely: back to whatever was open before its first page. */
    public void exitMenu() {
        popups.clear();
        beforeClose();
        Screen target = parent;
        while (target instanceof MenuScreen m) target = m.parent;
        if (this.minecraft != null) Compat.setScreen(this.minecraft, target);
    }
}
