package dev.dusk.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.dusk.client.account.DuskAccount;
import dev.dusk.client.account.Http;
import dev.dusk.client.account.MojangProfile;
import dev.dusk.client.account.SkinLibrary;
import dev.dusk.client.compat.SkinCompat;
import dev.dusk.client.config.DuskConfig;
import dev.dusk.client.cosmetics.CapeRegistry;
import dev.dusk.client.gui.widget.TextFieldWidget;
import dev.dusk.client.social.Social;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher's Cosmetics page in game (launcher/src/views/Cosmetics.tsx),
 * drawn and behaving the same: the window bar with SKINS / CAPES /
 * ACCESSORIES / OUTFITS (and the launcher's store as STORE, which the GET
 * MORE tiles open) and the current skin's name; the viewer (a 3D model, CANCEL and
 * APPLY) on the left; the gallery, a window of its own, on the right. A skin
 * is picked, previewed, then applied with the arm model read off its PNG.
 * Dusk capes and accessories are a draft until APPLY LOOK; Minecraft capes
 * apply on click. Mojang changes reach other players when they next load the
 * profile, so on a server: leave, change, rejoin.
 */
public class WardrobeScreen extends PanelScreen {
    private static final String[] TABS = {"SKINS", "CAPES", "ACCESSORIES", "OUTFITS", "STORE"};
    private static final int SKINS = 0, CAPES = 1, ACCESSORIES = 2, OUTFITS = 3, STORE = 4;
    private static final String[] FILTERS = {"ALL", "CAPES", "ACCESSORIES"};
    private static final int GAP = 4, TILE_MIN_W = 64, ACTIONS_H = 20, NAME_H = 10, ROW_H = 28, BTN_H = 18, OUTFIT_NAME_MAX = 32;
    private static final long CONFIRM_MS = 4000;

    private static int tab;
    private static int storeFilter;
    private static boolean mojangCapes;

    /**
     * One gallery tile: {@code picked} wears the accent, {@code worn} the
     * green; {@code badge} a corner tag (a price), {@code tip} the hover text.
     */
    private record Tile(String label, boolean picked, boolean worn, Theme.Family family, Thumb thumb, Runnable click,
                        @Nullable String badge, String tip) {
        Tile(String label, boolean picked, boolean worn, Theme.Family family, Thumb thumb, Runnable click) {
            this(label, picked, worn, family, thumb, click, null, "");
        }
    }

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
    /** Arm model per library skin, read off its PNG (see {@link SkinLibrary#slim}). */
    private final Map<String, Boolean> slimOf = new ConcurrentHashMap<>();
    /** The look being put together; APPLY LOOK publishes it. -1: no cape. */
    private int pickedCape;
    private List<Integer> pickedAcc;
    private boolean busy;
    private String status = "";
    private boolean statusError;

    @Nullable private List<Social.Outfit> outfits;
    @Nullable private String outfitsError;
    @Nullable private Social.Store store;
    @Nullable private String storeError;
    /** The store tile waiting for its second click to buy, and since when. */
    private int buyArmed = -1;
    private long buyArmedAt;
    private String outfitName = "";
    private final TextFieldWidget outfitField = new TextFieldWidget(() -> outfitName, s -> outfitName = s, true, OUTFIT_NAME_MAX)
            .themed().placeholder("NAME THIS LOOK");
    private int saveX, saveY, saveW;
    private List<String> lookLines = List.of();

    @Nullable private AbstractWidget model;
    private final NavBar gallery = new NavBar();
    /** Viewer box, the model's stage inside it, the gallery window. */
    private int vx, vy, vw, vh, stageX, stageY, stageW, stageH, gx, gy, gw, gh;
    private int cols, tileW, tileH;

    public WardrobeScreen(@Nullable Screen parent) {
        super(Component.literal("Wardrobe"), parent);
        pickedCape = DuskConfig.get().cosmetics.capeId();
        pickedAcc = new ArrayList<>(DuskConfig.get().cosmetics.accessoryIds());
        reload();
    }

    // ---- data ---------------------------------------------------------------

