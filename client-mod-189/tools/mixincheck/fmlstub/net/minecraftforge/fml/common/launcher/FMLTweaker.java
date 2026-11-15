package net.minecraftforge.fml.common.launcher;

import java.io.File;
import java.util.List;
import net.minecraft.launchwrapper.ITweaker;

/** Name-only stand-in: mixin's side detection looks for this class name. */
public class FMLTweaker implements ITweaker {
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) { }
    public void injectIntoClassLoader(net.minecraft.launchwrapper.LaunchClassLoader cl) { }
    public String getLaunchTarget() { return "net.minecraft.client.main.Main"; }
    public String[] getLaunchArguments() { return new String[0]; }
}
