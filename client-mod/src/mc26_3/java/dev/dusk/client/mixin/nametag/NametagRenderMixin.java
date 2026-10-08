package dev.dusk.client.mixin.nametag;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dusk.client.render.nametag.NametagHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PolyNametag's text wrap and background shape, 26.3 flavour: nametags are
 * plain text submits there, drawn by the renderer every world text shares,
 * so they are changed where the collection builds them. The shaped
 * background goes in ahead of the text as rectangles, the only background
 * that renderer draws, with the rounded corners stepped.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class NametagRenderMixin {
    @Shadow
    @Final
    public TranslucentFeatureRenderPhase seeThrough;

    @Shadow
    private void submitNameTagPart(TextFeatureRenderer.Submit submit) {
        throw new AssertionError();
    }

    @WrapOperation(method = "submitNameTag", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;nameTag(Lorg/joml/Matrix4f;FFLnet/minecraft/util/FormattedCharSequence;IIILnet/minecraft/client/gui/Font$DisplayMode;)Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;"))
    private TextFeatureRenderer.Submit duskclient$nameTag(Matrix4f pose, float x, float y, FormattedCharSequence text, int light,
                                                          int color, int background, Font.DisplayMode mode,
                                                          Operation<TextFeatureRenderer.Submit> original) {
        float ty = NametagHooks.translateY(y);
        boolean custom = NametagHooks.useCustomBackground();
        if (custom && (NametagHooks.backgroundColor(background) >>> 24) != 0) {
            int argb = NametagHooks.backgroundArgb();
            float[] r = NametagHooks.stripBuffer();
            int count = NametagHooks.backgroundStrips(x, ty, NametagHooks.textWidth(Minecraft.getInstance().font, text));
            for (int i = 0; i < count; i += 4) {
                duskclient$submit(new TextFeatureRenderer.Submit(pose, mode, light,
                        new TextFeatureRenderer.Content.StandaloneBackground(r[i], r[i + 1], r[i + 2], r[i + 3], argb)));
            }
        }
        int bg = custom ? 0 : NametagHooks.backgroundColor(background);
        return new TextFeatureRenderer.Submit(pose, mode, light, new TextFeatureRenderer.Content.Text(
                x, ty, NametagHooks.text(text), NametagHooks.textShadow(false), NametagHooks.textColor(color), bg, 0));
    }

    /** Into the phase vanilla gives this display mode's part. */
    @Unique
    private void duskclient$submit(TextFeatureRenderer.Submit submit) {
        if (submit.displayMode() == Font.DisplayMode.SEE_THROUGH) seeThrough.submit(submit);
        else submitNameTagPart(submit);
    }
}
