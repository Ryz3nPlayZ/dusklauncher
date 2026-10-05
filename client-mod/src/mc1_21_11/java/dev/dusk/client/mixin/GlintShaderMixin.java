package dev.dusk.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import dev.dusk.client.modules.render.GlintColor;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * {@link GlintColor}: teaches glint.fsh a second colour mode. A colour
 * modulator with negative alpha recolours the glint (brightness from the
 * texture, hue from the modulator); anything else draws it exactly as vanilla
 * does, so the patch is harmless while the module is off. A resource pack's
 * own glint.fsh that doesn't contain the vanilla line is left alone. After
 * ZEEG's ShaderLoaderMixin (MIT, Zapaxe).
 */
@Mixin(ShaderManager.class)
public class GlintShaderMixin {
    @Unique private static final String VANILLA = "vec4 color = texture(Sampler0, texCoord0) * ColorModulator;";
    @Unique private static final String PATCHED = "vec4 duskTex = texture(Sampler0, texCoord0);"
            + " vec4 color = ColorModulator.a < 0.0"
            + " ? vec4(max(duskTex.r, max(duskTex.g, duskTex.b)) * ColorModulator.rgb, duskTex.a)"
            + " : duskTex * ColorModulator;";

    @WrapOperation(method = "loadShader", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;process(Ljava/lang/String;)Ljava/util/List;"))
    private static List<String> duskclient$patchGlint(GlslPreprocessor pre, String source, Operation<List<String>> original,
                                                     @Local(argsOnly = true) Identifier id) {
        if (id.getPath().endsWith("/glint.fsh")) source = source.replace(VANILLA, PATCHED);
        return original.call(pre, source);
    }
}
