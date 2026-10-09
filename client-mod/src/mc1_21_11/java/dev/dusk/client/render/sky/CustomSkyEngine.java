package dev.dusk.client.render.sky;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.modules.render.CustomSkies;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;

/**
 * OptiFine-format custom skies, drawn by the client itself: the layers a
 * resource pack puts in {@code optifine/sky} (or {@code mcpatcher/sky}) go
 * over the vanilla sky, after the sunrise glow and before the sun, moon and
 * stars — where OptiFine draws them. The End's go over its sky box.
 *
 * Each version's {@code SkyGpu} does the drawing; this decides what to draw.
 */
public final class CustomSkyEngine {
    public static final int ALPHA = 0, ADD = 1, SUBTRACT = 2, MULTIPLY = 3, DODGE = 4, BURN = 5, SCREEN = 6, OVERLAY = 7, REPLACE = 8;
    public static final int BLEND_COUNT = 9;
    public static final int CUBE_VERTICES = 24, CUBE_INDICES = 36;

    public interface Sink {
        void draw(Identifier texture, int blend, Matrix4f modelView, Vector4f color);
    }

    public interface CubeSink {
        void vertex(float x, float y, float z, float u, float v);
    }

    private static volatile boolean dirty = true;
    private static CustomSkyLoader.Skies skies = new CustomSkyLoader.Skies(List.of(), List.of());

    // the frame being drawn, as the sky's render state saw it
    private static WeakReference<ClientLevel> frameLevel = new WeakReference<>(null);
    private static WeakReference<ClientLevel> layersLevel = new WeakReference<>(null);
    private static float celestial, rainLevel, thunderLevel;

    private CustomSkyEngine() {}

    /** The sky renderer was rebuilt (a resource reload): read the packs again before the next frame. */
    public static void markDirty() {
        dirty = true;
    }

    public static void extract(ClientLevel level, float partialTick, float sunAngle, float rainBrightness) {
        if (!CustomSkies.on()) {
            frameLevel = new WeakReference<>(null);
            return;
        }
        if (dirty) {
            dirty = false;
            reload();
        }
        frameLevel = new WeakReference<>(level);
        celestial = Mth.positiveModulo(sunAngle / Mth.TWO_PI, 1);
        rainLevel = 1 - rainBrightness;
        float thunder = level.getThunderLevel(partialTick);
        thunderLevel = rainLevel > 0 ? thunder / rainLevel : thunder;
    }

    private static void reload() {
        Minecraft mc = Minecraft.getInstance();
        try {
            skies = CustomSkyLoader.load(mc.getResourceManager());
        } catch (Throwable t) {
            skies = new CustomSkyLoader.Skies(List.of(), List.of());
            System.err.println("[DuskClient] couldn't read the resource packs' custom skies: " + t);
        }
        layersLevel = new WeakReference<>(null);
    }

    /** Anything to draw for this kind of sky this frame. */
    public static boolean has(boolean end) {
        return frameLevel.get() != null && !(end ? skies.end() : skies.overworld()).isEmpty();
    }

    /** Draws each showing layer of the overworld's (or End's) sky over {@code base}, the sky's model-view. */
    public static void draw(boolean end, Matrix4fc base, Sink sink) {
        ClientLevel level = frameLevel.get();
        if (level == null) return;
        List<SkyLayer> layers = end ? skies.end() : skies.overworld();
        if (layers.isEmpty()) return;
        if (layersLevel.get() != level) {
            layersLevel = new WeakReference<>(level);
            for (SkyLayer l : skies.overworld()) l.resetPosition();
            for (SkyLayer l : skies.end()) l.resetPosition();
        }
        Entity camera = Minecraft.getInstance().getCameraEntity();
        BlockPos pos = camera == null ? null : camera.blockPosition();
        long dayTime = Compat.dayTime(level);
        int timeOfDay = (int) (dayTime % SkyLayer.DAY);
        if (timeOfDay < 0) timeOfDay += SkyLayer.DAY;
        for (SkyLayer layer : layers) {
            if (!layer.showing(dayTime, timeOfDay)) continue;
            float alpha = layer.alpha(level, pos, timeOfDay, rainLevel, thunderLevel);
            if (alpha < 0.0001f) continue;
            Matrix4f mv = new Matrix4f(base);
            if (layer.rotate) {
                float rad = layer.rotation(dayTime, celestial) * Mth.DEG_TO_RAD;
                mv.rotate(new Quaternionf().rotationAxis(rad, layer.axisX, layer.axisY, layer.axisZ));
            }
            sink.draw(layer.texture, layer.blend, mv, color(layer.blend, alpha));
        }
    }

    /** What the texture is multiplied by, so each blend fades the way OptiFine's does. */
    private static Vector4f color(int blend, float a) {
        return switch (blend) {
            case ALPHA, ADD, REPLACE -> new Vector4f(1, 1, 1, a);
            case MULTIPLY -> new Vector4f(a, a, a, a);
            default -> new Vector4f(a, a, a, 1);
        };
    }

    public static String blendName(int blend) {
        return CustomSkyLoader.blendName(blend);
    }

    /**
     * The sky cube OptiFine maps a sky texture onto: a 3×2 sheet, bottom, top
     * and the four sides, 100 blocks out.
     */
    public static void cube(CubeSink sink) {
        Matrix4fStack faces = new Matrix4fStack(4);
        faces.rotateX(90 * Mth.DEG_TO_RAD).rotateZ(-90 * Mth.DEG_TO_RAD);
        side(faces, sink, 4);
        faces.pushMatrix().rotateX(90 * Mth.DEG_TO_RAD);
        side(faces, sink, 1);
        faces.popMatrix();
        faces.pushMatrix().rotateX(-90 * Mth.DEG_TO_RAD);
        side(faces, sink, 0);
        faces.popMatrix();
        faces.rotateZ(90 * Mth.DEG_TO_RAD);
        side(faces, sink, 5);
        faces.rotateZ(90 * Mth.DEG_TO_RAD);
        side(faces, sink, 2);
        faces.rotateZ(90 * Mth.DEG_TO_RAD);
        side(faces, sink, 3);
    }

    private static void side(Matrix4fc m, CubeSink sink, int side) {
        float u = (side % 3) / 3f, v = (side / 3) / 2f;
        corner(m, sink, -100, -100, u, v);
        corner(m, sink, -100, 100, u, v + 0.5f);
        corner(m, sink, 100, 100, u + 1 / 3f, v + 0.5f);
        corner(m, sink, 100, -100, u + 1 / 3f, v);
    }

    private static void corner(Matrix4fc m, CubeSink sink, float x, float z, float u, float v) {
        Vector3f p = m.transformPosition(new Vector3f(x, -100, z));
        sink.vertex(p.x, p.y, p.z, u, v);
    }

    /** With Iris shaders on, have the shader pack treat our layers as textured sky (as Nuit does). */
    public static void assignIris(Object pipeline) {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object iris = api.getMethod("getInstance").invoke(null);
            Class<?> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object sky = Enum.valueOf((Class) program, "SKY_TEXTURED");
            for (Method m : api.getMethods()) {
                if (m.getName().equals("assignPipeline") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].isInstance(pipeline) && m.getParameterTypes()[1] == program) {
                    m.invoke(iris, pipeline, sky);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // no Iris, or an Iris without the pipeline API
        }
    }
}
