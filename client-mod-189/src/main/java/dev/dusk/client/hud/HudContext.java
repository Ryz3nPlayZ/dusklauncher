package dev.dusk.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.multiplayer.WorldClient;

/**
 * Per-frame facts every HUD element wants: the game, the GUI-scaled screen
 * size and whether the layout editor is open (where elements with nothing to
 * show draw sample data so they can be placed).
 */
public final class HudContext {
    private final Minecraft mc;
    private final int width, height;
    private final float partialTick;
    private final boolean editing;

    public HudContext(Minecraft mc, int width, int height, float partialTick, boolean editing) {
        this.mc = mc;
        this.width = width;
        this.height = height;
        this.partialTick = partialTick;
        this.editing = editing;
    }

    public Minecraft mc() { return mc; }
    public int width() { return width; }
    public int height() { return height; }
    public float partialTick() { return partialTick; }
    public boolean editing() { return editing; }
    public FontRenderer font() { return mc.fontRendererObj; }
    public EntityPlayerSP player() { return mc.thePlayer; }
    public WorldClient level() { return mc.theWorld; }
    public int textWidth(String s) { return mc.fontRendererObj.getStringWidth(s); }
    public int lineHeight() { return mc.fontRendererObj.FONT_HEIGHT; }
}
