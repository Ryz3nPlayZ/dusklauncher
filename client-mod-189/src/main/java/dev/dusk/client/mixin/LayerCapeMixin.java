package dev.dusk.client.mixin;

import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom capes draw blended, so PNG alpha shows as it does in MinecraftCapes
 * (vanilla 1.8 only alpha-tests capes), and with the armour glint over them
 * when the cape asks for it. The glint is LayerArmorBase's two-pass recipe.
 */
@Mixin(LayerCape.class)
public abstract class LayerCapeMixin {
    @Unique
    private static final ResourceLocation DUSK$GLINT = new ResourceLocation("textures/misc/enchanted_item_glint.png");

    @Unique
    private AbstractClientPlayer dusk$player;
    @Unique
    private float dusk$partialTicks;

    @Inject(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V", at = @At("HEAD"))
    private void dusk$capture(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
                              float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        dusk$player = player;
        dusk$partialTicks = partialTicks;
    }

    @Redirect(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/ModelPlayer;renderCape(F)V"), require = 0)
    private void dusk$renderCape(ModelPlayer model, float scale) {
        AbstractClientPlayer player = dusk$player;
        dusk$player = null;
        PlayerCosmetics c = player == null ? PlayerCosmetics.NONE : CosmeticsManager.get(player);
        if (!c.hasCape()) {
            model.renderCape(scale);
            return;
        }
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        model.renderCape(scale);
        GlStateManager.disableBlend();
        if (c.glint) dusk$glint(model, scale, player.ticksExisted + dusk$partialTicks);
    }

    @Unique
    private static void dusk$glint(ModelPlayer model, float scale, float time) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(DUSK$GLINT);
        GlStateManager.enableBlend();
        GlStateManager.depthFunc(GL11.GL_EQUAL);
        GlStateManager.depthMask(false);
        for (int i = 0; i < 2; i++) {
            GlStateManager.disableLighting();
            GlStateManager.blendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
            float k = 0.76f;
            GlStateManager.color(0.5f * k, 0.25f * k, 0.8f * k, 1f);
            GlStateManager.matrixMode(GL11.GL_TEXTURE);
            GlStateManager.loadIdentity();
            GlStateManager.scale(1f / 3f, 1f / 3f, 1f / 3f);
            GlStateManager.rotate(30f - i * 60f, 0f, 0f, 1f);
            GlStateManager.translate(0f, time * (0.001f + i * 0.003f) * 20f, 0f);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            model.renderCape(scale);
        }
        GlStateManager.matrixMode(GL11.GL_TEXTURE);
        GlStateManager.loadIdentity();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.enableLighting();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
