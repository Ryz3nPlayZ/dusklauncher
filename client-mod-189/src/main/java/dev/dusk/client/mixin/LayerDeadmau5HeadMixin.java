package dev.dusk.client.mixin;

import dev.dusk.client.cosmetics.CosmeticsManager;
import dev.dusk.client.cosmetics.PlayerCosmetics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerDeadmau5Head;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MinecraftCapes ears: a 14x7 image mapped 1:1 onto each 6x6x1 ear, placed
 * exactly where vanilla puts deadmau5's. Vanilla's ears read the skin, so
 * ours use their own model with the ears image's UV layout.
 */
@Mixin(LayerDeadmau5Head.class)
public abstract class LayerDeadmau5HeadMixin {
    @Shadow @Final private RenderPlayer playerRenderer;

    @Unique
    private static ModelRenderer dusk$ear;

    @Inject(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void dusk$ears(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
                           float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (player.isInvisible()) return;
        PlayerCosmetics c = CosmeticsManager.get(player);
        ResourceLocation tex = c.earsTexture();
        if (tex == null) return;
        ci.cancel();

        ModelRenderer head = playerRenderer.getMainModel().bipedHead;
        ModelRenderer ear = dusk$ear();
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        GlStateManager.color(1f, 1f, 1f, 1f);
        for (int i = 0; i < 2; i++) {
            GlStateManager.pushMatrix();
            if (player.isSneaking()) GlStateManager.translate(0f, 0.2f, 0f);
            GlStateManager.translate(head.rotationPointX * scale, head.rotationPointY * scale, head.rotationPointZ * scale);
            GlStateManager.rotate(netHeadYaw, 0f, 1f, 0f);
            GlStateManager.rotate(headPitch, 1f, 0f, 0f);
            GlStateManager.translate(0.375f * (i * 2 - 1), 0f, 0f);
            GlStateManager.translate(0f, -0.375f, 0f);
            GlStateManager.rotate(-headPitch, 1f, 0f, 0f);
            GlStateManager.rotate(-netHeadYaw, 0f, 1f, 0f);
            float s = 4f / 3f;
            GlStateManager.scale(s, s, s);
            ear.rotateAngleX = head.rotateAngleX;
            ear.rotateAngleY = head.rotateAngleY;
            ear.rotateAngleZ = head.rotateAngleZ;
            ear.render(scale);
            GlStateManager.popMatrix();
        }
    }

    @Unique
    private static ModelRenderer dusk$ear() {
        if (dusk$ear == null) {
            ModelBase base = new ModelBase() {};
            base.textureWidth = 14;
            base.textureHeight = 7;
            ModelRenderer r = new ModelRenderer(base, 0, 0);
            r.setTextureSize(14, 7);
            r.addBox(-3f, -6f, -1f, 6, 6, 1);
            dusk$ear = r;
        }
        return dusk$ear;
    }
}
