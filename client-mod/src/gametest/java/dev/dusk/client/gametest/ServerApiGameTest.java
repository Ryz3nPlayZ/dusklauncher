package dev.dusk.client.gametest;

import dev.dusk.client.DuskClient;
import dev.dusk.client.modules.hud.Coordinates;
import dev.dusk.client.modules.render.Fullbright;
import dev.dusk.client.server.ServerChannel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The server API end to end on the integrated server: it registers {@code dusk:hello}
 * (so the client greets it), then blocks and unblocks modules over {@code dusk:rules};
 * leaving the world lifts the block and keeps the player's own toggles.
 */
public final class ServerApiGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        AtomicReference<String> hello = new AtomicReference<>();
        ServerPlayNetworking.registerGlobalReceiver(ServerChannel.Hello.TYPE, (payload, context) -> hello.set(payload.json()));
        ctx.runOnClient(client -> {
            Fullbright.instance().setEnabled(true);
            DuskClient.modules().get(Coordinates.class).setEnabled(true);
        });

        try (TestSingleplayerContext sp = Worlds.create(ctx)) {
            HarnessCompat.waitForChunksRender(sp);
            ctx.waitFor(client -> hello.get() != null, 100);
            String greeting = hello.get();
            if (!greeting.contains("\"protocol\":1") || !greeting.contains("\"fullbright\"")) {
                throw new AssertionError("unexpected dusk:hello " + greeting);
            }

            sendRules(sp, "{\"disable\":[\"fullbright\",\"nosuchmodule\"]}");
            ctx.waitFor(client -> Fullbright.instance().blocked(), 40);
            ctx.runOnClient(client -> {
                Fullbright fb = Fullbright.instance();
                if (fb.enabled()) throw new AssertionError("blocked fullbright still enabled");
                fb.setEnabled(false);
                fb.setEnabled(true); // the player re-ticking it must not beat the block
                if (fb.enabled()) throw new AssertionError("player toggle overrode the server block");
                if (!fb.description().contains("server")) throw new AssertionError("blocked description not shown");
                if (!Boolean.TRUE.equals(fb.saveState().get("enabled"))) throw new AssertionError("block leaked into the saved config");
                if (!DuskClient.modules().get(Coordinates.class).enabled()) throw new AssertionError("unlisted module was blocked");
            });

            sendRules(sp, "{\"disable\":[]}");
            ctx.waitFor(client -> Fullbright.instance().enabled(), 40);

            sendRules(sp, "not json");
            sendRules(sp, "{\"disable\":[\"coords\"]}");
            ctx.waitFor(client -> DuskClient.modules().get(Coordinates.class).blocked(), 40);
        }
        ctx.runOnClient(client -> {
            if (DuskClient.modules().get(Coordinates.class).blocked()) throw new AssertionError("block outlived the connection");
            if (!DuskClient.modules().get(Coordinates.class).enabled()) throw new AssertionError("player's toggle lost after unblock");
        });
    }

    private static void sendRules(TestSingleplayerContext sp, String json) {
        sp.getServer().runOnServer(server -> {
            for (var player : server.getPlayerList().getPlayers()) {
                ServerPlayNetworking.send(player, new ServerChannel.Rules(json));
            }
        });
    }
}
