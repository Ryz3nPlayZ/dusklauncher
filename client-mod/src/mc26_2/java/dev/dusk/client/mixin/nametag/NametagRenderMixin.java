package dev.dusk.client.mixin.nametag;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dusk.client.render.nametag.NametagHooks;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PolyNametag's text wrap and background shape, 26.2 flavour: glyphs are
 * prepared per submit, so the shaped background is drawn for the whole group
 * first. Extends the feature renderer so its getVertexBuilder is callable.
 */
@Mixin(NameTagFeatureRenderer.class)
public abstract class NametagRenderMixin extends RenderTypeFeatureRenderer<NameTagFeatureRenderer.Submit> {
    @Inject(method = "buildGroup", at = @At("HEAD"))
    private void duskclient$drawShapedBackground(FeatureFrameContext context, List<NameTagFeatureRenderer.Submit> submits, CallbackInfo ci) {
        if (!NametagHooks.useCustomBackground()) return;
        for (NameTagFeatureRenderer.Submit submit : submits) {
            if ((NametagHooks.backgroundColor(submit.backgroundColor()) >>> 24) == 0) continue;
            VertexConsumer consumer = getVertexBuilder(submit.displayMode() == Font.DisplayMode.SEE_THROUGH
                    ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground());
            int argb = NametagHooks.backgroundArgb();
            float[] v = NametagHooks.quadBuffer();
            int count = NametagHooks.backgroundQuads(submit.x(), NametagHooks.translateY(submit.y()),
                    NametagHooks.textWidth(context.font(), submit.text()));
            for (int i = 0; i < count; i += 2) {
                consumer.addVertex(submit.pose(), v[i], v[i + 1], NametagHooks.BACKGROUND_DEPTH).setColor(argb).setLight(submit.lightCoords());
            }
        }
    }

    @WrapOperation(method = "prepareText", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"))
    private static Font.PreparedText duskclient$prepareNametag(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow,
                                                               boolean includeEmpty, int background, Operation<Font.PreparedText> original) {
        int bg = NametagHooks.useCustomBackground() ? 0 : NametagHooks.backgroundColor(background);
        return original.call(font, NametagHooks.text(text), x, NametagHooks.translateY(y),
                NametagHooks.textColor(color), NametagHooks.textShadow(shadow), includeEmpty, bg);
    }
}
