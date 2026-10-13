package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Draws the world at a share of the window's resolution and stretches it to
 * fit, while the HUD, chat and menus are drawn after it at the full size, so
 * a Retina Mac can keep the frame rate of a lowered resolution without blurry
 * text. The idea is Resolution Control's (UltimateBoomer, MIT); the 1.21.11
 * side swaps the main render target for the world pass only.
 */
public class RenderScale extends Module {
    private static RenderScale instance;

    private final IntSetting scale = add(new IntSetting("scale", "World resolution", 50, 25, 100, 5, "%"));
    private final BoolSetting sharp = add(new BoolSetting("sharp", "Sharp pixels", false));

    public RenderScale() {
        super("renderscale", "Render Scale", Category.RENDER,
                "Draws the world at a lower resolution for more FPS while the HUD and menus stay sharp.");
        instance = this;
    }

    /** The world's share of the window, 1 when off or a shader pack draws the world itself. */
    public static float factor() {
        if (instance == null || !instance.enabled() || IrisCheck.shaders()) return 1f;
        return instance.scale.get() / 100f;
    }

    /** Nearest-pixel stretch instead of the smooth one. */
    public static boolean sharp() {
        return instance != null && instance.sharp.get();
    }

    /** Iris with a shader pack renders to its own targets; scaling stays out of its way. */
    private static final class IrisCheck {
        private static final java.lang.reflect.Method IN_USE;
        private static final Object API;

        static {
            Object api = null;
            java.lang.reflect.Method m = null;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                m = c.getMethod("isShaderPackInUse");
            } catch (Throwable ignored) {
            }
            API = api;
            IN_USE = m;
        }

        static boolean shaders() {
            if (IN_USE == null) return false;
            try {
                return (Boolean) IN_USE.invoke(API);
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
