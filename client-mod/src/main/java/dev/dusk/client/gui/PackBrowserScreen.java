package dev.dusk.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dusk.client.account.Http;
import dev.dusk.client.gui.widget.TextFieldWidget;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modrinth's resource packs and shaders for this Minecraft version, like the
 * launcher's browse page: search and install into the game's resourcepacks /
 * shaderpacks folder. Turning packs on stays in the vanilla screens.
 */
public class PackBrowserScreen extends PanelScreen {
    public static final int RESOURCE_PACKS = 0, SHADERS = 1;
    private static final String API = "https://api.modrinth.com/v2";
    private static final String[] TABS = {"RESOURCE PACKS", "SHADERS"};
    private static final int PAGE = 20, ROW_GAP = 4, SEARCH_DELAY_MS = 350;

    private record Hit(String id, String title, String author, String description, long downloads, @Nullable String icon) {}

    private enum Install { NONE, BUSY, DONE, FAILED }

    private final RemoteImages icons = new RemoteImages(64);
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "duskclient-packs");
        t.setDaemon(true);
        return t;
    });
    private final TextFieldWidget search;
    private final String mcVersion;
    private final boolean irisLoaded;

    private int tab;
    private String query = "";
    private long queryAt;
    private int generation;
    private final List<Hit> hits = new ArrayList<>();
    private int total = -1;
    private boolean loading, more;
    @Nullable private String error;
    private final Map<String, Install> installs = new HashMap<>();
    private final Map<String, String> installErrors = new HashMap<>();
    private int rowH;

    public PackBrowserScreen(@Nullable Screen parent, int tab) {
        super(Component.literal("Browse packs"), parent);
        this.tab = tab;
        this.mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
        this.irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
        this.search = new TextFieldWidget(() -> "", q -> {
            query = q.trim();
            queryAt = System.currentTimeMillis();
        }, true, 60).themed().placeholder("Search Modrinth...");
        fetch(true);
    }

    // ---- data ---------------------------------------------------------------

    private String projectType() {
        return tab == SHADERS ? "shader" : "resourcepack";
    }

    private Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(tab == SHADERS ? "shaderpacks" : "resourcepacks");
    }

    private void fetch(boolean reset) {
        if (reset) {
            generation++;
            hits.clear();
            total = -1;
            scroll = 0;
            more = false;
        }
        loading = true;
        error = null;
        int gen = generation, offset = hits.size();
        String facets = "[[\"project_type:" + projectType() + "\"]" + (mcVersion.isEmpty() ? "" : ",[\"versions:" + mcVersion + "\"]") + "]";
        String url = API + "/search?query=" + enc(query) + "&facets=" + enc(facets) + "&limit=" + PAGE + "&offset=" + offset
                + "&index=" + (query.isEmpty() ? "downloads" : "relevance");
        worker.execute(() -> {
            List<Hit> page = new ArrayList<>();
            int count;
            String fail = null;
            try {
                Http.Response r = Http.get(url, null);
                if (!r.ok()) throw new IOException(r.error("Modrinth"));
                JsonObject o = r.json().getAsJsonObject();
                count = o.get("total_hits").getAsInt();
                for (JsonElement e : o.getAsJsonArray("hits")) {
                    JsonObject h = e.getAsJsonObject();
                    page.add(new Hit(str(h, "project_id"), str(h, "title"), str(h, "author"), str(h, "description"),
                            h.has("downloads") ? h.get("downloads").getAsLong() : 0, h.has("icon_url") && !h.get("icon_url").isJsonNull() ? str(h, "icon_url") : null));
                }
            } catch (IOException | RuntimeException e) {
                count = 0;
                fail = "Could not reach Modrinth: " + e.getMessage();
            }
            int n = count;
            String f = fail;
            Minecraft.getInstance().execute(() -> {
                if (gen != generation) return;
                loading = false;
                error = f;
                hits.addAll(page);
                total = n;
                more = f == null && hits.size() < n;
            });
        });
    }

    private void install(Hit hit) {
        if (installs.get(hit.id) == Install.BUSY) return;
        installs.put(hit.id, Install.BUSY);
        installErrors.remove(hit.id);
        Path dir = folder();
        String versions = enc("[\"" + mcVersion + "\"]");
        worker.execute(() -> {
            Install result = Install.DONE;
            String fail = null;
            try {
                Http.Response r = Http.get(API + "/project/" + hit.id + "/version" + (mcVersion.isEmpty() ? "" : "?game_versions=" + versions), null);
                if (!r.ok()) throw new IOException(r.error("Modrinth"));
                JsonArray list = r.json().getAsJsonArray();
                if (list.isEmpty()) throw new IOException("No version for " + mcVersion);
                JsonArray files = list.get(0).getAsJsonObject().getAsJsonArray("files");
                JsonObject file = files.get(0).getAsJsonObject();
                for (JsonElement e : files) {
                    if (e.getAsJsonObject().has("primary") && e.getAsJsonObject().get("primary").getAsBoolean()) {
                        file = e.getAsJsonObject();
                        break;
                    }
                }
                String name = Path.of(str(file, "filename")).getFileName().toString();
                if (name.isEmpty() || name.startsWith(".")) throw new IOException("Bad file name");
                Http.Response dl = Http.get(str(file, "url"), null);
                if (!dl.ok()) throw new IOException("Download failed (" + dl.code() + ")");
                String sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
                if (!sha1.equalsIgnoreCase(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(dl.body())))) {
                    throw new IOException("Checksum mismatch");
                }
                Files.createDirectories(dir);
                Path tmp = dir.resolve(name + ".part");
                Files.write(tmp, dl.body());
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
                result = Install.FAILED;
                fail = e.getMessage();
            }
            Install done = result;
            String f = fail;
            Minecraft.getInstance().execute(() -> {
                installs.put(hit.id, done);
                if (f != null) installErrors.put(hit.id, f);
            });
        });
    }

    private static void openFolder(Path dir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
        try {
            new ProcessBuilder(cmd, dir.toAbsolutePath().toString()).start();
        } catch (IOException ignored) {
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1e3);
        return Long.toString(n);
    }

    // ---- layout -------------------------------------------------------------

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
        if (i == tab) return;
        tab = i;
        fetch(true);
    }

    @Override
    protected List<Tool> tools() {
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("close", Icons.CLOSE, "", "Close"));
        t.add(new Tool("folder", null, "OPEN FOLDER", "Open the " + (tab == SHADERS ? "shaderpacks" : "resourcepacks") + " folder"));
        return t;
    }

    @Override
    protected void onTool(String id) {
        if (id.equals("folder")) {
            Path dir = folder();
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
            }
            openFolder(dir);
        } else {
            super.onTool(id);
        }
    }

    @Override
    protected TextFieldWidget search() {
        return search;
    }

    private void layout() {
        layoutPanel();
        listX = px + pad;
        listY = bodyY() + pad;
        listW = px + pw - pad - BAR_W - 4 - listX;
        listBottom = py + ph - pad - (tab == SHADERS && !irisLoaded ? 12 : 0);
        rowH = 44;
        clampScroll();
    }

    @Override
    protected int contentHeight() {
        int n = hits.size() + (more || loading || error != null || total == 0 ? 1 : 0);
        return n == 0 ? 0 : n * (rowH + ROW_GAP) - ROW_GAP;
    }

    private int rowY(int i) {
        return listY + i * (rowH + ROW_GAP) - scroll;
    }

    private int[] installBox(int i) {
        int w = 70, h = 18;
        return new int[] {listX + listW - w - 6, rowY(i) + (rowH - h) / 2, w, h};
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    protected void drawMenu(Canvas c, int mouseX, int mouseY, float delta) {
        if (queryAt != 0 && System.currentTimeMillis() - queryAt >= SEARCH_DELAY_MS) {
            queryAt = 0;
            fetch(true);
        }
        layout();
        // near the end of the list: fetch the next page
        if (more && !loading && scroll >= maxScroll() - rowH * 2) fetch(false);

        drawPanel(c, mouseX, mouseY);
        boolean hotList = inList(mouseX, mouseY);
        c.scissor(listX, listY, listX + listW, listBottom);
        for (int i = 0; i < hits.size(); i++) {
            int y = rowY(i);
            if (y + rowH < listY || y > listBottom) continue;
            drawRow(c, i, hits.get(i), y, mouseX, mouseY, hotList);
        }
        String foot = error != null ? error : loading ? "Loading..." : total == 0 ? "Nothing found for " + (mcVersion.isEmpty() ? "this version" : mcVersion) + "." : null;
        if (foot != null) {
            int y = rowY(hits.size());
            c.text(Theme.ellipsize(c, foot, listW), listX + 4, y + (rowH - 8) / 2, error != null ? Theme.RED_UP : Theme.TEXT_MUTED, false);
        }
        c.unscissor();
        if (tab == SHADERS && !irisLoaded) {
            c.text("Shaders need Iris, which isn't installed in this instance.", listX, py + ph - pad - 8, Theme.RED_UP, false);
        }
        drawScrollbar(c);
    }

    private void drawRow(Canvas c, int i, Hit hit, int y, int mouseX, int mouseY, boolean hotList) {
        Theme.plate(c, listX, y, listW, rowH, Theme.SURFACE, Theme.SURFACE, false);
        int is = rowH - 8, ix = listX + 4, iy = y + 4;
        c.fill(ix, iy, ix + is, iy + is, 0x33000000);
        if (hit.icon != null) {
            RemoteImages.Image img = icons.get(hit.icon, () -> Http.get(hit.icon, null).body());
            if (img != null) {
                c.push();
                c.translate(ix, iy);
                c.scale(is / (float) img.w(), is / (float) img.h());
                c.blit(img.id(), 0, 0, 0, 0, img.w(), img.h(), img.w(), img.h());
                c.pop();
            }
        }
        int[] b = installBox(i);
        int tx = ix + is + 8, tw = b[0] - 8 - tx;
        String by = " by " + hit.author;
        String title = Theme.ellipsize(c, hit.title, tw);
        c.text(title, tx, y + 6, 0xFFFFFFFF, false);
        int after = tx + c.textWidth(title);
        if (after + c.textWidth(by) <= tx + tw) c.text(by, after, y + 6, Theme.TEXT_MUTED, false);
        String err = installErrors.get(hit.id);
        c.text(Theme.ellipsize(c, err != null ? err : hit.description, tw), tx, y + 18, err != null ? Theme.RED_UP : Theme.TEXT_MUTED, false);
        c.text(count(hit.downloads) + " downloads", tx, y + 30, 0x80FFFFFF, false);

        Install st = installs.getOrDefault(hit.id, Install.NONE);
        String label = switch (st) {
            case NONE -> "INSTALL";
            case BUSY -> "...";
            case DONE -> "INSTALLED";
            case FAILED -> "RETRY";
        };
        boolean enabled = st == Install.NONE || st == Install.FAILED;
        flatButton(c, label, b[0], b[1], b[2], b[3], hotList ? mouseX : -1, hotList ? mouseY : -1, st == Install.DONE, enabled);
    }

    // ---- input --------------------------------------------------------------

    @Override
    protected boolean menuClick(double mx, double my, int button) {
        layout();
        if (clickChrome(mx, my, button)) return true;
        if (button == 0 && inList(mx, my)) {
            for (int i = 0; i < hits.size(); i++) {
                int[] b = installBox(i);
                if (Vanilla.inside(mx, my, b[0], b[1], b[2], b[3])) {
                    Install st = installs.getOrDefault(hits.get(i).id, Install.NONE);
                    if (st == Install.NONE || st == Install.FAILED) install(hits.get(i));
                    return true;
                }
            }
        }
        return Vanilla.inside(mx, my, px, py, pw, ph);
    }

    @Override
    public void removed() {
        super.removed();
        icons.releaseAll();
        worker.shutdown();
    }
}
