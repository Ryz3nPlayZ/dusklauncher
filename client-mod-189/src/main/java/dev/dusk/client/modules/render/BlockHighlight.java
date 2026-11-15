package dev.dusk.client.modules.render;

import dev.dusk.client.module.Category;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

/**
 * Block Highlight, simplified from CBH's option list (behaviour only): the
 * box around the block you're looking at gets a colour and width of its own,
 * and can be filled in. The full CBH shape picking and per-face pages are a
 * modern-build thing; one box covers what bedwars wants (placing bridges and
 * clutching without the black wireframe fighting your crosshair).
 */
public class BlockHighlight extends Module {
    private static BlockHighlight instance;

    private final ColorSetting outlineColor = add(new ColorSetting("outlineColor", "Outline colour", 0xFF00C8FF), "Outline");
    private final IntSetting width = add(new IntSetting("lineWidth", "Outline width", 100, 10, 300, 10, "%"), "Outline");
    private final BoolSetting fill = add(new BoolSetting("fill", "Fill", false), "Fill");
    private final ColorSetting fillColor = add(new ColorSetting("fillColor", "Fill colour", 0x3300C8FF), "Fill");
    private final BoolSetting throughWalls = add(new BoolSetting("throughWalls", "See through walls", false), "Outline");

    public BlockHighlight() {
        super("blockhighlight", "Block Highlight", Category.RENDER,
                "Recolours and widens the box around the block you're looking at.");
        instance = this;
    }

    public static boolean active() {
        BlockHighlight m = instance;
        return m != null && m.enabled();
    }

    /**
     * Draws the selection box vanilla would have, in our colour. Called in
     * place of GuiIngame's drawSelectionBox, in its GL state.
     */
    public static void draw(World world, EntityPlayer player, MovingObjectPosition hit, float partialTicks) {
        BlockHighlight m = instance;
        if (m == null) return;
        BlockPos pos = hit.getBlockPos();
        Block block = world.getBlockState(pos).getBlock();
        if (block.getMaterial() == Material.air || !world.getWorldBorder().contains(pos)) return;
        block.setBlockBoundsBasedOnState(world, pos);
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        net.minecraft.util.AxisAlignedBB box = block.getSelectedBoundingBox(world, pos)
                .expand(0.0020000000949949026D, 0.0020000000949949026D, 0.0020000000949949026D)
                .offset(-x, -y, -z);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        if (m.throughWalls.get()) GlStateManager.disableDepth();

        if (m.fill.get() && (m.fillColor.argb() >>> 24) > 0) {
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            GlStateManager.color(((m.fillColor.argb() >> 16) & 0xFF) / 255f,
                    ((m.fillColor.argb() >> 8) & 0xFF) / 255f,
                    (m.fillColor.argb() & 0xFF) / 255f,
                    (m.fillColor.argb() >>> 24) / 255f);
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
            // bottom / top
            wr.pos(box.minX, box.minY, box.minZ).endVertex();
            wr.pos(box.maxX, box.minY, box.minZ).endVertex();
            wr.pos(box.maxX, box.minY, box.maxZ).endVertex();
            wr.pos(box.minX, box.minY, box.maxZ).endVertex();
            wr.pos(box.minX, box.maxY, box.maxZ).endVertex();
            wr.pos(box.maxX, box.maxY, box.maxZ).endVertex();
            wr.pos(box.maxX, box.maxY, box.minZ).endVertex();
            wr.pos(box.minX, box.maxY, box.minZ).endVertex();
            // sides
            wr.pos(box.minX, box.minY, box.minZ).endVertex();
            wr.pos(box.minX, box.maxY, box.minZ).endVertex();
            wr.pos(box.maxX, box.maxY, box.minZ).endVertex();
            wr.pos(box.maxX, box.minY, box.minZ).endVertex();
            wr.pos(box.maxX, box.minY, box.maxZ).endVertex();
            wr.pos(box.maxX, box.maxY, box.maxZ).endVertex();
            wr.pos(box.minX, box.maxY, box.maxZ).endVertex();
            wr.pos(box.minX, box.minY, box.maxZ).endVertex();
            wr.pos(box.minX, box.minY, box.minZ).endVertex();
            wr.pos(box.minX, box.minY, box.maxZ).endVertex();
            wr.pos(box.minX, box.maxY, box.maxZ).endVertex();
            wr.pos(box.minX, box.maxY, box.minZ).endVertex();
            wr.pos(box.maxX, box.minY, box.maxZ).endVertex();
            wr.pos(box.maxX, box.minY, box.minZ).endVertex();
            wr.pos(box.maxX, box.maxY, box.minZ).endVertex();
            wr.pos(box.maxX, box.maxY, box.maxZ).endVertex();
            tessellator.draw();
        }

        int argb = m.outlineColor.argb();
        GL11.glLineWidth(2.0f * m.width.get() / 100f);
        GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
        net.minecraft.client.renderer.RenderGlobal.drawSelectionBoundingBox(box);

        if (m.throughWalls.get()) GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }
}
