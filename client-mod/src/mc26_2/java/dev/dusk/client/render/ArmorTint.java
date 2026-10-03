package dev.dusk.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.dusk.client.modules.render.DamageTint;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * DamageTint on worn armour. Vanilla draws armour with a pipeline compiled
 * without the overlay sampler, so the hurt flash can't reach it; while an
 * entity flashes its armour is drawn with the plain no-cull entity pipeline
 * instead, which is the same shader with the overlay left in. Everything else
 * about the armour render type (lightmap, z layering, outline) is kept.
 */
public final class ArmorTint {
    /** 26.1 renamed ENTITY_CUTOUT_NO_CULL to ENTITY_CUTOUT (culling became the variant). */
    private static final RenderPipeline PIPELINE = RenderPipelines.ENTITY_CUTOUT;

    /** Armour texture → its hurt render type. Render thread only. */
    private static final Map<Identifier, RenderType> TYPES = new HashMap<>();

    private ArmorTint() {}

    /** Whether this render state's armour should flash this frame. */
    public static boolean flashing(Object state) {
        return state instanceof LivingEntityRenderState living && living.hasRedOverlay && DamageTint.tintsArmor();
    }

    /** armorCutoutNoCull for this texture, but with the overlay sampled. */
    public static RenderType renderType(Identifier texture) {
        return TYPES.computeIfAbsent(texture, tex -> RenderType.create("duskclient_hurt_armor",
                RenderSetup.builder(PIPELINE)
                        .withTexture("Sampler0", tex)
                        .useLightmap()
                        .useOverlay()
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .affectsCrumbling()
                        .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                        .createRenderSetup()));
    }
}
