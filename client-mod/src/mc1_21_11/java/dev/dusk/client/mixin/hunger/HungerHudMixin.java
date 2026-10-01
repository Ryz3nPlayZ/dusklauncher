package dev.dusk.client.mixin.hunger;

import dev.dusk.client.gui.GraphicsCanvas;
import dev.dusk.client.modules.render.HungerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hunger Info: AppleSkin's hooks around vanilla's hunger bar and hearts, so
 * the exhaustion bar sits behind the shanks and the previews line up with
 * the icons vanilla just drew.
 */
@Mixin(Gui.class)
public abstract class HungerHudMixin {
    @Inject(method = "renderFood", at = @At("HEAD"))
    private void dusk$foodPre(GuiGraphics graphics, Player player, int top, int right, CallbackInfo ci) {
        HungerInfo.onPreRenderFood(new GraphicsCanvas(graphics, Minecraft.getInstance().font), player, top, right);
    }

    @Inject(method = "renderFood", at = @At("RETURN"))
    private void dusk$foodPost(GuiGraphics graphics, Player player, int top, int right, CallbackInfo ci) {
        HungerInfo.onRenderFood(new GraphicsCanvas(graphics, Minecraft.getInstance().font), player, top, right,
                ((Gui) (Object) this).getGuiTicks());
    }

    @Inject(method = "renderHearts", at = @At("RETURN"))
    private void dusk$heartsPost(GuiGraphics graphics, Player player, int left, int top, int lines, int regeneratingHeartIndex,
                                 float maxHealth, int lastHealth, int health, int absorption, boolean blinking, CallbackInfo ci) {
        HungerInfo.onRenderHealth(new GraphicsCanvas(graphics, Minecraft.getInstance().font), player, left, top,
                ((Gui) (Object) this).getGuiTicks());
    }
}
