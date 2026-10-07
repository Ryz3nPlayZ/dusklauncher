package dev.dusk.client.cosmetics;

import dev.dusk.client.account.Http;
import dev.dusk.client.account.SkinLibrary;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Skins of cracked (offline) players, kept on Dusk and looked up by name the
 * way PineconeMC's skin server works: a player whose profile carries no
 * Mojang textures is asked of {@code /v1/skins/{name}}, so a cracked Dusk
 * user's skin shows to every Dusk player on any server. Names are cached
 * for a few minutes, misses too.
 */
public final class DuskSkins {
    private static final long TTL_MS = 5 * 60_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Dusk skins");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static int generation;

    private DuskSkins() {}

    /** A registered skin texture, plus the vanilla skin last built around it. */
    public static final class Skin {
        private final CapeTexture texture;
        private final boolean slim;
        @Nullable private Object lastIn;
        @Nullable private Object lastOut;

        Skin(CapeTexture texture, boolean slim) {
            this.texture = texture;
            this.slim = slim;
        }

        public CapeTexture texture() {
            return texture;
        }

        public boolean slim() {
            return slim;
        }

        /** {@code make(original)}, built once per original so the render path doesn't allocate. */
        @SuppressWarnings("unchecked")
        public synchronized <T> T wrap(T original, UnaryOperator<T> make) {
            if (lastIn != original || lastOut == null) {
                lastIn = original;
                lastOut = make.apply(original);
            }
            return (T) lastOut;
        }
    }

    private static final class Entry {
        @Nullable volatile Skin skin;
        @Nullable volatile String etag;
        volatile long until;
        volatile boolean loading;
    }

    /** {@code name}'s Dusk skin once it has loaded; null while loading, or when they have none. */
    @Nullable
    public static Skin get(@Nullable String name) {
        if (name == null || !NAME.matcher(name).matches()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        Entry e = CACHE.computeIfAbsent(key, k -> new Entry());
        if (!e.loading && System.currentTimeMillis() >= e.until) {
            e.loading = true;
            POOL.execute(() -> load(key, e));
        }
        Skin s = e.skin;
        return s != null && s.texture.isRegistered() ? s : null;
    }

    /** Ask again on the next lookup (after this player changed their skin). */
    public static void refresh(String name) {
        Entry e = CACHE.get(name.toLowerCase(Locale.ROOT));
        if (e != null) e.until = 0;
    }

    private static void load(String key, Entry e) {
        try {
            Http.Response r = Http.get(DuskProvider.apiBase() + "/v1/skins/" + key, null);
            if (r.code() == 404) {
                swap(e, null);
                e.etag = null;
                return;
            }
            if (!r.ok()) return;
            String etag = r.header("ETag");
            if (etag != null && etag.equals(e.etag) && e.skin != null) return;
            boolean slim = "slim".equalsIgnoreCase(r.header("X-Skin-Model"));
            int gen;
            synchronized (DuskSkins.class) {
                gen = ++generation;
            }
            CapeTexture tex = CapeTexture.prepareFrames("skins/" + key + "/" + gen, SkinLibrary.modernize(r.body()), 1, CapeTexture.FRAME_MS);
            if (tex == null) return;
            e.etag = etag;
            swap(e, new Skin(tex, slim));
        } catch (Exception ignored) {
            // offline or the service is down: try again after the TTL
        } finally {
            e.until = System.currentTimeMillis() + TTL_MS;
            e.loading = false;
        }
    }

    /** Put {@code next} in place on the render thread, freeing the texture it replaces. */
    private static void swap(Entry e, @Nullable Skin next) {
        Minecraft.getInstance().execute(() -> {
            if (next != null) next.texture.register();
            Skin old = e.skin;
            e.skin = next;
            if (old != null) old.texture.release();
        });
    }
}
