package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.GlintColor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@link GlintColor}: hands glint draws the module's colour as their colour
 * modulator, which {@link GlintShaderMixin}'s patched shader reads.
 */
@Mixin(RenderType.class)
public class GlintColorMixin {
    @ModifyArg(method = "draw", index = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private Vector4fc duskclient$glintColor(Vector4fc color) {
        if (((RenderType) (Object) this).pipeline() != RenderPipelines.GLINT) return color;
        float[] c = GlintColor.modulator();
        return c == null ? color : new Vector4f(c[0], c[1], c[2], c[3]);
    }
}
