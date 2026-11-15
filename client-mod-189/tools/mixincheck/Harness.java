import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.launch.MixinTweaker;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Applies duskclient's mixins to the real (MCP-named) Minecraft classes and
 * checks every hook landed in the transformed bytecode. This is the same
 * check the game does at startup — a wrong handler signature fails here
 * exactly like it crashes there.
 */
public class Harness {
    static final String OUT = "/tmp/dusk-mixincheck/out";

    /** {target class, dusk$ names that must appear in its transformed bytes} */
    static final String[][] TARGETS = {
        {"net.minecraft.client.Minecraft", "dusk$backgroundFps", "dusk$frameEnd",
            "dusk$freecamAttack", "dusk$freecamUse", "dusk$freecamPick", "dusk$freecamMine",
            "dusk$macInitialWindow", "dusk$macToggle", "dusk$macResize", "dusk$macSync",
            "dusk$hiDpiAsk", "dusk$hiDpiSize", "dusk$hiDpiWidth", "dusk$hiDpiHeight", "dusk$hiDpiScreenChange",
            "dusk$afterSwap", "dusk$tickStart", "dusk$tickEnd", "dusk$noForcedGc"},
        {"net.minecraft.client.renderer.EntityRenderer",
            "dusk$beforeMouseRead", "dusk$mouseX", "dusk$mouseY", "dusk$gamma", "dusk$lightmapUpload",
            "dusk$guiMouseX", "dusk$guiMouseY",
            "dusk$bobTranslate", "dusk$bobRotate0", "dusk$bobRotate1", "dusk$blurPrePass",
            "dusk$turn", "dusk$camYaw", "dusk$camPrevYaw", "dusk$camPitch", "dusk$camPrevPitch"},
        {"net.minecraft.client.network.NetHandlerPlayClient", "dusk$entityStatus", "dusk$swing", "dusk$timeUpdate"},
        {"net.minecraft.client.renderer.entity.RendererLivingEntity",
            "dusk$nametags", "dusk$tintPrepare", "dusk$tintR", "dusk$tintG", "dusk$tintB", "dusk$tintMix",
            "dusk$upsideDown"},
        {"net.minecraft.client.renderer.entity.RenderManager", "dusk$showHitboxes", "dusk$drawHitbox", "dusk$freelookView"},
        {"net.minecraft.client.renderer.RenderGlobal", "dusk$selection", "dusk$freelookCulling"},
        {"net.minecraft.client.gui.GuiIngame", "dusk$scoreboard"},
        {"net.minecraft.client.gui.GuiPlayerTabOverlay", "dusk$columnWidth", "dusk$ping"},
        {"net.minecraft.client.renderer.ItemRenderer", "dusk$lowFire"},
        {"net.minecraft.client.particle.EffectRenderer", "dusk$gate", "dusk$style", "dusk$glow"},
        {"net.minecraft.client.particle.EntityFX", "dusk$age", "dusk$maxAge"},
        {"net.minecraft.entity.Entity", "dusk$fullBright"},
        {"net.minecraft.client.settings.KeyBinding", "dusk$setPressed"},
        {"net.minecraft.client.gui.inventory.GuiContainer", "dusk$slotAt", "dusk$click",
            "dusk$dragSplitting", "dusk$setDragSplitting", "dusk$dragSplittingButton", "dusk$setIgnoreMouseUp"},
        {"net.minecraft.client.gui.GuiButton", "dusk$modern"},
        {"net.minecraft.client.renderer.ActiveRenderInfo", "dusk$yaw", "dusk$pitch"},
        {"net.minecraft.world.World", "dusk$cleanVoid"},
        {"net.minecraft.client.entity.AbstractClientPlayer", "dusk$cape"},
        {"net.minecraft.client.renderer.entity.layers.LayerCape", "dusk$capture", "dusk$renderCape"},
        {"net.minecraft.client.renderer.entity.layers.LayerDeadmau5Head", "dusk$ears"},
        {"net.minecraft.client.gui.GuiScreen", "dusk$eventX", "dusk$eventY"},
        {"net.minecraft.client.gui.GuiChat", "dusk$x", "dusk$y"},
        {"net.minecraft.client.gui.ScaledResolution", "dusk$guiScale"},
        {"net.minecraft.scoreboard.Scoreboard", "dusk$noTeam", "dusk$noObjective"},
        {"net.minecraft.profiler.Profiler", "dusk$sectionStart", "dusk$sectionEnd"},
    };

    public static void main(String[] args) throws Exception {
        File[] cp = new File(args[0]).listFiles();
        java.net.URL[] urls = new java.net.URL[cp.length];
        for (int i = 0; i < cp.length; i++) urls[i] = cp[i].toURI().toURL();

        Files.createDirectories(Paths.get(OUT));

        LaunchClassLoader cl = new LaunchClassLoader(urls);
        // what Launch.main would normally provide
        if (Launch.blackboard == null) Launch.blackboard = new java.util.HashMap<String, Object>();
        Launch.blackboard.put("Tweaks", java.util.Collections.singletonList(
                new net.minecraftforge.fml.common.launcher.FMLTweaker())); // -> side CLIENT
        Launch.classLoader = cl;
        // Real tweaker path: the platform manager scans this jar's manifest
        // (MixinConfigs) and registers the config. MixinPlatformAgentFML is
        // deleted from this test copy of the jar: without a booted FML its
        // remapper hookup NPEs, so the Default agent handles the container.
        new MixinTweaker().injectIntoClassLoader(cl);
        // Production advances PREINIT -> DEFAULT through the launch lifecycle;
        // this harness transforms while still in PREINIT, so the config goes
        // straight into that environment.
        MixinEnvironment.getEnvironment(MixinEnvironment.Phase.PREINIT)
                .addConfiguration("mixins.duskclient.json");
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        cl.registerTransformer("Recorder");

        int failures = 0;
        for (String[] target : TARGETS) {
            String cls = target[0];
            try {
                cl.findClass(cls); // define + transform, no static init, no parent fallback
            } catch (Throwable t) {
                System.out.println("FAIL apply " + cls);
                t.printStackTrace(System.out);
                failures++;
                continue;
            }
            File file = new File(OUT, cls.replace('.', '_') + ".class");
            if (!file.isFile()) {
                System.out.println("FAIL no recorded bytes for " + cls);
                failures++;
                continue;
            }
            String bytes = new String(Files.readAllBytes(file.toPath()), "ISO-8859-1");
            for (int i = 1; i < target.length; i++) {
                if (!bytes.contains(target[i])) {
                    System.out.println("FAIL " + cls + " missing hook " + target[i]);
                    failures++;
                }
            }
        }
        System.out.println(failures == 0
                ? "ALL " + TARGETS.length + " MIXINS APPLIED, ALL HOOKS WIRED"
                : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
