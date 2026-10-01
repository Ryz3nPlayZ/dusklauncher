package dev.dusk.client.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Force-loads every mixin target so a broken injection fails here, not in a
 * player's session. Mixins apply lazily, so a descriptor that stopped matching
 * (the 26.x particle mixins shipped that way for several releases) otherwise
 * only crashes once the game first touches the target class.
 */
public final class MixinAuditGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(client -> {
            try {
                MixinEnvironment.getCurrentEnvironment().audit();
            } catch (Throwable t) {
                throw new AssertionError("mixin audit failed", t);
            }
        });
    }
}
