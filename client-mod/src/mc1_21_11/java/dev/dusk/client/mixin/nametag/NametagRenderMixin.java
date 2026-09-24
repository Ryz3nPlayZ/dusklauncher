package dev.dusk.client.mixin.nametag;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.render.nametag.NametagHooks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyNametag's text wrap and background shape in one: height offset, text
 * colour and shadow, background colour, and a padded or rounded background
 * drawn in place of vanilla's box. 1.21.11 flavour.
 */
@Mixin(NameTagFeatureRenderer.class)
public abstract class NametagRenderMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"))
    private void duskclient$drawNametag(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f pose,
                                        MultiBufferSource buffers, Font.DisplayMode mode, int background, int light, Operation<Void> original) {
        text = NametagHooks.text(text);
        y = NametagHooks.translateY(y);
        background = NametagHooks.backgroundColor(background);
        if (NametagHooks.useCustomBackground() && (background >>> 24) != 0) {
            VertexConsumer consumer = buffers.getBuffer(mode == Font.DisplayMode.SEE_THROUGH
                    ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground());
            int argb = NametagHooks.backgroundArgb();
            float[] v = NametagHooks.quadBuffer();
            int count = NametagHooks.backgroundQuads(x, y, NametagHooks.textWidth(font, text));
            for (int i = 0; i < count; i += 2) {
                consumer.addVertex(pose, v[i], v[i + 1], NametagHooks.BACKGROUND_DEPTH).setColor(argb).setLight(light);
            }
            background = 0;
        }
        original.call(font, text, x, y, NametagHooks.textColor(color), NametagHooks.textShadow(shadow), pose, buffers, mode, background, light);
    }
}
