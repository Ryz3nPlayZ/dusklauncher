package dev.dusk.client.render.highlight;

import dev.dusk.client.modules.render.BlockHighlight;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Block Highlight's ties to 1.21.11: the camera, block models and the render events. */
public final class HighlightPlatform {
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private HighlightPlatform() {}

    public static void register() {
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, outline) -> BlockHighlight.drawVanilla());
        WorldRenderEvents.END_MAIN.register(ctx -> HighlightEngine.mainLoop(ctx.matrices()));
    }

    public static Vec3 cameraPos() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
    }

    public static void forEachQuad(BlockState state, RandomSource random, QuadSink sink) {
        for (BlockModelPart part : Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state).collectParts(random)) {
            for (Direction side : SIDES) {
                for (BakedQuad q : part.getQuads(side)) sink.quad(q.position0(), q.position1(), q.position2(), q.position3());
            }
        }
    }
}
