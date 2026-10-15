package dev.dusk.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.realmsclient.RealmsMainScreen;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.social.SocialNotifier;
import dev.dusk.client.compat.SkinCompat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.media.MediaScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dusk main menu, proportioned after the Dawn menu mock-up
 * (dawn-client-menu.html): the launcher's scene behind the wordmark,
 * Singleplayer / Multiplayer / Options as long 7:1 bars in the middle, Quit
 * and the version at the bottom, the account pill and square shortcuts
 * top-right. Every mock-up measurement (CSS px) is scaled by {@code v}: the
 * screen's size against 1440x900, but never so small that a scale-1 label
 * is bigger than the mock-up's 11.5px text is to its button.
 */
public class DuskTitleScreen extends DuskScreen {

    private record Action(int x, int y, int w, int h, String label, @Nullable Icons icon, String tip, Theme.Kind kind, Runnable run) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }

    private final List<Action> actions = new ArrayList<>();
    private int titleY, titleScale, iconScale, labelScale, iconGap, versionY;
    @Nullable private Action account; // the pill with the player's face; null when hidden
    private int faceScale;
    /** Modern look: the version line's left edge. */
    private int edge;

    public DuskTitleScreen() {
        super(Component.literal("DUSK"));
    }

    @Override
    protected void init() {
        this.clearWidgets();
        actions.clear();
        account = null;
        Theme.sync();
        float v = Math.max(8f / 11.5f, Math.min(this.width / 1440f, this.height / 900f));
        int cx = this.width / 2;
        labelScale = Math.max(1, (int) (11.5f * v / 8f + 0.15f));
        if (Theme.modern()) {
            initModern(v);
            return;
        }

        // top bar: 16 padding, 38 squares 6 apart, 20 from the right edge
        int ib = Math.max(18, px(38, v)), ig = Math.max(2, px(6, v)), top = Math.max(4, px(16, v));
        int ix = iconRow(ib, ig, top, v);
        if (DuskConfig.get().showAccountTile && this.minecraft != null) {
            // 5 | 28 face | 10 | name | 14
            faceScale = Math.max(1, Math.round(28 * v / 8));
            int pw = px(5, v) + 8 * faceScale + px(10, v) + this.font.width(username()) + px(14, v);
            account = new Action(ix - ig - pw, top, pw, ib, "", null, "Skin Customization", Theme.Kind.NORMAL, this::openSkins);
            actions.add(account);
        }
        int topH = top * 2 + ib;

        // bottom: 220x38 Quit, 12, the version line, 18 padding
        int qw = Math.min(this.width - 16, px(220, v)), qh = Math.max(18, px(38, v));
        versionY = this.height - Math.max(4, px(18, v)) - 8;
        int quitY = versionY - Math.max(4, px(12, v)) - qh;

        // centre: the wordmark, 24 (plus its line box), then 310x44 bars 9 apart
        int bw = Math.min(this.width - 16, px(310, v)), bh = Math.max(20, px(44, v)), gap = Math.max(3, px(9, v));
        titleScale = Math.max(2, Math.round(44 * v / 8) * 2 / 3);
        int logoGap = px(30, v);
        int centreH = Theme.wordmarkHeight(titleScale) + logoGap + 3 * bh + 2 * gap;
        int free = quitY - topH - centreH;
        int y0 = topH + Math.max(0, free / 2) - px(10, v);
        titleY = Math.max(4, y0);
        int y = titleY + Theme.wordmarkHeight(titleScale) + logoGap;
        iconGap = Math.max(4, px(12, v));
        add(cx - bw / 2, y, bw, bh, "SINGLEPLAYER", Icons.PERSON, Theme.Kind.NORMAL, this::openWorlds);
        y += bh + gap;
        add(cx - bw / 2, y, bw, bh, "MULTIPLAYER", Icons.PEOPLE, Theme.Kind.NORMAL, this::openServers);
        y += bh + gap;
        add(cx - bw / 2, y, bw, bh, "OPTIONS", Icons.GEAR, Theme.Kind.NORMAL, this::openOptions);
        y += bh + gap;
        int barsH = 3 * bh + 2 * gap;
        wardrobe(cx - bw / 2, y - gap - barsH, barsH, bh, Math.max(18, px(34, v)), v);
        add(cx - qw / 2, Math.max(y, quitY), qw, qh, "QUIT GAME", null, Theme.Kind.NORMAL, this::quit);
    }

    /**
     * The modern look, in the launcher's language: the wordmark over a
     * centred stack of pills (a white Singleplayer, Multiplayer, then Options
     * and Quit side by side), round shortcuts and the account pill top-right,
     * the version bottom-left. Every gap is one step of the same scale.
     */
    private void initModern(float v) {
        int cx = this.width / 2;
        int ib = Math.max(18, px(32, v)), ig = Math.max(3, px(8, v)), top = Math.max(6, px(18, v));
        int ix = iconRow(ib, ig, top, v);
        if (DuskConfig.get().showAccountTile && this.minecraft != null) {
            // inset | face | gap | name | half the pill's height, so the name
            // sits as far from the round end as the face does from the other
            faceScale = 0;
            int faceGui = (int) Math.ceil(faceWindowPx(ib) / Px.gui());
            int inset = (ib - faceGui) / 2;
            int pw = inset + faceGui + Math.max(4, px(10, v)) + this.font.width(username()) * 17 / 20 + ib / 2;
            account = new Action(ix - ig - pw, top, pw, ib, "", null, "Skin Customization", Theme.Kind.NORMAL, this::openSkins);
            actions.add(account);
        }
        int topH = top * 2 + ib;
        edge = Math.max(6, px(20, v));
        versionY = this.height - edge - 8;

        int bh = Math.max(20, px(40, v)), gap = Math.max(4, px(8, v));
        int half = (Math.min(this.width - 16, px(320, v)) - gap) / 2, bw = 2 * half + gap;
        titleScale = Math.max(2, Math.round(44 * v / 8) * 2 / 3);
        int logoGap = Math.max(10, px(32, v)), markH = Theme.wordmarkHeight(titleScale);
        int stackH = markH + logoGap + 3 * bh + 2 * gap;
        titleY = Math.max(topH, (this.height - stackH) / 2 - px(12, v));
        int x0 = cx - bw / 2, y = titleY + markH + logoGap;
        add(x0, y, bw, bh, "Singleplayer", null, Theme.Kind.PRIMARY, this::openWorlds);
        y += bh + gap;
        add(x0, y, bw, bh, "Multiplayer", null, Theme.Kind.NORMAL, this::openServers);
        y += bh + gap;
        add(x0, y, half, bh, "Options", null, Theme.Kind.NORMAL, this::openOptions);
        add(x0 + half + gap, y, half, bh, "Quit", null, Theme.Kind.NORMAL, this::quit);
        y += bh;
        wardrobe(x0, titleY + markH + logoGap, y - titleY - markH - logoGap, bh, bh, v);
    }

    /** The account face's size in window pixels: whole pixels per texel, inset in a cell {@code ib} tall. */
    private static int faceWindowPx(int ib) {
        int room = (int) Math.round(ib * Px.gui() * 0.66);
        return Math.max(8, room / 8 * 8);
    }

    private void openSkins() {
        if (this.minecraft != null) open(new SkinCustomizationScreen(this, this.minecraft.options));
    }

    private void openWorlds() {
        open(new SelectWorldScreen(this));
    }

    private void openServers() {
        open(new JoinMultiplayerScreen(this));
    }

    private void openOptions() {
        if (this.minecraft != null) open(Compat.optionsScreen(this, this.minecraft));
    }

    private void quit() {
        if (this.minecraft != null) this.minecraft.stop();
    }

    /** The shortcut cells, right to left from the top-right corner; returns the last one's x. */
    private int iconRow(int ib, int ig, int top, float v) {
        iconScale = Math.max(1, Math.round(ib * 0.47f / 7));
        int ix = this.width - Math.max(4, px(20, v)) - ib;
        icon(ix, top, ib, Icons.ACCESS, "Accessibility", () -> {
            if (this.minecraft != null) open(new AccessibilityOptionsScreen(this, this.minecraft.options));
        });
        ix -= ib + ig;
        icon(ix, top, ib, Icons.GLOBE, "Language", () -> {
            if (this.minecraft != null) open(new LanguageSelectScreen(this, this.minecraft.options, this.minecraft.getLanguageManager()));
        });
        ix -= ib + ig;
        icon(ix, top, ib, Icons.REALMS, "Realms", () -> open(new RealmsMainScreen(this)));
        ix -= ib + ig;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            icon(ix, top, ib, Icons.SHADER, "Shaders", () -> open(new PackBrowserScreen(this, PackBrowserScreen.SHADERS)));
            ix -= ib + ig;
        }
        icon(ix, top, ib, Icons.PACK, "Resource Packs", () -> open(new PackBrowserScreen(this, PackBrowserScreen.RESOURCE_PACKS)));
        ix -= ib + ig;
        icon(ix, top, ib, Icons.MEDIA, "Media", () -> open(new MediaScreen(this, MediaScreen.SCREENSHOTS)));
        ix -= ib + ig;
        icon(ix, top, ib, Icons.CHAT, "Friends & Chat", () -> SocialScreen.show(this));
        ix -= ib + ig;
        icon(ix, top, ib, Icons.STAR, "Quests", () -> QuestsScreen.show(this));
        ix -= ib + ig;
        icon(ix, top, ib, Icons.GRID, "Dusk Menu", () -> open(new DuskSettingsScreen(this)));
        return ix;
    }

    /**
     * The player's model, spinnable, with WARDROBE under it, centred on the
     * bars in the space to their left. Left out when that space is too narrow.
     */
    private void wardrobe(int barsX, int barsY, int barsH, int bh, int bth, float v) {
        if (this.minecraft == null) return;
        int margin = Math.max(8, px(40, v));
        int btnW = this.font.width("WARDROBE") * labelScale + px(36, v);
        int bgap = Math.max(4, px(10, v));
        int mw = Math.min(px(150, v), barsX - 2 * margin);
        if (mw < 60 || btnW > barsX - 2 * margin) return;
        int mh = Math.min(mw * 2, Math.max(60, barsH + 2 * bh - bth - bgap));
        mw = Math.min(mw, mh / 2 + 10);
        int bx = barsX / 2;
        int top = barsY + barsH / 2 - (mh + bgap + bth) / 2;
        var model = SkinCompat.widget(this.minecraft, mw, mh, () -> null);
        model.setX(bx - mw / 2);
        model.setY(top);
        this.addRenderableWidget(model);
        int w = Math.max(btnW, Math.min(mw, px(180, v)));
        add(bx - w / 2, top + mh + bgap, w, bth, "WARDROBE", null, Theme.Kind.NORMAL, () -> open(new WardrobeScreen(this)));
    }

    private static int px(float css, float v) {
        return Math.round(css * v);
    }

    private void add(int x, int y, int w, int h, String label, @Nullable Icons icon, Theme.Kind kind, Runnable run) {
        actions.add(new Action(x, y, w, h, label, icon, "", kind, run));
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
        else c.fill(0, 0, this.width, this.height, Theme.pick(0x800A0B1C, 0x66000000));
    }

    @Override
    protected void drawOverlay(Canvas c, int mouseX, int mouseY, float delta) {
        if (Theme.modern()) {
            drawModern(c, mouseX, mouseY);
            return;
        }
        int cx = this.width / 2;
        Theme.wordmark(c, cx - Theme.wordmarkWidth(titleScale) / 2, titleY, titleScale);

        Action tipFor = null;
        for (Action a : actions) {
            boolean hover = a.contains(mouseX, mouseY);
            if (a.label.isEmpty() && a != account) {
                // the launcher's nav cell (px--cell): black edge, grey band, flat face, #b8b8b8 glyph
                if (Theme.modern()) Round.fill(c, a.x, a.y, a.w, a.h, 64, hover ? Theme.RAISED_2 : Theme.RAISED, hover ? Theme.LINE_2 : Theme.LINE);
                else {
                    c.fill(a.x, a.y, a.x + a.w, a.y + a.h, 0xFF000000);
                    Theme.cell(c, a.x + 1, a.y + 1, a.w - 2, a.h - 2, hover);
                }
                int iw = a.icon.width() * iconScale, ih = a.icon.height() * iconScale;
                a.icon.draw(c, a.x + (a.w - iw) / 2, a.y + (a.h - ih) / 2, Theme.filter(Theme.GLYPH, hover, false), iconScale);
                if (a.icon == Icons.CHAT) badge(c, a, SocialNotifier.badge());
                if (a.icon == Icons.STAR) badge(c, a, SocialNotifier.ready());
                if (hover) tipFor = a;
                continue;
            }
            Theme.button(c, a.x, a.y, a.w, a.h, hover, a.kind);
            int up = hover ? Theme.ACTIVE_UP : Theme.LABEL_UP;
            int lo = hover ? Theme.ACTIVE_LO : Theme.LABEL_LO;
            if (a == account) {
                drawAccount(c, a, up);
                if (hover) tipFor = a;
                continue;
            }
            // icon + label, centred together, 12 apart
            int lw = Theme.labelWidth(c, a.label, labelScale);
            int iw = a.icon == null ? 0 : a.icon.width() * labelScale + iconGap;
            int lx = a.x + (a.w - lw - iw) / 2 + iw, ly = a.y + (a.h - 7 * labelScale) / 2;
            if (a.icon != null) a.icon.draw(c, lx - iw, a.y + (a.h - a.icon.height() * labelScale) / 2, up, labelScale);
            Theme.label(c, a.label, lx, ly, up, lo, labelScale);
        }
        if (tipFor != null) tip(c, tipFor);

        String version = version();
        c.text(version, cx - c.textWidth(version) / 2, versionY, Theme.TEXT_FAINT, !Theme.modern());
    }

    private void drawModern(Canvas c, int mouseX, int mouseY) {
        Theme.wordmark(c, this.width / 2 - Theme.wordmarkWidth(titleScale) / 2, titleY, titleScale);
        Action tipFor = null;
        for (Action a : actions) {
            boolean hover = a.contains(mouseX, mouseY);
            if (a.label.isEmpty()) {
                Glass.cell(c, a.x, a.y, a.w, a.h, hover);
                if (hover) tipFor = a;
                if (a == account) {
                    drawAccountModern(c, a, hover);
                    continue;
                }
                int iw = a.icon.width() * iconScale, ih = a.icon.height() * iconScale;
                a.icon.draw(c, a.x + (a.w - iw) / 2, a.y + (a.h - ih) / 2, Glass.glyph(hover), iconScale);
                if (a.icon == Icons.CHAT) badge(c, a, SocialNotifier.badge());
                if (a.icon == Icons.STAR) badge(c, a, SocialNotifier.ready());
                continue;
            }
            boolean primary = a.kind == Theme.Kind.PRIMARY;
            Glass.bar(c, a.x, a.y, a.w, a.h, hover, false, primary);
            Glass.label(c, a.label, a.x, a.y, a.w, a.h, Glass.text(hover, false, primary));
        }
        if (tipFor != null) tip(c, tipFor);
        c.text(version(), edge, versionY, 0x8CFFFFFF, false);
    }

    private void tip(Canvas c, Action a) {
        int pad = Theme.modern() ? 6 : 4, th = Theme.modern() ? 17 : 13;
        int w = c.textWidth(a.tip) + 2 * pad;
        int x = Math.max(2, Math.min(this.width - 2 - w, a.x + a.w / 2 - w / 2)), y = a.y + a.h + 4;
        if (Theme.modern()) Vanilla.popBox(c, x, y, w, th);
        else c.fill(x, y, x + w, y + 13, 0x80000000);
        c.text(a.tip, x + pad, y + (th - 7) / 2, Theme.TEXT, false);
    }

    private String version() {
        return "Dusk " + modVersion() + "  \u00b7  Minecraft " + mcVersion();
    }

    /** The modern account pill: the face, whole window pixels to the texel, then the name in sans. */
    private void drawAccountModern(Canvas c, Action a, boolean hover) {
        if (this.minecraft == null) return;
        String tex = Compat.localSkin(this.minecraft);
        double g = Px.gui();
        int face = faceWindowPx(a.h), k = face / 8;
        int hw = (int) Math.round(a.h * g);
        int inset = (hw - face) / 2;
        int wy = (int) Math.round(a.y * g) + inset, wx = (int) Math.round(a.x * g) + inset;
        c.push();
        c.scale((float) (1 / g), (float) (1 / g));
        c.translate(wx, wy);
        c.scale(k, k);
        c.blit(tex, 0, 0, 8, 8, 8, 8, 64, 64);
        c.blit(tex, 0, 0, 40, 8, 8, 8, 64, 64);
        c.pop();
        // the name centred in the room between the face and the round end;
        // the box reaches 4 past each side for Px.sans's own inset
        int nx = (int) Math.ceil((wx + face) / g) + Math.max(4, a.h / 4);
        int nr = a.x + a.w - a.h / 2;
        Px.sans(c, username(), nx - 4, a.y, nr - nx + 8, a.h, Px.Size.S16, Glass.glyph(hover), true, false);
    }

    /** Unread messages plus requests, a gold count on the cell's corner like the launcher's sidebar. */
    private static void badge(Canvas c, Action a, int n) {
        if (n <= 0) return;
        String s = n > 99 ? "99+" : String.valueOf(n);
        int w = Math.max(10, c.textWidth(s) + 5), h = 10;
        int x = a.x + a.w - w + 3, y = a.y - 3;
        if (Theme.modern()) {
            Round.pill(c, x - 1, y - 1, w + 2, h + 2, Theme.BG);
            Round.pill(c, x, y, w, h, Theme.ACCENT);
            c.text(s, x + (w - c.textWidth(s)) / 2, y + (h - 7) / 2, Theme.INK, false);
            return;
        }
        c.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        c.fill(x, y, x + w, y + h, Theme.ACCENT);
        c.text(s, x + (w - c.textWidth(s)) / 2 + 1, y + 1, 0xFF1A1A1A, false);
    }

    /** The account pill: the player's face (with the hat layer) and name. */
    private void drawAccount(Canvas c, Action a, int color) {
        if (this.minecraft == null) return;
        String tex = Compat.localSkin(this.minecraft);
        int f = 8 * faceScale;
        int fx = a.x + Math.max(2, (a.h - f) / 2), fy = a.y + (a.h - f) / 2;
        if (!Theme.modern()) c.outline(fx - 1, fy - 1, f + 2, f + 2, 0x33FFFFFF);
        c.push();
        c.translate(fx, fy);
        c.scale(faceScale, faceScale);
        c.blit(tex, 0, 0, 8, 8, 8, 8, 64, 64);
        c.blit(tex, 0, 0, 40, 8, 8, 8, 64, 64);
        c.pop();
        String name = username();
        int nx = a.x + a.w - Math.max(4, Math.round(a.h * 14f / 38)) - c.textWidth(name);
        c.text(name, nx, a.y + (a.h - 7) / 2, color, false);
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
        return key == InputConstants.KEY_ESCAPE; // the title screen has nowhere to go back to
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
