package dev.dusk.client.gui;

import com.mojang.realmsclient.RealmsMainScreen;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.config.DuskConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dusk main menu, laid out after Figma frame 7 (1280x832): the launcher's
 * scene behind the wordmark, Singleplayer / Multiplayer / Options stacked in
 * the middle with Quit near the bottom, four square shortcuts top-right and
 * the player's skin, flat and front-on, with a name tag on the left. Every
 * Figma position is scaled by the smaller of width/1280 and height/832.
 */
public class DuskTitleScreen extends DuskScreen {
    private static final String WORDMARK = "DUSK";

    private record Action(int x, int y, int w, int h, String label, @Nullable Icons icon, String tip, Theme.Kind kind, Runnable run) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private final List<Action> actions = new ArrayList<>();
    private int titleY, titleScale, iconScale;
    private float labelScale;
    private int skinX, skinY, skinScale; // skinScale 0 = no room for the skin

    public DuskTitleScreen() {
        super(Component.literal("DUSK"));
    }

    @Override
    protected void init() {
        this.clearWidgets();
        actions.clear();
        float u = Math.min(this.width / 1280f, this.height / 832f);
        int ox = Math.round((this.width - 1280 * u) / 2), oy = Math.round((this.height - 832 * u) / 2);
        int cx = this.width / 2;

        // centre column: 252x58 plates, 12 apart, the first at y=310; Quit at y=728
        int bw = Math.min(this.width - 16, Math.max(120, Math.round(252 * u)));
        int bh = Math.max(20, Math.round(58 * u)), gap = Math.max(4, Math.round(12 * u));
        int y = oy + Math.round(310 * u);
        boolean dawn = Theme.style() == Theme.Style.DAWN;
        add(cx - bw / 2, y, bw, bh, "SINGLEPLAYER", dawn ? Theme.Kind.FOCUSED : Theme.Kind.NORMAL,
                () -> open(new SelectWorldScreen(this)));
        y += bh + gap;
        add(cx - bw / 2, y, bw, bh, "MULTIPLAYER", Theme.Kind.NORMAL, () -> open(new JoinMultiplayerScreen(this)));
        y += bh + gap;
        add(cx - bw / 2, y, bw, bh, "OPTIONS", Theme.Kind.NORMAL,
                () -> { if (this.minecraft != null) open(Compat.optionsScreen(this, this.minecraft)); });
        y += bh + gap;
        int quitY = Math.max(y + gap, this.height - Math.round(46 * u) - bh);
        add(cx - bw / 2, quitY, bw, bh, "QUIT GAME", Theme.Kind.NORMAL,
                () -> { if (this.minecraft != null) this.minecraft.stop(); });

        labelScale = 1f;
        for (float s : new float[] {3f, 2f, 1.5f}) {
            if (this.font.width("SINGLEPLAYER") * s <= bw - 16 && 7 * s <= bh * 0.5f) { labelScale = s; break; }
        }

        // wordmark: Figma's LOGO sits 51 above the first button
        titleScale = Math.max(2, Math.round(36 * u / 7));
        titleY = Math.max(dawn ? 8 * titleScale + 6 : 4, oy + Math.round(310 * u) - Math.round(51 * u) - 7 * titleScale);

        // top-right shortcuts: 58x58, 10 from the edge and 10 apart
        int ib = Math.max(20, Math.round(58 * u)), m = Math.max(4, Math.round(10 * u));
        iconScale = Math.max(1, ib / 14);
        int ix = this.width - m - ib;
        icon(ix, m, ib, Icons.ACCESS, "Accessibility", () -> {
            if (this.minecraft != null) open(new AccessibilityOptionsScreen(this, this.minecraft.options));
        });
        ix -= ib + m;
        icon(ix, m, ib, Icons.GLOBE, "Language", () -> {
            if (this.minecraft != null) open(new LanguageSelectScreen(this, this.minecraft.options, this.minecraft.getLanguageManager()));
        });
        ix -= ib + m;
        icon(ix, m, ib, Icons.REALMS, "Realms", () -> open(new RealmsMainScreen(this)));
        ix -= ib + m;
        icon(ix, m, ib, Icons.GRID, "Dusk Menu", () -> open(new DuskSettingsScreen(this)));

        // skin: a 227x303 box at (236,249), the name tag above it
        skinScale = 0;
        if (DuskConfig.get().showAccountTile && this.minecraft != null) {
            int s = Math.max(2, Math.round(303 * u / 32));
            int sx = ox + Math.round(349.5f * u), sy = oy + Math.round(400.5f * u) - 16 * s;
            if (sx + 8 * s + 6 <= cx - bw / 2 && sy >= 24) {
                skinScale = s;
                skinX = sx - 8 * s;
                skinY = sy;
            }
        }
    }

    private void add(int x, int y, int w, int h, String label, Theme.Kind kind, Runnable run) {
        actions.add(new Action(x, y, w, h, label, null, "", kind, run));
    }

    private void icon(int x, int y, int size, Icons icon, String tip, Runnable run) {
        actions.add(new Action(x, y, size, size, "", icon, tip, Theme.Kind.NORMAL, run));
    }

    private void open(Screen screen) {
        if (this.minecraft != null) Compat.setScreen(this.minecraft, screen);
    }

