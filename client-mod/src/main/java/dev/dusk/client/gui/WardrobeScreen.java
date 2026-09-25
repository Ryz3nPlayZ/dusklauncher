package dev.dusk.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.dusk.client.account.DuskAccount;
import dev.dusk.client.account.Http;
import dev.dusk.client.account.MojangProfile;
import dev.dusk.client.account.SkinLibrary;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.compat.SkinCompat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CapeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher's Cosmetics page in game: skins from the launcher's library
 * (uploaded to the Mojang account), the account's Mojang capes, and the Dusk
 * capes and accessories it owns. A 3D model of the picked skin on the left.
 * Mojang changes reach other players when they next load the profile, so on
 * a server: leave, change, rejoin.
 */
public class WardrobeScreen extends PanelScreen {
    private static final String[] TABS = {"SKINS", "CAPES", "ACCESSORIES"};
    private static final int CARD_GAP = 6, CARD_MIN_W = 72, SEG_H = 16;

    private static int tab;
    private static boolean mojangCapes;

    /** One grid card; {@code thumb} draws into the box above the label. */
    private record Card(String label, String badge, boolean selected, boolean on, Thumb thumb, Runnable click) {}

    private interface Thumb {
        void draw(Canvas c, int x, int y, int w, int h);
    }

    private final RemoteImages images = new RemoteImages(0);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "duskclient-wardrobe");
        t.setDaemon(true);
        return t;
    });

    // state, touched on the render thread only (the worker posts back through mc.execute)
    private List<SkinLibrary.Entry> skins = List.of();
    @Nullable private MojangProfile.Profile profile;
    @Nullable private String profileError;
    @Nullable private Set<Integer> owned;
    @Nullable private String picked; // library skin in the preview; null: the account's own
    private boolean slim;
    private boolean busy;
    private String status = "";
    private boolean statusError;

    @Nullable private AbstractWidget model;
    private int colX, colW, modelY, modelH, cols, cardW, cardH, gridY;

    public WardrobeScreen(@Nullable Screen parent) {
        super(Component.literal("Wardrobe"), parent);
        slim = Compat.localSkinSlim(Minecraft.getInstance());
        reload();
    }

    // ---- data ---------------------------------------------------------------

    private void reload() {
        worker.execute(() -> {
            List<SkinLibrary.Entry> list = new ArrayList<>(SkinLibrary.list());
            list.sort(Comparator.comparingLong(SkinLibrary.Entry::addedAt).reversed());
            post(() -> skins = list);
            try {
                MojangProfile.Profile p = MojangProfile.fetch();
                post(() -> {
                    profile = p;
                    profileError = null;
                    MojangProfile.Skin s = p.activeSkin();
                    if (picked == null && s != null) slim = s.slim();
                });
            } catch (IOException e) {
                post(() -> profileError = e.getMessage());
            }
            Set<Integer> have;
            try {
                have = new LinkedHashSet<>(DuskAccount.me().owned());
            } catch (IOException e) {
                have = DuskAccount.cachedOwned();
            }
            Set<Integer> ids = have;
            post(() -> {
                ids.add(DuskConfig.get().cosmetics.capeId());
                ids.addAll(DuskConfig.get().cosmetics.accessoryIds());
                ids.remove(-1);
                owned = ids;
            });
        });
    }

    private void post(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private interface Job {
        String run() throws IOException;
    }

    /** Run {@code job} on the worker, showing {@code doing} meanwhile and its answer (or error) after. */
    private void act(String doing, Job job) {
        if (busy) return;
        busy = true;
        status = doing;
        statusError = false;
        worker.execute(() -> {
            String done;
            boolean error = false;
            try {
                done = job.run();
            } catch (IOException | RuntimeException e) {
                done = e.getMessage() == null ? e.toString() : e.getMessage();
                error = true;
            }
            String msg = done;
            boolean err = error;
            post(() -> {
                busy = false;
                status = msg;
                statusError = err;
            });
        });
    }

    private void applySkin() {
        String name = picked;
        boolean variant = slim;
        MojangProfile.Profile p = profile;
        act("Uploading skin...", () -> {
            byte[] png;
            if (name != null) {
                png = SkinLibrary.read(name);
            } else {
                MojangProfile.Skin s = p == null ? null : p.activeSkin();
                if (s == null) throw new IOException("Pick a skin first");
                Http.Response r = Http.get(s.url(), null);
                if (!r.ok()) throw new IOException("Could not download the current skin");
                png = r.body();
            }
            MojangProfile.Profile after = MojangProfile.uploadSkin(png, variant);
            if (name != null) SkinLibrary.select(name);
            MojangProfile.Skin s = after.activeSkin();
            post(() -> {
                profile = after;
                if (s != null) SkinCompat.useUploaded(Minecraft.getInstance(), s.url(), s.slim());
                if (name != null) skins = skins.stream().map(e -> new SkinLibrary.Entry(e.name(), e.addedAt(), e.name().equals(name))).toList();
            });
            return inWorld() ? "Skin updated. Rejoin the server to see it there." : "Skin updated. Servers show it the next time you join.";
        });
    }

    private void addSkin() {
        act("Choose a skin file...", () -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png"));
                filters.flip();
                path = TinyFileDialogs.tinyfd_openFileDialog("Add skin", System.getProperty("user.home") + "/", filters, "Minecraft skin (PNG)", false);
            }
            if (path == null) return "";
            SkinLibrary.Entry e = SkinLibrary.add(Path.of(path));
            post(() -> {
                List<SkinLibrary.Entry> list = new ArrayList<>(skins);
                list.removeIf(s -> s.name().equals(e.name()));
                list.add(0, e);
                skins = list;
                images.invalidate("skin:" + e.name());
                picked = e.name();
                scroll = 0;
            });
            return "Added \"" + e.name() + "\". Press APPLY SKIN to wear it.";
        });
    }

    private void mojangCape(@Nullable String id) {
        act("Changing cape...", () -> {
            MojangProfile.Profile after = id == null ? MojangProfile.hideCape() : MojangProfile.showCape(id);
            post(() -> profile = after);
            return inWorld() ? "Cape updated. Rejoin the server to see it there." : "Cape updated.";
        });
    }

    private void publish(Map<String, JsonElement> loadout) {
        act("Saving...", () -> {
            DuskAccount.publish(loadout);
            return "Saved.";
        });
    }

    private void duskCape(int id) {
        Map<String, JsonElement> next = new LinkedHashMap<>(DuskConfig.get().cosmetics.loadout);
        if (id < 0) next.remove("cape");
        else next.put("cape", new JsonPrimitive(id));
        publish(next);
    }

    private void toggleAccessory(int id) {
        List<Integer> ids = new ArrayList<>(DuskConfig.get().cosmetics.accessoryIds());
        if (!ids.remove((Integer) id)) ids.add(id);
        Map<String, JsonElement> next = new LinkedHashMap<>(DuskConfig.get().cosmetics.loadout);
        if (ids.isEmpty()) {
            next.remove("accessories");
        } else {
            JsonArray a = new JsonArray();
            ids.forEach(a::add);
            next.put("accessories", a);
        }
        publish(next);
    }

    // ---- cards --------------------------------------------------------------

    private List<Card> cards() {
        List<Card> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        switch (tab) {
            case 0 -> {
                out.add(new Card("CURRENT", "", picked == null, false,
                        (c, x, y, w, h) -> skinFront(c, Compat.localSkin(mc), Compat.localSkinSlim(mc), x, y, w, h),
                        () -> {
                            picked = null;
                            MojangProfile.Skin s = profile == null ? null : profile.activeSkin();
                            slim = s != null ? s.slim() : Compat.localSkinSlim(mc);
                        }));
                for (SkinLibrary.Entry e : skins) {
                    String key = "skin:" + e.name();
                    out.add(new Card(e.name(), e.selected() ? "APPLIED" : "", e.name().equals(picked), false, (c, x, y, w, h) -> {
                        RemoteImages.Image img = images.get(key, () -> SkinLibrary.modernize(SkinLibrary.read(e.name())));
                        if (img != null) skinFront(c, img.id(), picked != null && e.name().equals(picked) && slim, x, y, w, h);
                    }, () -> picked = e.name()));
                }
            }
            case 1 -> {
                if (mojangCapes) {
                    MojangProfile.Profile p = profile;
                    if (p == null) break;
                    boolean none = p.activeCape() == null;
                    out.add(new Card("NO CAPE", none ? "ACTIVE" : "", none, false, (c, x, y, w, h) -> {}, () -> mojangCape(null)));
                    for (MojangProfile.Cape cape : p.capes()) {
                        String key = "mcape:" + cape.id();
                        out.add(new Card(cape.alias().replace('_', ' '), cape.active() ? "ACTIVE" : "", cape.active(), false, (c, x, y, w, h) -> {
                            RemoteImages.Image img = images.get(key, () -> Http.get(cape.url(), null).body());
                            if (img != null) capeFront(c, img, x, y, w, h);
                        }, () -> mojangCape(cape.id())));
                    }
                } else {
                    Set<Integer> have = owned;
                    if (have == null) break;
                    int worn = DuskConfig.get().cosmetics.capeId();
                    out.add(new Card("NO CAPE", worn < 0 ? "WORN" : "", worn < 0, false, (c, x, y, w, h) -> {}, () -> duskCape(-1)));
                    for (CapeRegistry.CapeEntry cape : CapeRegistry.capes().values()) {
                        if (!have.contains(cape.id())) continue;
                        String key = "dcape:" + cape.id();
                        out.add(new Card(cape.name(), cape.id() == worn ? "WORN" : "", cape.id() == worn, false, (c, x, y, w, h) -> {
                            RemoteImages.Image img = images.get(key, () -> CapeRegistry.texture(cape.id(), "cape.png"));
                            if (img != null) capeFront(c, img, x, y, w, h);
                        }, () -> duskCape(cape.id())));
                    }
                }
            }
            default -> {
                Set<Integer> have = owned;
                if (have == null) break;
                List<Integer> worn = DuskConfig.get().cosmetics.accessoryIds();
                for (CapeRegistry.AccessoryEntry acc : CapeRegistry.accessories().values()) {
                    if (!have.contains(acc.id())) continue;
                    String key = "acc:" + acc.id();
                    boolean on = worn.contains(acc.id());
                    out.add(new Card(acc.name(), on ? "EQUIPPED" : "", false, on, (c, x, y, w, h) -> {
                        RemoteImages.Image img = images.get(key, () -> CapeRegistry.accessoryFile(acc.id(), "texture.png"));
                        if (img != null) fit(c, img.id(), img.w(), img.h() / Math.max(1, acc.frames()), img.w(), img.h(), x, y, w, h);
                    }, () -> toggleAccessory(acc.id())));
                }
            }
        }
        return out;
    }

    @Nullable
    private String emptyText() {
        if (tab == 0) return null;
        if (tab == 1 && mojangCapes) {
            if (profile == null) return profileError != null ? profileError : "Loading your capes...";
            return null;
        }
        if (owned == null) return "Loading your cosmetics...";
        return tab == 1 ? null : "No accessories yet. Get them in the launcher's store.";
    }

    /** A skin's front view, flat: head, body, arms and legs with their outer layers. */
    private static void skinFront(Canvas c, String tex, boolean slim, int x, int y, int w, int h) {
        float s = Math.min(w / 16f, h / 32f);
        if (s >= 1) s = (float) Math.floor(s);
        int ox = x + Math.round((w - 16 * s) / 2), oy = y + Math.round((h - 32 * s) / 2);
        int aw = slim ? 3 : 4;
        c.push();
        c.translate(ox, oy);
        c.scale(s, s);
        int[][] parts = {
                {4, 0, 8, 8, 8, 8}, {4, 8, 20, 20, 8, 12}, {4 - aw, 8, 44, 20, aw, 12}, {12, 8, 36, 52, aw, 12},
                {4, 20, 4, 20, 4, 12}, {8, 20, 20, 52, 4, 12},
                {4, 0, 40, 8, 8, 8}, {4, 8, 20, 36, 8, 12}, {4 - aw, 8, 44, 36, aw, 12}, {12, 8, 52, 52, aw, 12},
                {4, 20, 4, 36, 4, 12}, {8, 20, 4, 52, 4, 12}};
        for (int[] p : parts) c.blit(tex, p[0], p[1], p[2], p[3], p[4], p[5], 64, 64);
        c.pop();
    }

    /** A cape's outside face (u 1, v 1, 10x16 of the 64-wide layout, scaled with the image). */
    private static void capeFront(Canvas c, RemoteImages.Image img, int x, int y, int w, int h) {
        float k = img.w() / 64f;
        c.push();
        float s = Math.min(w / 10f, h / 16f);
        c.translate(x + (w - 10 * s) / 2, y + (h - 16 * s) / 2);
        c.scale(s / k, s / k);
        c.blit(img.id(), 0, 0, k, k, Math.round(10 * k), Math.round(16 * k), img.w(), img.h());
        c.pop();
    }

    private static void fit(Canvas c, String tex, int rw, int rh, int texW, int texH, int x, int y, int w, int h) {
        float s = Math.min(w / (float) rw, h / (float) rh);
        c.push();
        c.translate(x + (w - rw * s) / 2, y + (h - rh * s) / 2);
        c.scale(s, s);
        c.blit(tex, 0, 0, 0, 0, rw, rh, texW, texH);
        c.pop();
    }

    // ---- layout ---------------------------------------------------------------

    @Override
    protected String[] tabs() {
        return TABS;
    }

    @Override
    protected int activeTab() {
        return tab;
    }

    @Override
    protected void selectTab(int i) {
        tab = i;
    }

    @Override
    protected List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("close", Icons.CLOSE, "", "Close"));
        if (tab == 0) t.add(new Tool("add", null, "ADD SKIN", "Add a skin PNG to your library"));
        return t;
    }

    @Override
    protected void onTool(String id) {
        if (id.equals("add")) addSkin();
        else super.onTool(id);
    }

    @Override
    protected void init() {
        layout();
        Minecraft mc = Minecraft.getInstance();
        model = SkinCompat.widget(mc, colW, modelH, this::preview);
        model.setX(colX);
        model.setY(modelY);
        this.addRenderableWidget(model);
    }

    @Nullable
    private SkinCompat.Preview preview() {
        if (picked == null) return null;
        RemoteImages.Image img = images.get("skin:" + picked, () -> SkinLibrary.modernize(SkinLibrary.read(picked)));
        return img == null ? null : new SkinCompat.Preview(img.id(), slim);
    }

    private void layout() {
        layoutPanel();
        int top = bodyY() + pad, bottom = py + ph - pad;
        colW = Math.max(70, Math.min(150, Math.round(pw * 0.26f)));
        colX = px + pad;
        modelY = top;
        modelH = Math.max(50, Math.round((bottom - top) * 0.62f));
        listX = colX + colW + pad;
        listW = px + pw - pad - BAR_W - 4 - listX;
        gridY = top + (tab == 1 ? SEG_H + 6 : 0);
        listY = gridY;
        listBottom = bottom;
        cols = Math.max(1, (listW + CARD_GAP) / (CARD_MIN_W + CARD_GAP));
        cardW = (listW - CARD_GAP * (cols - 1)) / cols;
        cardH = Math.round(cardW * 1.25f) + 12;
        clampScroll();
    }

    @Override
    protected int contentHeight() {
        int n = cards().size(), rows = (n + cols - 1) / cols;
        return rows == 0 ? 0 : rows * (cardH + CARD_GAP) - CARD_GAP;
    }

    private int cardX(int i) { return listX + (i % cols) * (cardW + CARD_GAP); }

    private int cardY(int i) { return listY + (i / cols) * (cardH + CARD_GAP) - scroll; }

    /** {x, y, w, h} of the left column's controls below the model. */
    private int[] nameBox() { return new int[] {colX, modelY + modelH + 4, colW, 10}; }

    private int[] variantBox(boolean slimSide) {
        int y = modelY + modelH + 18, w = (colW - 3) / 2;
        return new int[] {slimSide ? colX + colW - w : colX, y, w, 16};
    }

    private int[] applyBox() { return new int[] {colX, modelY + modelH + 38, colW, 18}; }

    private static boolean in(int[] b, double mx, double my) {
        return Vanilla.inside(mx, my, b[0], b[1], b[2], b[3]);
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        drawPanel(c, mouseX, mouseY);
        // the model is a vanilla widget and draws before this overlay: keep the panel off it
        drawColumn(c, mouseX, mouseY);

        if (tab == 1) {
            int w = (listW - 3) / 2;
            flatButton(c, "DUSK", listX, bodyY() + pad, w, SEG_H, mouseX, mouseY, !mojangCapes, true);
            flatButton(c, "MINECRAFT", listX + listW - w, bodyY() + pad, w, SEG_H, mouseX, mouseY, mojangCapes, true);
        }

        List<Card> cards = cards();
        boolean hotList = inList(mouseX, mouseY) && !busy;
        c.scissor(listX, listY, listX + listW, listBottom);
        for (int i = 0; i < cards.size(); i++) {
            int x = cardX(i), y = cardY(i);
            if (y + cardH < listY || y > listBottom) continue;
            drawCard(c, cards.get(i), x, y, hotList && Vanilla.inside(mouseX, mouseY, x, y, cardW, cardH));
        }
        c.unscissor();
        String empty = emptyText();
        if (empty != null && cards.isEmpty()) {
            int ty = listY + 4;
            for (String line : wrap(c, empty, listW)) {
                c.text(line, listX, ty, Theme.TEXT_MUTED, false);
                ty += 10;
            }
        }
        drawScrollbar(c);
    }

    private void drawCard(Canvas c, Card card, int x, int y, boolean hot) {
        Theme.plate(c, x, y, cardW, cardH, Theme.SURFACE, card.on ? Theme.MOSS_BOT : Theme.SURFACE, hot);
        if (card.selected) c.outline(x, y, cardW, cardH, Theme.ACCENT);
        int m = 5;
        card.thumb.draw(c, x + m, y + m + (card.badge.isEmpty() ? 0 : 6), cardW - 2 * m, cardH - 2 * m - 12 - (card.badge.isEmpty() ? 0 : 6));
        if (!card.badge.isEmpty()) c.text(card.badge, x + cardW - m - c.textWidth(card.badge), y + m - 1, Theme.ACCENT, false);
        String label = Theme.ellipsize(c, card.label.toUpperCase(Locale.ROOT), cardW - 2 * m);
        c.text(label, x + (cardW - c.textWidth(label)) / 2, y + cardH - m - 8, 0xFFFFFFFF, false);
    }

    private void drawColumn(Canvas c, int mouseX, int mouseY) {
        c.outline(colX - 1, modelY - 1, colW + 2, modelH + 2, 0x33FFFFFF);
        int[] nb = nameBox();
        String name = picked != null ? picked : Minecraft.getInstance().getUser().getName();
        name = Theme.ellipsize(c, name, colW);
        c.text(name, nb[0] + (colW - c.textWidth(name)) / 2, nb[1], 0xFFFFFFFF, false);
        int ty = modelY + modelH + 18;
        if (tab == 0) {
            int[] cb = variantBox(false), sb = variantBox(true), ab = applyBox();
            flatButton(c, "CLASSIC", cb[0], cb[1], cb[2], cb[3], mouseX, mouseY, !slim, !busy);
            flatButton(c, "SLIM", sb[0], sb[1], sb[2], sb[3], mouseX, mouseY, slim, !busy);
            flatButton(c, busy ? "..." : "APPLY SKIN", ab[0], ab[1], ab[2], ab[3], mouseX, mouseY, false, !busy && profile != null);
            ty = ab[1] + ab[3] + 5;
        }
        String msg = !status.isEmpty() ? status : tab == 0 && profile == null && profileError != null ? profileError : "";
        boolean err = !status.isEmpty() ? statusError : true;
        for (String line : wrap(c, msg, colW)) {
            if (ty + 8 > py + ph - 3) break;
            c.text(line, colX, ty, err ? Theme.RED_UP : Theme.TEXT_MUTED, false);
            ty += 10;
        }
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (clickChrome(mx, my, button)) return true;
        if (Vanilla.inside(mx, my, colX, modelY, colW, modelH)) return false; // spin the model
        if (button != 0 || busy) return Vanilla.inside(mx, my, px, py, pw, ph);
        if (tab == 0) {
            if (in(variantBox(false), mx, my)) { slim = false; return true; }
            if (in(variantBox(true), mx, my)) { slim = true; return true; }
            if (in(applyBox(), mx, my)) {
                if (profile != null) applySkin();
                return true;
            }
        }
        if (tab == 1 && my >= bodyY() + pad && my < bodyY() + pad + SEG_H && mx >= listX && mx < listX + listW) {
            mojangCapes = mx >= listX + listW / 2;
            scroll = 0;
            return true;
        }
        if (inList(mx, my)) {
            List<Card> cards = cards();
            for (int i = 0; i < cards.size(); i++) {
                if (Vanilla.inside(mx, my, cardX(i), cardY(i), cardW, cardH)) {
                    cards.get(i).click.run();
                    return true;
                }
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    public void removed() {
        super.removed();
        images.releaseAll();
        worker.shutdown();
    }
}
