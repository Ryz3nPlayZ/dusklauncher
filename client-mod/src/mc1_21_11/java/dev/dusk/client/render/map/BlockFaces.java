package dev.dusk.client.render.map;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Where this game version keeps block models and tints; the map only sees quads. */
final class BlockFaces {
    private BlockFaces() {
    }

    /** Something that changes when resource packs reload. */
    static Object modelsKey() {
        return Minecraft.getInstance().getModelManager().getMissingBlockStateModel();
    }

    private static BlockStateModel model(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
    }

    static List<BlockLooks.Part> parts(BlockState state, RandomSource random) {
        random.setSeed(42L);
        List<BlockModelPart> parts = new ArrayList<>();
        model(state).collectParts(random, parts);
        List<BlockLooks.Part> out = new ArrayList<>(parts.size());
        for (BlockModelPart part : parts) {
            out.add(direction -> {
                List<BlockLooks.Quad> quads = new ArrayList<>();
                for (BakedQuad quad : part.getQuads(direction)) {
                    quads.add(new BlockLooks.Quad(quad.sprite(), quad.tintIndex(), quad.direction()));
                }
                return quads;
            });
        }
        return out;
    }

    static TextureAtlasSprite particle(BlockState state) {
        return model(state).particleIcon();
    }

    static BlockLooks.Tint blockTint(BlockState state, int index, TintWorld probe) {
        BlockColors colors = Minecraft.getInstance().getBlockColors();
        return BlockLooks.Tint.ask(probe, () -> colors.getColor(state, probe, BlockPos.ZERO, index), () -> colors.getColor(state, null, null, index));
    }

    static TextureAtlasSprite fluidSprite(FluidState fluid) {
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
        return handler == null ? null : handler.getFluidSprites(null, null, fluid)[0];
    }

    static BlockLooks.Tint fluidTint(FluidState fluid, TintWorld probe) {
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
        if (handler == null) {
            return BlockLooks.Tint.NONE;
        }
        return BlockLooks.Tint.ask(probe, () -> handler.getFluidColor(probe, BlockPos.ZERO, fluid), () -> handler.getFluidColor(null, null, fluid));
    }
}