    private String username() {
        return this.minecraft != null ? this.minecraft.getUser().getName() : "";
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected boolean vanillaBackground() {
        return !DuskConfig.get().titleScene;
    }

    @Override
    protected void drawBackgroundOverlay(Canvas c) {
        if (DuskConfig.get().titleScene) TitleScene.draw(c, this.width, this.height);
        else c.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    protected void drawOverlay(Canvas c, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int tw = Theme.wordmarkWidth(c, WORDMARK, titleScale);
        if (Theme.style() == Theme.Style.DAWN) {
            int sunUnit = Math.max(1, titleScale / 2);
            Theme.sun(c, cx - 14 * sunUnit, titleY - 16 * sunUnit - 4, sunUnit);
        }
        Theme.wordmark(c, WORDMARK, cx - tw / 2, titleY, titleScale);

        Action tipFor = null;
        for (Action a : actions) {
            boolean hover = a.contains(mouseX, mouseY);
            Theme.button(c, a.x, a.y, a.w, a.h, hover, a.kind);
            if (a.icon != null) {
                int col = Theme.style() == Theme.Style.DUSK ? (hover ? Theme.ACTIVE_UP : Theme.LABEL_UP) : Theme.buttonText(hover, a.kind);
                int iw = a.icon.width() * iconScale, ih = a.icon.height() * iconScale;
                a.icon.draw(c, a.x + (a.w - iw) / 2, a.y + (a.h - ih) / 2, col, iconScale);
                if (hover) tipFor = a;
                continue;
            }
            int lw = Theme.labelWidth(c, a.label, labelScale);
            int lx = a.x + (a.w - lw) / 2, ly = a.y + Math.round((a.h - 7 * labelScale) / 2);
            if (Theme.style() == Theme.Style.DUSK && a.kind == Theme.Kind.NORMAL) {
                Theme.label(c, a.label, lx, ly, hover ? Theme.ACTIVE_UP : Theme.LABEL_UP, hover ? Theme.ACTIVE_LO : Theme.LABEL_LO, labelScale);
            } else {
                Theme.label(c, a.label, lx, ly, Theme.buttonText(hover, a.kind), Theme.buttonText(hover, a.kind), labelScale);
            }
        }
        if (tipFor != null) {
            int w = c.textWidth(tipFor.tip) + 8;
            int x = Math.max(2, Math.min(this.width - 2 - w, tipFor.x + tipFor.w / 2 - w / 2)), y = tipFor.y + tipFor.h + 3;
            c.fill(x, y, x + w, y + 13, 0x80000000);
            c.text(tipFor.tip, x + 4, y + 3, 0xFFFFFFFF, false);
        }

        if (skinScale > 0) drawSkin(c);

        String version = "Dusk " + modVersion() + "  ·  Minecraft " + mcVersion();
        c.text(version, 4, this.height - 11, Theme.TEXT_FAINT, true);
    }

    /** The local player's skin laid flat, front-on, over its outer layer; with the name tag above. */
    private void drawSkin(Canvas c) {
        if (this.minecraft == null) return;
        String tex = Compat.localSkin(this.minecraft);
        int aw = Compat.localSkinSlim(this.minecraft) ? 3 : 4;
        int s = skinScale;
        c.push();
        c.translate(skinX, skinY);
        c.scale(s, s);
        // {x, y, w, h, base u, base v, overlay u, overlay v}; the player's right side is on our left
        int[][] parts = {
                {4, 0, 8, 8, 8, 8, 40, 8},             // head, hat
                {4, 8, 8, 12, 20, 20, 20, 36},         // body, jacket
                {4 - aw, 8, aw, 12, 44, 20, 44, 36},   // right arm, sleeve
                {12, 8, aw, 12, 36, 52, 52, 52},       // left arm, sleeve
                {4, 20, 4, 12, 4, 20, 4, 36},          // right leg, trousers
                {8, 20, 4, 12, 20, 52, 4, 52},         // left leg, trousers
        };
        for (int[] p : parts) c.blit(tex, p[0], p[1], p[4], p[5], p[2], p[3], 64, 64);
        for (int[] p : parts) c.blit(tex, p[0], p[1], p[6], p[7], p[2], p[3], 64, 64);
        c.pop();

        // name tag: black at 50%, white text, centred over the head
        String name = username();
        int tw = c.textWidth(name) + 12, th = 15;
        int tx = skinX + 8 * s - tw / 2, ty = skinY - th - Math.max(4, s * 2);
        c.fill(tx, ty, tx + tw, ty + th, 0x80000000);
        c.text(name, tx + 6, ty + (th - 7) / 2, 0xFFFFFFFF, false);
    }

    private String mcVersion() {
        return this.minecraft != null ? this.minecraft.getLaunchedVersion() : "";
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("duskclient")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (button != 0) return false;
        for (Action a : actions) {
            if (a.contains(mx, my)) {
                a.run.run();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onKey(int key, int scancode, int modifiers) {
        if (isSettingsKey(key, scancode)) {
            open(new DuskSettingsScreen(this));
            return true;
        }
        return key == GLFW.GLFW_KEY_ESCAPE; // the title screen has nowhere to go back to
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
