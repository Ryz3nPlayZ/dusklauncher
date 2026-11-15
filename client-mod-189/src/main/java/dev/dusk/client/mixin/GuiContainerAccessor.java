package dev.dusk.client.mixin;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What Mouse Tweaks needs from a container screen: the hovered slot, a real click, and vanilla's drag state. */
@Mixin(GuiContainer.class)
public interface GuiContainerAccessor {
    @Invoker("getSlotAtPosition")
    Slot dusk$slotAt(int x, int y);

    @Invoker("handleMouseClick")
    void dusk$click(Slot slot, int slotId, int button, int mode);

    @Accessor("dragSplitting")
    boolean dusk$dragSplitting();

    @Accessor("dragSplitting")
    void dusk$setDragSplitting(boolean value);

    @Accessor("dragSplittingButton")
    int dusk$dragSplittingButton();

    @Accessor("ignoreMouseUp")
    void dusk$setIgnoreMouseUp(boolean value);
}
