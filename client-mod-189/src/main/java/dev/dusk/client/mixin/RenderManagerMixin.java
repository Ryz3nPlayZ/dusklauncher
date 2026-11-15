package dev.dusk.client.mixin;

import dev.dusk.client.modules.render.Freelook;
import dev.dusk.client.modules.render.Hitbox;
import dev.dusk.client.util.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The F3+B debug boxes, repurposed as the Hitbox module's carrier: the gate
 * opens when either vanilla debug or the module wants boxes, and the drawing
 * itself is replaced with ours (same shape, our colours and widths). OptiFine
 * touches this renderer too, so both hooks survive absence.
 */
@Mixin(RenderManager.class)
public abstract class RenderManagerMixin {
    // A FIELD redirect always passes the owner instance, even when the owner
    // is the target class itself. The original value comes from the getter.
    @Redirect(method = "doRenderEntity",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/RenderManager;debugBoundingBox:Z",
                    opcode = Opcodes.GETFIELD),
            require = 0)
    private boolean dusk$showHitboxes(RenderManager self) {
        return Hitbox.showing(self.isDebugBoundingBox());
    }

    @Inject(method = "renderDebugBoundingBox", at = @At("HEAD"), cancellable = true, require = 0)
    private void dusk$drawHitbox(Entity entity, double x, double y, double z, float entityYaw, float partialTicks,
                                 CallbackInfo ci) {
        if (!Hitbox.active()) return; // plain F3+B keeps vanilla's box
        try {
            Hitbox.draw(Minecraft.getMinecraft(), entity, x, y, z, partialTicks);
            ci.cancel();
        } catch (Throwable t) {
            Guard.fail("Hitbox draw", Hitbox.instance(), t);
        }
    }

    /** Freelook: nametags and other billboards face the orbiting camera, not the player's heading. */
    @Inject(method = "cacheActiveRenderInfo", at = @At("TAIL"), require = 0)
    private void dusk$freelookView(World world, FontRenderer font, Entity viewer, Entity pointed, GameSettings options,
                                   float partialTicks, CallbackInfo ci) {
        Freelook look = Freelook.looking();
        if (look == null || viewer != Minecraft.getMinecraft().getRenderViewEntity()) return;
        RenderManager self = (RenderManager) (Object) this;
        self.playerViewY = look.yaw() + (options.thirdPersonView == 2 ? 180F : 0F);
        self.playerViewX = look.pitch();
    }
}
