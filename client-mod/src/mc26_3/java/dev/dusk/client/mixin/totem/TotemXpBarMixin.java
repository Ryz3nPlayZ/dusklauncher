package dev.dusk.client.mixin.totem;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.dusk.client.modules.hud.TotemCounter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import net.minecraft.resources.Identifier;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Totem Counter's coloured XP bar (after uku3lig's MixinExperienceBarRenderer, MIT):
 * while it is on, the bar is drawn full and tinted by how many totems are left.
 */
@Mixin(ExperienceBar.class)
public class TotemXpBarMixin {
    @Unique
    private static final Identifier BAR = Identifier.fromNamespaceAndPath("duskclient", "textures/hud/totem_xp_bar.png");

    @ModifyExpressionValue(method = "extractBackground", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/player/LocalPlayer;experienceProgress:F", opcode = Opcodes.GETFIELD))
    private float duskclient$fullBar(float original) {
        return TotemCounter.xpBarColor() != 0 ? 1 : original;
    }

    @WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
    private void duskclient$tintBar(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                    int spriteW, int spriteH, int u, int v, int x, int y, int w, int h, Operation<Void> original) {
        int argb = TotemCounter.xpBarColor();
        if (argb != 0) graphics.blit(pipeline, BAR, x, y, 0, 0, 182, 5, 182, 5, argb);
        else original.call(graphics, pipeline, sprite, spriteW, spriteH, u, v, x, y, w, h);
    }
}
