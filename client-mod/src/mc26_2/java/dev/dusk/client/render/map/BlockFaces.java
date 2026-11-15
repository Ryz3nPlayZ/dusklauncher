package dev.dusk.client.render.map;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Where this game version keeps block models and tints; the map only sees quads. */
final class BlockFaces {
    private BlockFaces() {
    }

    /** Something that changes when resource packs reload. */
    static Object modelsKey() {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    }

    private static BlockStateModel model(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
    }

    static List<BlockLooks.Part> parts(BlockState state, RandomSource random) {
        random.setSeed(42L);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model(state).collectParts(random, parts);
        List<BlockLooks.Part> out = new ArrayList<>(parts.size());
        for (BlockStateModelPart part : parts) {
            out.add(direction -> {
                List<BlockLooks.Quad> quads = new ArrayList<>();
                for (BakedQuad quad : part.getQuads(direction)) {
                    quads.add(new BlockLooks.Quad(quad.materialInfo().sprite(), quad.materialInfo().tintIndex(), quad.direction()));
                }
                return quads;
            });
        }
        return out;
    }

    static TextureAtlasSprite particle(BlockState state) {
        return model(state).particleMaterial().sprite();
    }

    static BlockLooks.Tint blockTint(BlockState state, int index, TintWorld probe) {
        BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, index);
        if (source == null) {
            return BlockLooks.Tint.NONE;
        }
        return BlockLooks.Tint.ask(probe, () -> source.colorInWorld(state, probe, BlockPos.ZERO), () -> source.color(state));
    }

    static TextureAtlasSprite fluidSprite(FluidState fluid) {
        return Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid).stillMaterial().sprite();
    }

    static BlockLooks.Tint fluidTint(FluidState fluid, TintWorld probe) {
        BlockTintSource source = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid).tintSource();
        if (source == null) {
            return BlockLooks.Tint.NONE;
        }
        BlockState block = fluid.createLegacyBlock();
        return BlockLooks.Tint.ask(probe, () -> source.colorInWorld(block, probe, BlockPos.ZERO), () -> source.color(block));
    }
}
