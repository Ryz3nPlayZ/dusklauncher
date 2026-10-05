package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.dusk.client.modules.render.GlintColor;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link GlintColor}: hands glint draws the module's colour as their colour
 * modulator, which {@link GlintShaderMixin}'s patched shader reads. 26.2
 * writes render-type transforms through the two-matrix overload, which
 * always uses a white modulator, so glint draws switch to the full one.
 */
@Mixin(RenderType.class)
public class GlintColorMixin {
    @WrapOperation(method = "writeDynamicTransforms", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private GpuBufferSlice duskclient$glintColor(DynamicUniforms uniforms, Matrix4f modelView, Matrix4f texture,
                                                 Operation<GpuBufferSlice> original) {
        if (((RenderType) (Object) this).pipeline() != RenderPipelines.GLINT) return original.call(uniforms, modelView, texture);
        float[] c = GlintColor.modulator();
        if (c == null) return original.call(uniforms, modelView, texture);
        return uniforms.writeTransform(modelView, new Vector4f(c[0], c[1], c[2], c[3]), new Vector3f(), texture);
    }
}
