package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.gui.GraphicsCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Scoreboard tweaks: the sidebar can lose its red numbers (the ones bedwars
 * uses for counters nobody reads), shrink, and slide around. The layout is
 * redone here rather than nudged, so hiding the numbers also frees their
 * space. Same ids and defaults as the modern module.
 */
public class ScoreboardTweaks extends Module {
    private static ScoreboardTweaks instance;
    /**
     * Opaque white. Vanilla passes 0x20FFFFFF, which only looks solid while
     * blending happens to be off; with blending on (OptiFine, or no background
     * drawn) the text faded with the background.
     */
    private static final int TEXT = 0xFFFFFFFF;

    private final BoolSetting hideNumbers = add(new BoolSetting("hideNumbers", "Hide the red numbers", true), "Layout");
    private final BoolSetting hide = add(new BoolSetting("hide", "Hide the scoreboard", false), "Layout");
    private final IntSetting scale = add(new IntSetting("scale", "Scale", 100, 50, 150, 5, "%"), "Layout");
    private final IntSetting offsetX = add(new IntSetting("offsetX", "Move left", 0, -400, 0, 1, " px"), "Layout");
    private final IntSetting offsetY = add(new IntSetting("offsetY", "Move vertically", 0, -200, 200, 1, " px"), "Layout");
    // vanilla's rows are 0x50 (31%) black, its title strip 0x60
    private final IntSetting background = add(new IntSetting("background", "Background opacity", 31, 0, 100, 1, "%"), "Style");

    public ScoreboardTweaks() {
        super("scoreboard", "Scoreboard", Category.RENDER,
                "Hides the scoreboard's numbers, or the whole thing; resizes, moves and fades it.");
        instance = this;
    }

    public static boolean active() {
        ScoreboardTweaks m = instance;
        return m != null && m.enabled();
    }

    public static boolean hidden() {
        ScoreboardTweaks m = instance;
        return m != null && m.enabled() && m.hide.get();
    }

    public static boolean hidingNumbers() {
        ScoreboardTweaks m = instance;
        return m != null && m.enabled() && m.hideNumbers.get();
    }

    public static float scale() {
        ScoreboardTweaks m = instance;
        return m == null || !m.enabled() ? 1f : m.scale.get() / 100f;
    }

    public static int offsetX() {
        ScoreboardTweaks m = instance;
        return m == null || !m.enabled() ? 0 : m.offsetX.get();
    }

    public static int offsetY() {
        ScoreboardTweaks m = instance;
        return m == null || !m.enabled() ? 0 : m.offsetY.get();
    }

    /**
     * The whole sidebar, redrawn with this module's rules standing in for
     * GuiIngame's version: numbers hidden and their width reclaimed, scaled
     * around the sidebar's right-hand anchor and moved by the offsets. The
     * layout itself is vanilla's, colours and all.
     */
    public static void render(Minecraft mc, ScoreObjective objective, ScaledResolution scaledRes) {
        ScoreboardTweaks m = instance;
        if (m == null) return;
        Scoreboard scoreboard = objective.getScoreboard();
        List<Score> scores = new ArrayList<Score>();
        for (Score score : scoreboard.getSortedScores(objective)) {
            if (score.getPlayerName() != null && !score.getPlayerName().startsWith("#")) scores.add(score);
        }
        if (scores.size() > 15) scores = new ArrayList<Score>(scores.subList(scores.size() - 15, scores.size()));

        boolean numbers = !m.hideNumbers.get();
        int textWidth = mc.fontRendererObj.getStringWidth(objective.getDisplayName());
        for (Score score : scores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String entry = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            if (numbers) entry += ": " + EnumChatFormatting.RED + score.getScorePoints();
            textWidth = Math.max(textWidth, mc.fontRendererObj.getStringWidth(entry));
        }

        int lineHeight = mc.fontRendererObj.FONT_HEIGHT;
        int bottom = scaledRes.getScaledHeight() / 2 + scores.size() * lineHeight / 3;
        int edge = 3;
        int left = scaledRes.getScaledWidth() - textWidth - edge;
        int right = scaledRes.getScaledWidth() - edge + 2;

        GlStateManager.pushMatrix();
        float scale = m.scale.get() / 100f;
        // offsets in screen pixels, then the scale pivots on the sidebar's anchor
        GlStateManager.translate(m.offsetX.get(), m.offsetY.get(), 0);
        GlStateManager.translate(right, bottom, 0);
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-right, -bottom, 0);

        int rowBg = Math.round(m.background.get() * 2.55f) << 24;
        int titleBg = Math.min(255, Math.round(m.background.get() * 2.55f * 1.2f)) << 24;
        int row = 0;
        for (Score score : scores) {
            row++;
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String entry = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            String number = EnumChatFormatting.RED + "" + score.getScorePoints();
            int y = bottom - row * lineHeight;
            GraphicsCanvas.rect(left - 2, y, right, y + lineHeight, rowBg);
            mc.fontRendererObj.drawString(entry, left, y, TEXT);
            if (numbers) {
                mc.fontRendererObj.drawString(number, right - mc.fontRendererObj.getStringWidth(number), y, TEXT);
            }
            if (row == scores.size()) {
                String title = objective.getDisplayName();
                GraphicsCanvas.rect(left - 2, y - lineHeight - 1, right, y - 1, titleBg);
                GraphicsCanvas.rect(left - 2, y - 1, right, y, rowBg);
                mc.fontRendererObj.drawString(title, left + textWidth / 2 - mc.fontRendererObj.getStringWidth(title) / 2,
                        y - lineHeight, TEXT);
            }
        }
        GlStateManager.popMatrix();
    }
}
