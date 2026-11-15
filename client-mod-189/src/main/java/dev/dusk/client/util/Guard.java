package dev.dusk.client.util;

import dev.dusk.client.module.Module;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * On 1.8.9 Forge an exception in an event handler or mixin hook takes the
 * whole game down. Every Dusk hook runs its module code through here instead:
 * the first failure is logged with its stack trace, the module is switched
 * off, and the game keeps going.
 */
public final class Guard {
    private static final Logger LOG = LogManager.getLogger("Dusk Client");
    private static final Set<String> reported = new HashSet<String>();

    private Guard() {}

    public static void run(String where, Module module, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            fail(where, module, t);
        }
    }

    public static void fail(String where, Module module, Throwable t) {
        if (reported.add(where)) {
            LOG.error("Dusk Client: " + where + " failed"
                    + (module != null ? ", turning " + module.name() + " off" : ""), t);
        }
        if (module == null || !module.enabled()) return;
        try {
            module.setEnabled(false);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.ingameGUI != null) mc.ingameGUI.setRecordPlaying(module.name() + " hit an error and was turned off", false);
        } catch (Throwable ignored) {
            // the module's own onDisable failed too; it's off either way
        }
    }
}
