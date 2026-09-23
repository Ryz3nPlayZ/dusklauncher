package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;

/**
 * BactroMod's "Pumpkin Overlay" toggle: drops the view-obscuring texture a
 * carved pumpkin puts over the camera, leaving every other equipment
 * overlay alone.
 */
public class NoPumpkinBlur extends Module {
    private static NoPumpkinBlur instance;

    public NoPumpkinBlur() {
        super("nopumpkinblur", "No Pumpkin Blur", Category.RENDER,
                "Hides the overlay a carved pumpkin draws over the camera.");
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.enabled();
    }
}
