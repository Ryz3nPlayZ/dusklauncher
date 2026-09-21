package dev.dusk.client.gametest;

import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.MinecraftCapesProvider;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What another player looks like to us (docs/COSMETICS.md §3.1): a player who
 * uploaded a cape at minecraftcapes.net must show up in Dusk through the very
 * path the renderer uses for every non-local player. The subject is the
 * MinecraftCapes author's own profile (animated cape, glint, ears — all three
 * features at once), fetched live, so the test is skipped rather than failed
 * when there is no network.
 *
 * <p>It also pins the current limit: a non-local player never gets registry
 * accessories or registry capes until the Dusk cosmetics server (phase 3)
 * exists — only their MinecraftCapes profile.
 */
public final class CrossModCapesGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("duskclient/gametest");
    /** James090500 — the MinecraftCapes maintainer; has cape + ears + glint. */
    private static final UUID SUBJECT = UUID.fromString("ba4161c0-3a42-496c-8ae0-7d13372f3371");
    private static final String SUBJECT_NAME = "James090500";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // the raw provider first, off the harness thread (it reads the client proxy,
        // which the harness only allows from a plain worker — as in production)
        MinecraftCapesProvider.Profile profile = fetchDirect();
        if (profile == null) {
            LOG.warn("api.minecraftcapes.net unreachable — skipping the cross-mod visibility check");
            ctx.waitForScreen(TitleScreen.class);
            return;
        }
        if (profile.cape() == null) throw new AssertionError("MinecraftCapes profile for " + SUBJECT_NAME + " has no cape");
        LOG.info("MinecraftCapes profile: cape {} B, ears {} B, glint {}, upsideDown {}",
                profile.cape().length, profile.ears() == null ? 0 : profile.ears().length, profile.glint(), profile.upsideDown());

        // now through the manager, exactly as the render path resolves a tab-list player
        boolean local = ctx.computeOnClient(client -> client.getUser().getProfileId().equals(SUBJECT));
        if (local) throw new AssertionError("test account must not be the subject");
        int ticks = ctx.waitFor(client -> CosmeticsManager.get(SUBJECT, SUBJECT_NAME).hasCape(), 600);
        LOG.info("Remote MinecraftCapes cape resolved after {} tick(s)", ticks);

        PlayerCosmetics c = ctx.computeOnClient(client -> CosmeticsManager.get(SUBJECT, SUBJECT_NAME));
        if (!c.hasCape()) throw new AssertionError("remote cape not registered");
        if (profile.ears() != null && !c.hasEars()) throw new AssertionError("remote ears not registered");
        if (c.glint() != profile.glint()) throw new AssertionError("glint flag lost: " + c.glint() + " vs " + profile.glint());
        if (!c.accessories().isEmpty()) {
            throw new AssertionError("a non-local player must not get registry accessories before phase 3: " + c.accessories());
        }
        LOG.info("Cross-mod visibility OK: MinecraftCapes user {} renders in Dusk with cape/ears/glint", SUBJECT_NAME);

        ctx.waitForScreen(TitleScreen.class);
    }

    private static MinecraftCapesProvider.@org.jetbrains.annotations.Nullable Profile fetchDirect() {
        try {
            return CompletableFuture.supplyAsync(() -> MinecraftCapesProvider.fetch(SUBJECT, SUBJECT_NAME)).join();
        } catch (RuntimeException e) {
            LOG.warn("MinecraftCapes fetch threw", e);
            return null;
        }
    }
}
