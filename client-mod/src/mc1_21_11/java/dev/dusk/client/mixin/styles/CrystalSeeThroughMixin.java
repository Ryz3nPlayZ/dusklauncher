package dev.dusk.client.mixin.styles;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.dusk.client.modules.render.Styles;
import dev.dusk.client.styles.StylePacks;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Styles: a glass crystal style (a texture that is mostly half transparent)
 * draws translucent instead of cut out, so it shows through rather than
 * rendering solid. 1.21.11 only; other versions draw it cut out.
 */
@Mixin(EndCrystalRenderer.class)
public abstract class CrystalSeeThroughMixin {
    @Unique
    private static final RenderType DUSKCLIENT$GLASS = RenderTypes.entityTranslucent(
            Identifier.withDefaultNamespace("textures/entity/end_crystal/end_crystal.png"));

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void duskclient$hooked(CallbackInfo ci) {
        Styles.crystalHooked = true;
    }

    @ModifyExpressionValue(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/EndCrystalRenderer;RENDER_TYPE:Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType duskclient$glass(RenderType original) {
        return StylePacks.crystalSeeThrough() ? DUSKCLIENT$GLASS : original;
    }
}