    private void reload() {
        worker.execute(() -> {
            List<SkinLibrary.Entry> list = new ArrayList<>(SkinLibrary.list());
            list.sort(Comparator.comparingLong(SkinLibrary.Entry::addedAt).reversed());
            post(() -> {
                skins = list;
                // start on the skin that is on, like the launcher
                if (picked == null) list.stream().filter(SkinLibrary.Entry::selected).findFirst().ifPresent(e -> picked = e.name());
            });
            try {
                MojangProfile.Profile p = MojangProfile.fetch();
                post(() -> {
                    profile = p;
                    profileError = null;
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
            try {
                List<Social.Outfit> saved = Social.outfits();
                post(() -> {
                    outfits = saved;
                    outfitsError = null;
                });
            } catch (IOException | RuntimeException e) {
                post(() -> {
                    outfits = List.of();
                    outfitsError = message(e);
                });
            }
            try {
                Social.Store st = Social.store();
                post(() -> {
                    store = st;
                    storeError = null;
                });
            } catch (IOException | RuntimeException e) {
                post(() -> storeError = message(e));
            }
        });
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
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
        if (name == null) return;
        act("Uploading skin...", () -> {
            byte[] png = SkinLibrary.read(name);
            boolean variant = SkinLibrary.slim(png);
            SkinLibrary.select(name);
            MojangProfile.Profile after = MojangProfile.uploadSkin(png, variant);
            MojangProfile.Skin s = after.activeSkin();
            post(() -> {
                profile = after;
                if (s != null) SkinCompat.useUploaded(Minecraft.getInstance(), s.url(), s.slim());
                skins = skins.stream().map(e -> new SkinLibrary.Entry(e.name(), e.addedAt(), e.name().equals(name))).toList();
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
            return "Added \"" + e.name() + "\". Press APPLY to wear it.";
        });
    }

    private void mojangCape(@Nullable String id) {
        act("Changing cape...", () -> {
            MojangProfile.Profile after = id == null ? MojangProfile.hideCape() : MojangProfile.showCape(id);
            post(() -> profile = after);
            return inWorld() ? "Cape updated. Rejoin the server to see it there." : "Cape updated.";
        });
    }

    private boolean lookDirty() {
        return pickedCape != DuskConfig.get().cosmetics.capeId()
                || !new LinkedHashSet<>(pickedAcc).equals(new LinkedHashSet<>(DuskConfig.get().cosmetics.accessoryIds()));
    }

    private void cancelLook() {
        pickedCape = DuskConfig.get().cosmetics.capeId();
        pickedAcc = new ArrayList<>(DuskConfig.get().cosmetics.accessoryIds());
    }

    private void applyLook() {
        Map<String, JsonElement> next = new LinkedHashMap<>(DuskConfig.get().cosmetics.loadout);
        if (pickedCape < 0) next.remove("cape");
        else next.put("cape", new JsonPrimitive(pickedCape));
        if (pickedAcc.isEmpty()) {
            next.remove("accessories");
        } else {
            JsonArray a = new JsonArray();
            pickedAcc.forEach(a::add);
            next.put("accessories", a);
        }
        act("Saving...", () -> {
            DuskAccount.publish(next);
            post(this::cancelLook); // the draft is now what is worn
            return "Saved.";
        });
    }

    private void toggleAccessory(int id) {
        if (!pickedAcc.remove((Integer) id)) pickedAcc.add(id);
    }

    // ---- outfits and the store ----------------------------------------------

    /** The look on the viewer, as a loadout: what SAVE OUTFIT keeps. */
    private Map<String, JsonElement> pickedLook() {
        Map<String, JsonElement> l = new LinkedHashMap<>();
        if (pickedCape >= 0) l.put("cape", new JsonPrimitive(pickedCape));
        JsonArray a = new JsonArray();
        pickedAcc.forEach(a::add);
        l.put("accessories", a);
        return l;
    }

    /** "Cape · Acc · Acc", the launcher's outfit summary. */
    private static String summary(Map<String, JsonElement> loadout) {
        List<String> parts = new ArrayList<>();
        int cape = capeOf(loadout);
        if (cape >= 0) {
            CapeRegistry.CapeEntry e = CapeRegistry.cape(cape);
            parts.add(e != null ? e.name() : "Cape #" + cape);
        }
        for (int id : accessoriesOf(loadout)) {
            CapeRegistry.AccessoryEntry e = CapeRegistry.accessory(id);
            parts.add(e != null ? e.name() : "#" + id);
        }
        return parts.isEmpty() ? "Nothing equipped" : String.join(" \u00b7 ", parts);
    }

    private static int capeOf(Map<String, JsonElement> loadout) {
        JsonElement e = loadout.get("cape");
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : -1;
    }

    private static List<Integer> accessoriesOf(Map<String, JsonElement> loadout) {
        List<Integer> out = new ArrayList<>();
        JsonElement e = loadout.get("accessories");
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber()) out.add(x.getAsInt());
        }
        return out;
    }

    private void saveOutfit() {
        String name = outfitName.trim();
        if (name.isEmpty() || busy) return;
        Map<String, JsonElement> look = pickedLook();
        act("Saving outfit...", () -> {
            List<Social.Outfit> list = Social.saveOutfit(name, look);
            post(() -> {
                outfits = list;
                outfitsError = null;
                outfitField.setText("");
            });
            return "Saved \"" + name + "\".";
        });
    }

    /** Puts an outfit on the viewer (only what's still owned); APPLY LOOK wears it. */
    private void wearOutfit(Social.Outfit o) {
        Set<Integer> have = owned;
        int cape = capeOf(o.loadout());
        pickedCape = cape >= 0 && (have == null || have.contains(cape)) ? cape : -1;
        pickedAcc = new ArrayList<>(accessoriesOf(o.loadout()).stream().filter(id -> have == null || have.contains(id)).toList());
        status = lookDirty() ? "\"" + o.name() + "\" is on the viewer. APPLY LOOK to wear it." : "You're wearing \"" + o.name() + "\".";
        statusError = false;
    }

    private void deleteOutfit(Social.Outfit o) {
        act("Deleting...", () -> {
            List<Social.Outfit> list = Social.deleteOutfit(o.id());
            post(() -> outfits = list);
            return "Deleted \"" + o.name() + "\".";
        });
    }

    private boolean buyArmed(int id) {
        return buyArmed == id && System.currentTimeMillis() - buyArmedAt < CONFIRM_MS;
    }

    /** A store tile: owned ones go on the viewer, the rest take a second click to buy. */
    private void storeClick(Social.StoreItem item) {
        boolean cape = item.kind().equals("cape");
        Set<Integer> have = owned;
        Social.Store st = store;
        if (have != null && have.contains(item.id())) {
            if (cape) pickedCape = item.id();
            else if (!pickedAcc.contains(item.id())) pickedAcc.add(item.id());
            selectTab(cape ? CAPES : ACCESSORIES);
            status = lookDirty() ? item.name() + " is on the viewer. APPLY LOOK to wear it." : "";
            statusError = false;
            return;
        }
        if (st == null) return;
        if (item.price() > st.coins()) {
            status = "You need " + (item.price() - st.coins()) + " more coins for " + item.name() + ".";
            statusError = true;
            buyArmed = -1;
            return;
        }
        if (!buyArmed(item.id())) {
            buyArmed = item.id();
            buyArmedAt = System.currentTimeMillis();
            status = "Buy " + item.name() + " for " + item.price() + " coins? Click it again.";
            statusError = false;
            return;
        }
        buyArmed = -1;
        act("Buying...", () -> {
            long coins = Social.buy(item.id());
            post(() -> {
                if (owned != null) owned.add(item.id());
                if (store != null) store = new Social.Store(store.items(), coins);
            });
            return "Bought " + item.name() + ". Click it to try it on.";
        });
    }

    // ---- tiles --------------------------------------------------------------

    private static Theme.Family family(boolean picked, boolean worn) {
        return picked ? Theme.Family.ACCENT : worn ? Theme.Family.GREEN : Theme.Family.PANEL;
    }

    @Nullable
    private SkinLibrary.Entry current() {
        for (SkinLibrary.Entry e : skins) if (e.selected()) return e;
        return null;
    }

    /** The library skin's texture, noting its arm model on the way in. */
    @Nullable
    private RemoteImages.Image skinImage(String name) {
        return images.get("skin:" + name, () -> {
            byte[] raw = SkinLibrary.read(name);
            slimOf.put(name, SkinLibrary.slim(raw));
            return SkinLibrary.modernize(raw);
        });
    }

    private List<Tile> tiles() {
        List<Tile> out = new ArrayList<>();
        switch (tab) {
            case 0 -> {
                out.add(new Tile("ADD SKIN", false, false, Theme.Family.GREY, WardrobeScreen::plus, this::addSkin));
                for (SkinLibrary.Entry e : skins) {
                    boolean on = e.name().equals(picked);
                    out.add(new Tile(e.name(), on, e.selected(), family(on, e.selected()), (c, x, y, w, h) -> {
                        RemoteImages.Image img = skinImage(e.name());
                        if (img != null) skinFront(c, img.id(), slimOf.getOrDefault(e.name(), false), x, y, w, h);
                    }, () -> picked = e.name()));
                }
            }
            case 1 -> {
                if (mojangCapes) {
                    MojangProfile.Profile p = profile;
                    if (p == null) break;
                    // applied on click: Mojang has no draft
                    boolean none = p.activeCape() == null;
                    out.add(new Tile("NONE", false, none, family(false, none), WardrobeScreen::none, () -> mojangCape(null)));
                    for (MojangProfile.Cape cape : p.capes()) {
                        String key = "mcape:" + cape.id();
                        out.add(new Tile(cape.alias().replace('_', ' '), false, cape.active(), family(false, cape.active()), (c, x, y, w, h) -> {
                            RemoteImages.Image img = images.get(key, () -> Http.get(cape.url(), null).body());
                            if (img != null) capeFront(c, img, x, y, w, h);
                        }, () -> mojangCape(cape.id())));
                    }
                } else {
                    Set<Integer> have = owned;
                    if (have == null) break;
                    int worn = DuskConfig.get().cosmetics.capeId();
                    out.add(new Tile("NONE", pickedCape < 0, worn < 0, family(pickedCape < 0, worn < 0), WardrobeScreen::none, () -> pickedCape = -1));
                    for (CapeRegistry.CapeEntry cape : CapeRegistry.capes().values()) {
                        if (!have.contains(cape.id())) continue;
                        boolean on = cape.id() == pickedCape, isWorn = cape.id() == worn;
                        out.add(new Tile(cape.name(), on, isWorn, family(on, isWorn), capeThumb(cape.id()),
                                () -> pickedCape = cape.id(), null, capeTraits(cape)));
                    }
                    out.add(getMore(1));
                }
            }
            case OUTFITS -> {}
            case STORE -> storeTiles(out);
            default -> {
                Set<Integer> have = owned;
                if (have == null) break;
                List<Integer> worn = DuskConfig.get().cosmetics.accessoryIds();
                for (CapeRegistry.AccessoryEntry acc : CapeRegistry.accessories().values()) {
                    if (!have.contains(acc.id())) continue;
                    boolean on = pickedAcc.contains(acc.id()), isWorn = worn.contains(acc.id());
                    out.add(new Tile(acc.name(), on, isWorn, family(on, isWorn), accThumb(acc.id()),
                            () -> toggleAccessory(acc.id()), null, accTraits(acc)));
                }
                out.add(getMore(2));
            }
        }
        return out;
    }

    private void storeTiles(List<Tile> out) {
        Social.Store st = store;
        Set<Integer> have = owned;
        if (st == null || have == null) return;
        int wornCape = DuskConfig.get().cosmetics.capeId();
        List<Integer> wornAcc = DuskConfig.get().cosmetics.accessoryIds();
        for (Social.StoreItem item : st.items()) {
            boolean cape = item.kind().equals("cape");
            if (storeFilter == 1 && !cape || storeFilter == 2 && cape) continue;
            boolean own = have.contains(item.id()), worn = cape ? wornCape == item.id() : wornAcc.contains(item.id());
            boolean armed = buyArmed(item.id());
            String badge = worn ? "WORN" : own ? "OWNED" : armed ? "BUY?" : item.price() + " COINS";
            Theme.Family f = armed ? Theme.Family.ACCENT : worn ? Theme.Family.GREEN : Theme.Family.PANEL;
            String tip;
            if (cape) {
                CapeRegistry.CapeEntry e = CapeRegistry.cape(item.id());
                tip = "cape" + (e == null || capeTraits(e).isEmpty() ? "" : " \u00b7 " + capeTraits(e));
            } else {
                CapeRegistry.AccessoryEntry e = CapeRegistry.accessory(item.id());
                tip = e == null ? "accessory" : "accessory \u00b7 " + accTraits(e);
            }
            out.add(new Tile(item.name(), armed, worn, f, cape ? capeThumb(item.id()) : accThumb(item.id()),
                    () -> storeClick(item), badge, tip));
        }
    }

    /** The last tile of the Dusk cape and accessory grids: the way to the store. */
    private Tile getMore(int filter) {
        return new Tile("GET MORE", false, false, Theme.Family.GREY, WardrobeScreen::plus, () -> {
            storeFilter = filter;
            selectTab(STORE);
        }, null, "Open the store");
    }

    private Thumb capeThumb(int id) {
        return (c, x, y, w, h) -> {
            if (CapeRegistry.cape(id) == null) return;
            RemoteImages.Image img = images.get("dcape:" + id, () -> CapeRegistry.texture(id, "cape.png"));
            if (img != null) capeFront(c, img, x, y, w, h);
        };
    }

    private Thumb accThumb(int id) {
        return (c, x, y, w, h) -> {
            CapeRegistry.AccessoryEntry acc = CapeRegistry.accessory(id);
            if (acc == null) return;
            RemoteImages.Image img = images.get("acc:" + id, () -> CapeRegistry.accessoryFile(id, "texture.png"));
            if (img != null) fit(c, img.id(), img.w(), img.h() / Math.max(1, acc.frames()), img.w(), img.h(), x, y, w, h);
        };
    }

    private static String capeTraits(CapeRegistry.CapeEntry cape) {
        List<String> t = new ArrayList<>();
        if (cape.glint()) t.add("glint");
        if (cape.ears()) t.add("ears");
        if (cape.upsideDown()) t.add("upside down");
        return String.join(" \u00b7 ", t);
    }

    private static String accTraits(CapeRegistry.AccessoryEntry acc) {
        String where = acc.attachment().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return acc.frames() > 1 ? where + " \u00b7 animated" : where;
    }

    /** What the gallery says under the tiles: progress, errors, the launcher's hints. */
    private String note() {
        if (!status.isEmpty()) return status;
        if (tab == 0) return profile == null && profileError != null ? profileError : "";
        if (tab == 1 && mojangCapes) {
            if (profile == null) return profileError != null ? profileError : "Loading your Minecraft capes...";
            if (profile.capes().isEmpty()) return "This account has no Minecraft capes.";
            return "Changes apply right away. Others see them after they rejoin.";
        }
        if (tab == OUTFITS) return outfitsError != null ? outfitsError : "";
        if (tab == STORE) {
            if (store == null) return storeError != null ? storeError : "Loading the store...";
            return "Bought cosmetics are yours on every Dusk instance, in game and in the launcher.";
        }
        if (owned == null) return "Loading your cosmetics...";
        if (tab == ACCESSORIES && tiles().size() <= 1) return "No accessories yet. GET MORE opens the store.";
        return "";
    }

    private boolean noteIsError() {
        if (!status.isEmpty()) return statusError;
        if (tab == OUTFITS) return outfitsError != null;
        if (tab == STORE) return store == null && storeError != null;
        return profile == null && profileError != null;
    }

    private String countLabel() {
        if (tab == OUTFITS) return "SAVED LOOKS";
        if (tab == STORE) return store == null ? "STORE" : store.coins() + " COINS";
        int n = 0;
        for (Tile t : tiles()) if (!t.label.equals("ADD SKIN") && !t.label.equals("NONE") && !t.label.equals("GET MORE")) n++;
        String noun = switch (tab) {
            case 0 -> n == 1 ? "SKIN" : "SKINS";
            case 1 -> (mojangCapes ? "MINECRAFT " : "") + (n == 1 ? "CAPE" : "CAPES");
            default -> n == 1 ? "ACCESSORY" : "ACCESSORIES";
        };
        return n + " " + noun;
    }

    /** The ADD SKIN tile's plus. */
    private static void plus(Canvas c, int x, int y, int w, int h) {
        int s = Math.max(8, Math.round(Math.min(w, h) * 0.3f)) / 2 * 2, t = 2;
        int cx = x + w / 2, cy = y + h / 2;
        c.fill(cx - s / 2, cy - t / 2, cx + s / 2, cy + t / 2, Theme.GLYPH);
        c.fill(cx - t / 2, cy - s / 2, cx + t / 2, cy + s / 2, Theme.GLYPH);
    }

    /** The NONE tile: a dashed cape outline at half strength. */
    private static void none(Canvas c, int x, int y, int w, int h) {
        int rh = Math.max(12, Math.round(h * 0.7f)), rw = Math.max(8, rh * 56 / 90);
        int rx = x + (w - rw) / 2, ry = y + (h - rh) / 2, col = 0x80B8B8B8;
        for (int i = 0; i < rw; i += 4) {
            c.fill(rx + i, ry, Math.min(rx + i + 2, rx + rw), ry + 1, col);
            c.fill(rx + i, ry + rh - 1, Math.min(rx + i + 2, rx + rw), ry + rh, col);
        }
        for (int i = 0; i < rh; i += 4) {
            c.fill(rx, ry + i, rx + 1, Math.min(ry + i + 2, ry + rh), col);
            c.fill(rx + rw - 1, ry + i, rx + rw, Math.min(ry + i + 2, ry + rh), col);
        }
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
        scroll = 0;
        buyArmed = -1;
        status = "";
        if (i != OUTFITS) outfitField.setFocused(false);
    }

    @Override
    protected List<Tool> tools() {
        SkinLibrary.Entry cur = current();
        String name = cur != null ? cur.name().toUpperCase(Locale.ROOT) : "NO SKIN SELECTED";
        return List.of(new Tool("close", Icons.CLOSE, "", "Close"),
                new Tool("label", null, name.length() > 22 ? name.substring(0, 21) + "..." : name, ""));
    }

    @Override
    protected void init() {
        layout();
        model = SkinCompat.widget(Minecraft.getInstance(), stageW, stageH, this::preview);
        model.setX(stageX);
        model.setY(stageY);
        this.addRenderableWidget(model);
    }

    /** The picked skin, with its own arm model; null shows the account's skin. */
    @Nullable
    private SkinCompat.Preview preview() {
        if (tab != 0 || picked == null) {
            SkinLibrary.Entry cur = current();
            if (cur == null) return null;
            RemoteImages.Image img = skinImage(cur.name());
            return img == null ? null : new SkinCompat.Preview(img.id(), slimOf.getOrDefault(cur.name(), false));
        }
        RemoteImages.Image img = skinImage(picked);
        return img == null ? null : new SkinCompat.Preview(img.id(), slimOf.getOrDefault(picked, false));
    }

    @Override
    protected void relayout() {
        layout();
    }

    private void layout() {
        layoutPanel();
        int top = bodyY() + GAP, bottom = py + ph - 1 - GAP, left = px + 1 + GAP, right = px + pw - 1 - GAP;
        // the launcher's 580 : 30 : 2000 split, with a floor for the buttons
        vw = Math.max(96, Math.round((right - left) * 0.222f));
        vx = left;
        vy = top;
        vh = bottom - top;
        int in = 2 + GAP;
        stageX = vx + in;
        stageY = vy + in;
        stageW = vw - 2 * in;
        stageH = vh - 2 * in - ACTIONS_H - GAP;

        gx = vx + vw + GAP;
        gy = top;
        gw = right - gx;
        gh = bottom - top;
        List<NavBar.Tool> t = new ArrayList<>();
        t.add(new NavBar.Tool("label", null, countLabel(), ""));
        String[] cells = tab == CAPES ? new String[] {mojangCapes ? "MINECRAFT" : "DUSK"}
                : tab == STORE ? new String[] {FILTERS[storeFilter]} : new String[0];
        gallery.layout(gx, gy, gw, cells, t, null, 0, this.font::width);

        String note = note();
        List<String> noteLines = note.isEmpty() ? List.of() : wrapLines(note, gw - 2 - 2 * GAP);
        listX = gx + 1 + GAP;
        listY = gy + NavBar.H + GAP;
        if (tab == OUTFITS) {
            // NAME THIS LOOK + SAVE OUTFIT, the summary of what it saves, then the outfits
            int inner = gx + gw - 1 - GAP - listX;
            saveW = this.font.width("SAVE OUTFIT") + 20;
            saveY = listY;
            saveX = listX + inner - saveW;
            outfitField.setBounds(listX, saveY, saveX - GAP - listX, BTN_H);
            lookLines = wrapLines("Saves the look on the viewer: " + summary(pickedLook()) + ".", inner);
            listY = saveY + BTN_H + 4 + lookLines.size() * 10 + 4;
        } else {
            outfitField.setBounds(0, 0, 0, 0);
        }
        listW = gx + gw - 1 - GAP - BAR_W - 3 - listX;
        listBottom = gy + gh - 1 - GAP - (noteLines.isEmpty() ? 0 : noteLines.size() * 10 + 2);
        cols = Math.max(1, (listW + GAP) / (TILE_MIN_W + GAP));
        tileW = (listW - GAP * (cols - 1)) / cols;
        tileH = tileW * 50 / 45;
        clampScroll();
    }

    private List<String> wrapLines(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (this.font.width(next) <= max || line.isEmpty()) {
                line.setLength(0);
                line.append(next);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    @Override
    protected int contentHeight() {
        if (tab == OUTFITS) return outfits == null || outfits.isEmpty() ? 40 : outfits.size() * ROW_H;
        int n = tiles().size(), rows = (n + cols - 1) / cols;
        return rows == 0 ? 0 : rows * (tileH + GAP) - GAP;
    }

    private int tileX(int i) { return listX + (i % cols) * (tileW + GAP); }

    private int tileY(int i) { return listY + (i / cols) * (tileH + GAP) - scroll; }

    /** {x, y, w, h} of CANCEL (left) or APPLY (right) under the model. */
    private int[] actionBox(boolean apply) {
        int y = vy + vh - 2 - GAP - ACTIONS_H, w = (vw - 4 - 3 * GAP) / 2;
        return new int[] {apply ? vx + vw - 2 - GAP - w : vx + 2 + GAP, y, w, ACTIONS_H};
    }

    private boolean canCancel() {
        if (busy) return false;
        if (tab == 0) {
            SkinLibrary.Entry cur = current();
            return picked != null && !(cur != null && picked.equals(cur.name()));
        }
        return lookDirty();
    }

    private boolean applied() {
        SkinLibrary.Entry cur = current();
        return picked != null && cur != null && picked.equals(cur.name());
    }

    private boolean canApply() {
        if (busy) return false;
        return tab == 0 ? picked != null && !applied() && profile != null : lookDirty();
    }

    private static boolean in(int[] b, double mx, double my) {
        return Vanilla.inside(mx, my, b[0], b[1], b[2], b[3]);
    }

    // ---- drawing ------------------------------------------------------------

    /** The viewer box and the gallery window go under the model widget, so it draws on top of them. */
    @Override
    protected void drawUnderWidgets(Canvas c) {
        Theme.box(c, vx, vy, vw, vh, Theme.Family.PANEL, false, false);
        NavBar.window(c, gx, gy, gw, gh);
    }

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        layout();
        drawPanel(c, mouseX, mouseY);
        gallery.draw(c, -1, mouseX, mouseY, this.width, this.height);

        int[] cb = actionBox(false), ab = actionBox(true);
        boxButton(c, "CANCEL", cb[0], cb[1], cb[2], cb[3], mouseX, mouseY, Theme.Family.GREY, false, canCancel());
        String apply = busy ? "..." : tab == 0 ? (applied() ? "APPLIED" : "APPLY") : "APPLY LOOK";
        boxButton(c, apply, ab[0], ab[1], ab[2], ab[3], mouseX, mouseY, Theme.Family.INSTALL, false, canApply());

        Tile tipFor = null;
        if (tab == OUTFITS) {
            drawOutfits(c, mouseX, mouseY);
        } else {
            List<Tile> tiles = tiles();
            boolean hotList = inList(mouseX, mouseY) && !busy;
            c.scissor(listX, listY, listX + listW, listBottom);
            for (int i = 0; i < tiles.size(); i++) {
                int x = tileX(i), y = tileY(i);
                if (y + tileH < listY || y > listBottom) continue;
                boolean hot = hotList && Vanilla.inside(mouseX, mouseY, x, y, tileW, tileH);
                drawTile(c, tiles.get(i), x, y, hot);
                if (hot && !tiles.get(i).tip.isEmpty()) tipFor = tiles.get(i);
            }
            c.unscissor();
        }
        drawScrollbar(c);

        String note = note();
        if (!note.isEmpty()) {
            int ty = listBottom + 3;
            for (String line : wrap(c, note, gw - 2 - 2 * GAP)) {
                c.text(line, listX, ty, noteIsError() ? Theme.RED_UP : Theme.TEXT_MUTED, false);
                ty += 10;
            }
        }
        if (tipFor != null) {
            // the launcher's title attribute: traits under the pointer
            int w = c.textWidth(tipFor.tip) + 8;
            int x = Math.max(2, Math.min(this.width - 2 - w, mouseX + 8)), y = Math.min(this.height - 15, mouseY + 12);
            c.beginLayer();
            c.fill(x, y, x + w, y + 13, 0xE0000000);
            c.text(tipFor.tip, x + 4, y + 3, Theme.TEXT, false);
            c.endLayer();
        }
    }

    private void drawOutfits(Canvas c, int mx, int my) {
        outfitField.render(c, mx, my);
        boxButton(c, busy ? "SAVING..." : "SAVE OUTFIT", saveX, saveY, saveW, BTN_H, mx, my, Theme.Family.ACCENT, false,
                !busy && !outfitName.isBlank());
        int ty = saveY + BTN_H + 4;
        for (String line : lookLines) {
            c.text(line, listX, ty, Theme.TEXT_MUTED, false);
            ty += 10;
        }
        List<Social.Outfit> list = outfits;
        c.scissor(listX, listY, listX + listW, listBottom);
        if (list == null) {
            c.text("Loading your outfits...", listX, listY + 4, Theme.TEXT_MUTED, false);
        } else if (list.isEmpty() && outfitsError == null) {
            Theme.box(c, listX, listY, listW, 36, Theme.Family.PANEL, false, false);
            Theme.label(c, "NO OUTFITS YET", listX + 8, listY + 7, Theme.LABEL_UP, Theme.LABEL_LO, 1f);
            c.text(Theme.ellipsize(c, "Pick a cape and accessories, name the look and save it to switch back any time.", listW - 16),
                    listX + 8, listY + 21, Theme.TEXT_FAINT, false);
        } else {
            boolean hotList = inList(mx, my) && !busy;
            for (int i = 0; i < list.size(); i++) {
                int y = listY + i * ROW_H - scroll;
                if (y + ROW_H < listY || y > listBottom) continue;
                Social.Outfit o = list.get(i);
                int[] wear = outfitButton(i, true), del = outfitButton(i, false);
                c.fill(listX, y + ROW_H - 1, listX + listW, y + ROW_H, 0x30FFFFFF);
                int textW = wear[0] - GAP - listX - 4;
                c.text(Theme.ellipsize(c, o.name(), textW), listX + 4, y + 5, Theme.TEXT, false);
                c.text(Theme.ellipsize(c, summary(o.loadout()), textW), listX + 4, y + 16, Theme.TEXT_FAINT, false);
                int hx = hotList ? mx : -1, hy = hotList ? my : -1;
                boxButton(c, "WEAR", wear[0], wear[1], wear[2], wear[3], hx, hy, Theme.Family.SOFT, false, !busy);
                boxButton(c, "DELETE", del[0], del[1], del[2], del[3], hx, hy, Theme.Family.GREY, false, !busy);
            }
        }
        c.unscissor();
    }

    /** {x, y, w, h} of outfit {@code i}'s WEAR or DELETE. */
    private int[] outfitButton(int i, boolean wear) {
        int dw = this.font.width("DELETE") + 16, ww = this.font.width("WEAR") + 20;
        int y = listY + i * ROW_H - scroll + (ROW_H - 1 - BTN_H) / 2;
        int dx = listX + listW - dw;
        return wear ? new int[] {dx - GAP - ww, y, ww, BTN_H} : new int[] {dx, y, dw, BTN_H};
    }

    private void drawTile(Canvas c, Tile t, int x, int y, boolean hot) {
        Theme.box(c, x, y, tileW, tileH, t.family, hot, false);
        int m = 2 + Math.max(2, tileW / 28);
        t.thumb.draw(c, x + m, y + m, tileW - 2 * m, tileH - 2 * m - NAME_H);
        String label = Theme.ellipsize(c, t.label.toUpperCase(Locale.ROOT), tileW - 2 * m);
        int lx = x + (tileW - c.textWidth(label)) / 2, ly = y + tileH - m - NAME_H + (NAME_H - 7) / 2;
        if (t.worn) Theme.label(c, label, lx, ly, Theme.GREEN_UP, Theme.GREEN_LO, 1f);
        else c.text(label, lx, ly, Theme.DIM, false);
        if (t.badge != null) {
            String b = Theme.ellipsize(c, t.badge, tileW - 8);
            int bw = c.textWidth(b) + 6, bx = x + tileW - 3 - bw;
            c.fill(bx, y + 3, bx + bw, y + 14, 0xC0000000);
            int col = t.worn ? Theme.GREEN_LO : t.badge.equals("OWNED") ? Theme.TEXT_MUTED : Theme.ACCENT;
            c.text(b, bx + 3, y + 5, col, false);
        }
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (outfitField.w > 0 && outfitField.contains(mx, my)) return outfitField.click(mx, my, button);
        outfitField.setFocused(false);
        if (clickChrome(mx, my, button)) return true;
        if (Vanilla.inside(mx, my, stageX, stageY, stageW, stageH)) return false; // spin the model
        if (button != 0) return Vanilla.inside(mx, my, px, py, pw, ph);
        if (in(actionBox(false), mx, my)) {
            if (canCancel()) {
                if (tab == 0) {
                    SkinLibrary.Entry cur = current();
                    picked = cur == null ? null : cur.name();
                } else {
                    cancelLook();
                }
            }
            return true;
        }
        if (in(actionBox(true), mx, my)) {
            if (canApply()) {
                if (tab == 0) applySkin();
                else applyLook();
            }
            return true;
        }
        if (gallery.contains(mx, my)) {
            if (tab == CAPES && gallery.tabAt(mx, my) == 0) {
                mojangCapes = !mojangCapes;
                scroll = 0;
                status = "";
            } else if (tab == STORE && gallery.tabAt(mx, my) == 0) {
                storeFilter = (storeFilter + 1) % FILTERS.length;
                scroll = 0;
                buyArmed = -1;
            }
            return true;
        }
        if (tab == OUTFITS) {
            if (Vanilla.inside(mx, my, saveX, saveY, saveW, BTN_H)) {
                saveOutfit();
                return true;
            }
            List<Social.Outfit> list = outfits;
            if (!busy && list != null && inList(mx, my)) {
                for (int i = 0; i < list.size(); i++) {
                    if (in(outfitButton(i, true), mx, my)) {
                        wearOutfit(list.get(i));
                        return true;
                    }
                    if (in(outfitButton(i, false), mx, my)) {
                        deleteOutfit(list.get(i));
                        return true;
                    }
                }
            }
            return Vanilla.inside(mx, my, px, py, pw, ph);
        }
        if (!busy && inList(mx, my)) {
            List<Tile> tiles = tiles();
            for (int i = 0; i < tiles.size(); i++) {
                if (Vanilla.inside(mx, my, tileX(i), tileY(i), tileW, tileH)) {
                    tiles.get(i).click.run();
                    return true;
                }
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    protected boolean menuKey(int key, int scancode, int modifiers) {
        if (!outfitField.focused()) return super.menuKey(key, scancode, modifiers);
        if ((modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 && key == GLFW.GLFW_KEY_V) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip != null) {
                String text = (outfitName + clip.replaceAll("\\s+", " "));
                outfitField.setText(text.substring(0, Math.min(OUTFIT_NAME_MAX, text.length())));
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            saveOutfit();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            outfitField.setFocused(false);
            return true;
        }
        return outfitField.keyPressed(key, modifiers);
    }

    @Override
    protected boolean menuChar(char ch) {
        if (outfitField.focused()) return outfitField.charTyped(ch);
        return super.menuChar(ch);
    }

    @Override
    public void removed() {
        super.removed();
        images.releaseAll();
        worker.shutdown();
    }
}
