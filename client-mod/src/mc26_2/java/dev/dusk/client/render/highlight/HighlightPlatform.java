package dev.dusk.client.render.highlight;

import dev.dusk.client.modules.render.BlockHighlight;
import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Block Highlight's ties to 26.x: the camera, block models and the render events. */
public final class HighlightPlatform {
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private HighlightPlatform() {}

    public static void register() {
        FeatureRendererRegistry.register(HighlightFeatureRenderer.TYPE, HighlightFeatureRenderer::new);
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, outline) -> BlockHighlight.drawVanilla());
        // drawn among the frame's submits, right after the terrain, when that phase is usable;
        // otherwise END_MAIN draws it on its own
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> {
            if (HighlightGpu.featurePhaseUsable() && HighlightEngine.prepareFeature()) {
                ((FabricOrderedSubmitNodeCollector) ctx.submitNodeCollector())
                        .submitCustom(SubmitRenderPhases.AFTER_TERRAIN, HighlightFeatureRenderer.NODE);
            }
        });
        LevelRenderEvents.END_MAIN.register(ctx -> HighlightEngine.mainLoop(ctx.poseStack()));
    }

    public static Vec3 cameraPos() {
        return Minecraft.getInstance().gameRenderer.mainCamera().position();
    }

    public static void forEachQuad(BlockState state, RandomSource random, QuadSink sink) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state).collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) {
                for (BakedQuad q : part.getQuads(side)) sink.quad(q.position0(), q.position1(), q.position2(), q.position3());
            }
        }
    }
}
