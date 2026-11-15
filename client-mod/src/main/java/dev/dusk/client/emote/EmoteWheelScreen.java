package dev.dusk.client.emote;

import dev.dusk.client.gui.Canvas;
import dev.dusk.client.gui.MenuScreen;
import dev.dusk.client.gui.Px;
import dev.dusk.client.gui.Theme;
import dev.dusk.client.modules.misc.Emotes;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The emote wheel: up to eight emotes in a ring round the cursor; point
 * at one and click to play it. The middle stops the one playing. Scroll
 * for the next eight when there are more.
 */
public final class EmoteWheelScreen extends MenuScreen {
    private static final int SLOTS = 8, RADIUS = 78, SLOT_W = 92, DEAD_ZONE = 22;

    private int page;

    public EmoteWheelScreen() {
        super(Component.literal("Emotes"), null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private List<EmoteAnimation> emotes() { return EmoteLibrary.all(); }

    private int pages() { return Math.max(1, (emotes().size() + SLOTS - 1) / SLOTS); }

    private int slotX(int i) { return this.width / 2 + (int) Math.round(Math.sin(angle(i)) * RADIUS) - SLOT_W / 2; }

    private int slotY(int i) { return this.height / 2 - (int) Math.round(Math.cos(angle(i)) * RADIUS) - Px.H / 2; }

    private static double angle(int i) { return i * (Math.PI * 2 / SLOTS); }

    /** The ring slot the mouse points at, -1 in the middle. */
    private int pointed(double mx, double my) {
        double dx = mx - this.width / 2.0, dy = this.height / 2.0 - my;
        if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) return -1;
        double a = Math.atan2(dx, dy);
        if (a < 0) a += Math.PI * 2;
        return (int) Math.round(a / (Math.PI * 2 / SLOTS)) % SLOTS;
    }

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        List<EmoteAnimation> all = emotes();
        int cx = this.width / 2, cy = this.height / 2;
        if (all.isEmpty()) {
            int y = cy - 24;
            c.centeredText("No emotes yet", cx, y, 0xFFF2F2F2, true);
            c.centeredText("Put Blockbench animation files (.json) in the emotes folder", cx, y + 14, 0xFFC6C6C6, true);
            c.centeredText("of this instance, then press Reload in the Emotes module.", cx, y + 26, 0xFFC6C6C6, true);
            if (EmoteLibrary.broken() > 0) {
                c.centeredText(EmoteLibrary.broken() + " file(s) there couldn't be read; see the game log.", cx, y + 44, 0xFFE07070, true);
            }
            return;
        }
        page = Math.min(page, pages() - 1);
        int hot = pointed(mouseX, mouseY);
        for (int i = 0; i < SLOTS; i++) {
            int idx = page * SLOTS + i;
            if (idx >= all.size()) break;
            EmoteAnimation e = all.get(idx);
            boolean on = i == hot;
            Px.box(c, slotX(i), slotY(i), SLOT_W, Px.H, Theme.Family.GREY, on, false, 0xFF);
            Px.label(c, e.name(), slotX(i), slotY(i), SLOT_W, Px.H, Px.Size.S14,
                    e == Emotes.playing() ? Px.Tone.ACCENT : Px.Tone.GREY, on, false, false, 0xFF);
        }
        boolean middleHot = hot == -1;
        String middle = Emotes.playing() != null ? "Stop" : "Close";
        int mw = 48;
        Px.box(c, cx - mw / 2, cy - Px.H / 2, mw, Px.H, Theme.Family.GREY, middleHot, false, 0xFF);
        Px.label(c, middle, cx - mw / 2, cy - Px.H / 2, mw, Px.H, Px.Size.S14, Px.Tone.GREY, middleHot, false, false, 0xFF);

        int ty = cy + RADIUS + Px.H + 8;
        if (hot >= 0 && page * SLOTS + hot < all.size() && all.get(page * SLOTS + hot).usesMolang()) {
            c.centeredText("This one uses expressions Dusk can't read yet, so parts of it stay still.", cx, ty, 0xFFE0C070, true);
            ty += 12;
        }
        if (pages() > 1) c.centeredText("Page " + (page + 1) + " of " + pages() + ", scroll for more", cx, ty, 0xFFA6A6A6, true);
    }

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        if (button != 0) return false;
        List<EmoteAnimation> all = emotes();
        int slot = all.isEmpty() ? -1 : pointed(mx, my);
        if (slot == -1) {
            if (Emotes.playing() != null) Emotes.stop();
            onClose();
            return true;
        }
        int idx = page * SLOTS + slot;
        if (idx >= all.size()) return false;
        onClose();
        Emotes.play(all.get(idx));
        return true;
    }

    @Override
    protected boolean menuScroll(double mx, double my, double amount) {
        int n = pages();
        if (n <= 1) return false;
        page = Math.floorMod(page + (amount < 0 ? 1 : -1), n);
        return true;
    }
}
