package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * Polyfrost's PolyNametag: nametag scale, height, shadow, colours and a
 * padded or rounded background, plus showing your own nametag and hiding
 * nametags by kind. Settings and defaults follow PolyNametag 1.2.0.
 */
public class Nametags extends Module {
    private static Nametags instance;

    public final BoolSetting removeNametags = add(new BoolSetting("removeNametags", "Remove nametags", false), "General");
    /** Hundredths of a text pixel, -10..10 in steps of 0.25 like the original slider. */
    public final IntSetting heightOffset = add(new IntSetting("heightOffset", "Height offset", 0, -1000, 1000, 25, "").decimals(2), "General");
    public final IntSetting scale = add(new IntSetting("scale", "Scale", 100, 0, 100, 5, "%"), "General");
    public final ChoiceSetting textShadow = add(new ChoiceSetting("textType", "Text shadow", "No Shadow", "No Shadow", "Shadow"), "General");
    public final BoolSetting showOwn = add(new BoolSetting("showOwnNametag", "Show own nametag", true), "General");
    public final BoolSetting showInInventory = add(new BoolSetting("showInInventory", "Show in inventory", false), "General");

    public final BoolSetting hideEntitiesF1 = add(new BoolSetting("hideEntityF1", "Hide entity nametags when HUD hidden", true), "Hidden HUD");
    public final BoolSetting hidePlayersF1 = add(new BoolSetting("hidePlayerF1", "Hide player nametags when HUD hidden", true), "Hidden HUD");
    public final BoolSetting hideArmorStandsF1 = add(new BoolSetting("hideArmorStandF1", "Hide armor stand nametags when HUD hidden", true), "Hidden HUD");

    public final BoolSetting background = add(new BoolSetting("background", "Background", true), "Style");
    public final ColorSetting backgroundColor = add(new ColorSetting("backgroundColor", "Background color", 0x3F000000), "Style");
    public final ColorSetting textColor = add(new ColorSetting("textColor", "Text color", 0xFFFFFFFF), "Style");
    public final BoolSetting overrideTextColor = add(new BoolSetting("overrideTextColor", "Override text color", false), "Style");
    public final BoolSetting rounded = add(new BoolSetting("rounded", "Rounded corners", false), "Style");
    public final IntSetting cornerRadius = add(new IntSetting("cornerRadius", "Corner radius", 3, 0, 10), "Style");
    public final IntSetting paddingX = add(new IntSetting("paddingX", "Padding X", 0, 0, 10), "Style");
    public final IntSetting paddingY = add(new IntSetting("paddingY", "Padding Y", 0, 0, 10), "Style");

    public Nametags() {
        super("nametags", "Nametags", Category.RENDER,
                "PolyNametag: scale, move, recolour and restyle nametags, and show your own.");
        instance = this;
    }

    /** The module while it is switched on, else null. */
    public static Nametags active() {
        return instance != null && instance.enabled() ? instance : null;
    }
}
